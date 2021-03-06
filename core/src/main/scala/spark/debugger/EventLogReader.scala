package spark.debugger

import java.io._

import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spark.Logging
import spark.RDD
import spark.Dependency
import spark.ShuffleDependency
import spark.SparkContext
import spark.SparkContext._
import spark.scheduler.ResultTask
import spark.scheduler.ShuffleMapTask
import spark.scheduler.Task

/**
 * Reads events from an event log and provides replay debugging.
 */
class EventLogReader(sc: SparkContext, eventLogPath: Option[String] = None) extends Logging {
  private val events_ = new ArrayBuffer[EventLogEntry]
  private val checksumVerifier = new ChecksumVerifier
  private val rdds = new mutable.HashMap[Int, RDD[_]]

  private def getEventLogPath(): String =
    eventLogPath orElse { Option(System.getProperty("spark.debugger.logPath")) } match {
      case Some(elp) => elp
      case None => throw new UnsupportedOperationException("No event log path provided")
    }
  private var objectInputStream: EventLogInputStream = {
    val file = new File(getEventLogPath())
    if (file.exists) {
      new EventLogInputStream(new FileInputStream(file), sc)
    } else {
      throw new UnsupportedOperationException("Event log %s does not exist")
    }
  }
  loadNewEvents()

  // Receive new events as they occur
  sc.env.eventReporter.subscribe(addEvent _)

  /** Looks up an RDD by ID. */
  def rdd(id: Int): RDD[_] = rdds(id)

  /** Set of RDD IDs. */
  def rddIds: scala.collection.Set[Int] = rdds.keySet

  /** Sequence of events in the event log. */
  def events: Seq[EventLogEntry] = events_.readOnly

  /** List of checksum mismatches. */
  def checksumMismatches: Seq[ChecksumEvent] = checksumVerifier.mismatches

  /** Prints a human-readable list of RDDs. */
  def printRDDs() {
    for (RDDRegistration(rdd) <- events) {
      println("#%02d: %-20s %s".format(
        rdd.id, rddType(rdd), firstExternalElement(rdd.creationLocation)))
    }
  }

  /** Reads any new events from the event log. */
  def loadNewEvents() {
    logDebug("Loading new events from " + getEventLogPath())
    try {
      while (true) {
        val event = objectInputStream.readObject.asInstanceOf[EventLogEntry]
        addEvent(event)
      }
    } catch {
      case e: EOFException => {}
    }
  }

  /**
   * Selects the elements in startRDD that match p, traces them forward until endRDD, and returns
   * the resulting members of endRDD.
   */
  def traceForward[T, U: ClassManifest](
      startRDD: RDD[T], p: T => Boolean, endRDD: RDD[U]): RDD[U] = {
    val taggedEndRDD: RDD[Tagged[U]] = tagRDD[U, T](
      endRDD, startRDD, startRDD.map((t: T) => Tagged(t, BooleanTag(p(t)))))
    taggedEndRDD.filter(tu => tu.tag.isTagged).map(tu => tu.elem)
  }

  /**
   * Traces the given element elem from startRDD forward until endRDD and returns the resulting
   * members of endRDD.
   */
  def traceForward[T, U: ClassManifest](startRDD: RDD[T], elem: T, endRDD: RDD[U]): RDD[U] =
    traceForward(startRDD, { (x: T) => x == elem }, endRDD)

  /**
   * Traces the given element elem from endRDD backward until startRDD and returns the resulting
   * members of startRDD.
   */
  def traceBackward[T: ClassManifest, U: ClassManifest](
      startRDD: RDD[T], elem: U, endRDD: RDD[U]): RDD[T] =
    traceBackwardUsingMappings(startRDD, { (x: U) => x == elem }, endRDD)

  /**
   * Selects the elements in endRDD that match p, traces them backward until startRDD, and returns
   * the resulting members of startRDD. Implemented by backward-tracing the elements in each stage
   * starting from the last one and maintaining the set of elements of interest from one stage to
   * the previous stage.
   */
  def traceBackwardMaintainingSet[T: ClassManifest, U: ClassManifest](
      startRDD: RDD[T],
      p: U => Boolean,
      endRDD: RDD[U]): RDD[T] = {
    if (endRDD.id == startRDD.id) {
      // Casts between RDD[U] and RDD[T] are legal because startRDD is the same as endRDD, so T is
      // the same as U
      startRDD.asInstanceOf[RDD[U]].filter(p).asInstanceOf[RDD[T]]
    } else {
      val (taggedEndRDD, firstRDDInStage) = tagRDDWithinStage(
        endRDD, startRDD, getParentStageRDDs(endRDD))
      // TODO: find the set of partitions of endRDD that contain elements that match p
      val tags = sc.broadcast(taggedEndRDD.filter(tu => p(tu.elem)).map(tu => tu.tag)
        .fold(IntSetTag.empty)(_ union _))
      val sourceElems = new UniquelyTaggedRDD(firstRDDInStage)
        .filter(taggedElem => (tags.value intersect taggedElem.tag).isTagged)
        .map((tx: Tagged[_]) => tx.elem).collect()
      // Casting from RDD[_] to RDD[Any] is legal because RDD is essentially covariant
      traceBackwardMaintainingSet[T, Any](
        startRDD,
        (x: Any) => sourceElems.contains(x),
        firstRDDInStage.asInstanceOf[RDD[Any]])
    }
  }

  /**
   * Selects the elements in endRDD that match p, traces them backward until startRDD, and returns
   * the resulting members of startRDD. Implemented by uniquely tagging the elements of startRDD,
   * tracing the tags all the way to endRDD in a single step, and returning the elements in startRDD
   * whose tags ended up on the elements of interest in endRDD.
   */
  def traceBackwardSingleStep[T: ClassManifest, U: ClassManifest](
      startRDD: RDD[T], p: U => Boolean, endRDD: RDD[U]): RDD[T] = {
    val taggedEndRDD: RDD[Tagged[U]] = tagRDD[U, T](
      endRDD, startRDD, tagElements(startRDD, (t: T) => true))
    val tags = sc.broadcast(
      taggedEndRDD.filter(tu => p(tu.elem)).map(tu => tu.tag).fold(IntSetTag.empty)(_ union _))
    val taggedStartRDD = new UniquelyTaggedRDD(startRDD)
    taggedStartRDD.filter(tt => (tags.value intersect tt.tag).isTagged).map(tt => tt.elem)
  }

  /**
   * Selects the elements in endRDD that match p, traces them backward until startRDD, and returns
   * the resulting members of startRDD. Implemented using traceBackwardGivenMappings.
   */
  def traceBackwardUsingMappings[T: ClassManifest, U: ClassManifest](
      startRDD: RDD[T], p: U => Boolean, endRDD: RDD[U]): RDD[T] = {
    val stageMappings = buildBackwardTraceMappings(startRDD, endRDD)
    traceBackwardGivenMappings(startRDD, p, endRDD, stageMappings)
  }

  /**
   * Given a set of mappings from the tags of one stage to the next, selects the elements in endRDD
   * that match p, finds the tags associated with those elements, traces the tags backward using the
   * mappings, and returns the elements in startRDD associated with the resulting tags.
   */
  def traceBackwardGivenMappings[T: ClassManifest, U: ClassManifest](
      startRDD: RDD[T],
      p: U => Boolean,
      endRDD: RDD[U],
      stageMappings: List[(Option[RDD[(Tag, Tag)]], RDD[Tagged[_]], RDD[Tagged[_]])]): RDD[T] = {
    stageMappings.lastOption match {
      case Some((_, _, lastStageTaggedEndRDD)) =>
        // Casting RDD[Tagged[_]] to RDD[Tagged[U]] is legal because lastStageTaggedEndRDD is
        // actually a tagged version of endRDD
        val tagsInEndRDD = lastStageTaggedEndRDD.asInstanceOf[RDD[Tagged[U]]]
          .filter(taggedElem => p(taggedElem.elem)).map(_.tag)
        val tagsInStartRDD = stageMappings.flatMap {
          case (mappingOption, _, _) => mappingOption
        }.foldRight(tagsInEndRDD) {
          (mapping, tagsSoFar) =>
            // tagsSoFar contains a subset of the new tags in mapping. We want to trace tagsSoFar
            // backwards by one stage, so we join tagsSoFar and mapping on these new tags and
            // extract the old tag associated with each new tag.
            val mappingNewToOld = mapping.map {
              case (oldTag, newTag) => (newTag, oldTag)
            }
            tagsSoFar.map(tag => (tag, ())).join(mappingNewToOld).map {
              case (newTag, ((), oldTag)) => oldTag
            }
        }
        // Calling head is legal because we know stageMappings is non-empty, since lastOption
        // returned Some
        stageMappings.head match {
          case (_, taggedStartRDD, _) =>
            // Casting RDD[Tagged[_]] to RDD[Tagged[T]] is legal because taggedStartRDD is a tagged
            // version of startRDD
            val elems = taggedStartRDD.asInstanceOf[RDD[Tagged[T]]]
            val tags = sc.broadcast(tagsInStartRDD.fold(IntSetTag.empty)(_ union _))
            elems.filter(tt => (tags.value intersect tt.tag).isTagged).map(tt => tt.elem)
        }
      case None =>
        sc.parallelize(List())
    }
  }

  /**
   * For each stage, produces a mapping from tags propagated from the first RDD of the previous
   * stage to the current RDD, to single-element tags representing the elements in the first RDD of
   * the stage. Returns the list of such mappings, one per stage, along with the tagged start RDD of
   * the stage and the tagged end RDD.
   */
  def buildBackwardTraceMappings(
      startRDD: RDD[_], endRDD: RDD[_])
    : List[(Option[RDD[(Tag, Tag)]], RDD[Tagged[_]], RDD[Tagged[_]])] = {

    val stages: List[(RDD[_], RDD[Tagged[_]])] = tagStages(startRDD, endRDD)
    val prevStages: List[Option[(RDD[_], RDD[Tagged[_]])]] =
      None :: stages.map(stage => Some(stage))
    for (((startRDD, taggedEndRDD), prevStage) <- stages.zip(prevStages))
    yield {
      // Casting from UniquelyTaggedRDD[A] forSome { type A } to RDD[Tagged[_]] is legal because
      // UniquelyTaggedRDD[A] is effectively covariant in A
      val taggedStartRDD = new UniquelyTaggedRDD(startRDD).asInstanceOf[RDD[Tagged[_]]]
      val tagMap = prevStage.map {
        case (prevStartRDD, prevTaggedEndRDD) =>
          // prevTaggedEndRDD and taggedStartRDD are differently-tagged versions of the same RDD. We
          // join the two RDDs on their elements to extract the mapping from old tags to new tags
          // across the two stages.
          val oldTagged = prevTaggedEndRDD.map((tagged: Tagged[_]) => (tagged.elem, tagged.tag))
          val newTagged = taggedStartRDD.map((tagged: Tagged[_]) => (tagged.elem, tagged.tag))
          oldTagged.join(newTagged).map {
            case (elem, (oldTag, newTag)) => (oldTag, newTag)
          }
      }
      (tagMap, taggedStartRDD, taggedEndRDD)
    }
  }

  /**
   * For each stage from startRDD to endRDD, finds the start RDD of the stage and the end RDD tagged
   * within the stage, and returns a list of pairs of these RDDs.
   */
  private def tagStages(startRDD: RDD[_], endRDD: RDD[_]): List[(RDD[_], RDD[Tagged[_]])] = {
    if (endRDD.id == startRDD.id || !rddPathExists(startRDD, endRDD)) {
      List()
    } else {
      val (taggedEndRDD, firstRDDInStage) = tagRDDWithinStage(
        endRDD, startRDD, getParentStageRDDs(endRDD))
      // Casting RDD[Tagged[A]] forSome { type A } to RDD[Tagged[_]] is legal because RDD[Tagged[A]]
      // is effectively covariant in A
      val rddsForStage = (firstRDDInStage, taggedEndRDD.asInstanceOf[RDD[Tagged[_]]])
      tagStages(startRDD, firstRDDInStage) :+ rddsForStage
    }
  }

  private def tagRDDWithinStage[A, T](
      rdd: RDD[A],
      startRDD: RDD[T],
      parentStageRDDs: Set[RDD[_]]): (RDD[Tagged[A]], RDD[_]) = {
    if (!rddPathExists(startRDD, rdd)) {
      (rdd.map(elem => Tagged(elem, IntSetTag.empty)), startRDD)
    } else if (rdd.id == startRDD.id || parentStageRDDs.contains(rdd)) {
      (new UniquelyTaggedRDD(rdd), rdd)
    } else {
      val dependencyResults = new ArrayBuffer[RDD[_]]
      val taggedRDD = rdd.tagged(new RDDTagger {
        def apply[B](prev: RDD[B]): RDD[Tagged[B]] = {
          val (taggedPrev, firstRDDInStage) =
            tagRDDWithinStage[B, T](prev, startRDD, parentStageRDDs)
          dependencyResults += firstRDDInStage
          taggedPrev
        }
      })
      (taggedRDD, dependencyResults.max(new Ordering[RDD[_]] {
        def compare(x: RDD[_], y: RDD[_]): Int = x.id - y.id
      }))
    }
  }

  private def rddPathExists(startRDD: RDD[_], endRDD: RDD[_]): Boolean = {
    if (startRDD.id == endRDD.id) {
      true
    } else {
      (for (dep <- endRDD.dependencies; rdd = dep.rdd) yield rdd).foldLeft(false) {
        (acc, rdd) => acc || rddPathExists(startRDD, rdd)
      }
    }
  }

  private def tagRDD[A, T](
      rdd: RDD[A],
      startRDD: RDD[T],
      taggedStartRDD: RDD[Tagged[T]]): RDD[Tagged[A]] = {
    if (rdd.id == startRDD.id) {
      // (rdd: RDD[A]) is the same as (startRDD: RDD[T]), so T is the same as A, so we can cast
      // RDD[Tagged[T]] to RDD[Tagged[A]]
      taggedStartRDD.asInstanceOf[RDD[Tagged[A]]]
    } else {
      rdd.tagged(new RDDTagger {
        def apply[B](prev: RDD[B]): RDD[Tagged[B]] = {
          tagRDD[B, T](prev, startRDD, taggedStartRDD)
        }
      })
    }
  }

  /** Takes an RDD and returns a set of RDDs representing the parent stages. */
  private def getParentStageRDDs(rdd: RDD[_]): Set[RDD[_]] = {
    val parentStageRDDs = new mutable.HashSet[RDD[_]]
    val visited = new mutable.HashSet[RDD[_]]
    def visit(r: RDD[_]) {
      if (!visited(r)) {
        visited += r
        for (dep <- r.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_,_,_] =>
              parentStageRDDs.add(dep.rdd)
            case _ =>
              visit(dep.rdd)
          }
        }
      }
    }
    visit(rdd)
    // toSet is necessary because for some reason Scala doesn't think a mutable.HashSet[RDD[_]] is a
    // Set[RDD[_]]
    parentStageRDDs.toSet
  }

  private def tagElements[T](rdd: RDD[T], p: T => Boolean): RDD[Tagged[T]] = {
    new UniquelyTaggedRDD(rdd).map {
      case Tagged(elem, tag) => Tagged(elem, if (p(elem)) tag else IntSetTag.empty)
    }
  }

  private def addEvent(event: EventLogEntry) {
    events_ += event
    event match {
      case RDDRegistration(rdd) =>
        // TODO(ankurdave): Check that the RDD ID and shuffle IDs aren't already in use. This may
        // happen if the EventLogReader is passed a SparkContext that has previously been used for
        // some computation.
        logDebug("Updating RDD ID to be greater than " + rdd.id)
        sc.updateRddId(rdd.id)
        if (rdd.dependencies != null) {
          rdd.dependencies.collect {
            case shufDep: ShuffleDependency[_,_,_] => shufDep.shuffleId
          } match {
            case Seq() => {}
            case shuffleIds =>
              val maxShuffleId = shuffleIds.max
              logDebug("Updating shuffle ID to be greater than " + maxShuffleId)
              sc.updateShuffleId(maxShuffleId)
          }
        } else {
          logError("Dependency list for RDD %d (%s) is null".format(rdd.id, rdd))
        }
        rdds(rdd.id) = rdd
      case c: ChecksumEvent =>
        checksumVerifier.verify(c)
      case t: TaskSubmission =>
        t.tasks.map(_.stageId) match {
          case Seq() => {}
          case stageIds =>
            val maxStageId = stageIds.max
            logDebug("Updating stage ID to be greater than " + maxStageId)
            sc.updateStageId(maxStageId)
        }
      case _ => {}
    }
  }

  private def firstExternalElement(location: Array[StackTraceElement]) =
    (location.tail.find(!_.getClassName.matches("""spark\.[A-Z].*"""))
      orElse { location.headOption }
      getOrElse { "" })

  private def rddType(rdd: RDD[_]): String =
    rdd.getClass.getName.replaceFirst("""^spark\.""", "")
}
