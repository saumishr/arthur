package spark.debugger

import java.io._

import scala.collection.mutable
import scala.util.MurmurHash

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._

import spark.Logging
import spark.RDD
import spark.SparkException
import spark.Utils
import spark.scheduler.ResultTask
import spark.scheduler.ShuffleMapTask
import spark.scheduler.Task
import spark.scheduler.TaskResult

sealed trait EventReporterMessage
case class LogEvent(entry: EventLogEntry) extends EventReporterMessage
case class StopEventReporter() extends EventReporterMessage

class EventReporterActor(eventLogWriter: EventLogWriter) extends Actor with Logging {
  def receive = {
    case LogEvent(entry) =>
      eventLogWriter.log(entry)

    case StopEventReporter =>
      logInfo("Stopping EventReporterActor")
      sender ! true
      context.stop(self)
  }
}

/** Manages event reporting on the master and slaves. Event reporting is thread-safe. */
trait EventReporter {
  /** Reports an exception when running a task from a slave using the Mesos executor. */
  def reportException(exception: Throwable, taskId: Long)
  /**
   * Reports an exception when running a task locally using LocalScheduler. Can only be called from
   * the master.
   */
  def reportLocalException(exception: Throwable, task: Task[_])
  /** Reports the creation of an RDD. Can only be called from the master. */
  def registerRDD(rdd: RDD[_])
  /** Reports the creation of a task. Can only be called from the master. */
  def registerTasks(tasks: Seq[Task[_]])
  /** Reports the checksum of a task's results. */
  def reportTaskChecksum(
    task: Task[_], accumUpdates: mutable.Map[Long, Any], serializedResult: Array[Byte])
  /** Reports the checksum of a block, which is typically created as the output of a task. */
  def reportBlockChecksum(blockId: String, blockBytes: Array[Byte])
  /** Allows subscription to events as they are logged. Can only be called from the master. */
  def subscribe(callback: EventLogEntry => Unit)
  /** Closes any resources held by the EventReporter, blocking until completion. */
  def stop()
}

class NullEventReporter extends EventReporter {
  override def reportException(exception: Throwable, taskId: Long) {}
  override def reportLocalException(exception: Throwable, task: Task[_]) {}
  override def registerRDD(rdd: RDD[_]) {}
  override def registerTasks(tasks: Seq[Task[_]]) {}
  override def reportTaskChecksum(
    task: Task[_], accumUpdates: mutable.Map[Long, Any], serializedResult: Array[Byte]) {}
  override def reportBlockChecksum(blockId: String, blockBytes: Array[Byte]) {}
  override def subscribe(callback: EventLogEntry => Unit) {}
  override def stop() {}
}

// TODO(ankurdave): Consider separating master and slave functionality.
// TODO(ankurdave): Unit-test this class.
class ActorBasedEventReporter(
  actorSystem: ActorSystem, isMaster: Boolean) extends EventReporter with Logging {

  private val ip: String = System.getProperty("spark.master.host", "localhost")
  private val port: Int = System.getProperty("spark.master.port", "7077").toInt
  private val actorName: String = "EventReporter"
  private val enableChecksumming = System.getProperty("spark.debugger.checksum", "true").toBoolean
  private val timeout = 10.seconds
  private var eventLogWriter: Option[EventLogWriter] =
    if (isMaster) {
      Some(new EventLogWriter)
    } else {
      None
    }
  private var reporterActor: ActorRef = if (isMaster) {
    val actor = actorSystem.actorOf(
      Props(new EventReporterActor(eventLogWriter.get)), name = actorName)
    logInfo("Registered EventReporterActor actor")
    actor
  } else {
    val url = "akka://spark@%s:%s/user/%s".format(ip, port, actorName)
    actorSystem.actorFor(url)
  }
  /**
   * IDs of registered RDDs, used when registering an RDD and all its dependencies. Only used on the
   * master.
   */
  private val rddIds = new mutable.HashSet[Int]

  override def reportException(exception: Throwable, taskId: Long) {
    report(LogEvent(RemoteExceptionEvent(exception, taskId)))
  }

  override def reportLocalException(exception: Throwable, task: Task[_]) {
    report(LocalExceptionEvent(exception, task))
  }

  override def registerRDD(newRDD: RDD[_]) {
    def visit(rdd: RDD[_]) {
      if (!rddIds.contains(rdd.id)) {
        rddIds.add(rdd.id)
        report(RDDRegistration(rdd))
        for (dep <- rdd.dependencies) {
          visit(dep.rdd)
        }
      }
    }
    visit(newRDD)
  }

  override def registerTasks(tasks: Seq[Task[_]]) {
    report(TaskSubmission(tasks))
  }

  override def reportTaskChecksum(
      task: Task[_],
      accumUpdates: mutable.Map[Long, Any],
      serializedResult: Array[Byte]) {
    if (enableChecksumming) {
      val checksum = new MurmurHash[Byte](42) // constant seed so checksum is reproducible
      task match {
        case rt: ResultTask[_,_] =>
          for (byte <- serializedResult) checksum(byte)
          val serializedFunc = Utils.serialize(rt.func)
          val funcChecksum = new MurmurHash[Byte](42)
          for (byte <- serializedFunc) funcChecksum(byte)
          report(LogEvent(ResultTaskChecksum(
            rt.rdd.id, rt.partition, funcChecksum.hash, checksum.hash)))
        case smt: ShuffleMapTask =>
          // Don't serialize the output of a ShuffleMapTask, only its
          // accumulator updates. The output is a URI that may change.
          val serializedAccumUpdates = Utils.serialize(accumUpdates)
          for (byte <- serializedAccumUpdates) checksum(byte)
          report(LogEvent(ShuffleMapTaskChecksum(smt.rdd.id, smt.partition, checksum.hash)))
        case _ =>
          logWarning("unknown task type: " + task)
      }
    }
  }

  override def reportBlockChecksum(blockId: String, blockBytes: Array[Byte]) {
    if (enableChecksumming) {
      val blockChecksum = new MurmurHash[Byte](42)
      for (byte <- blockBytes) blockChecksum(byte)
      report(LogEvent(BlockChecksum(blockId, blockChecksum.hash)))
    }
  }

  override def subscribe(callback: EventLogEntry => Unit) {
    for (elw <- eventLogWriter) {
      elw.subscribe(callback)
    }
  }

  override def stop() {
    if (askReporter(StopEventReporter) != true) {
      throw new SparkException("Error reply received from EventReporter")
    }
    for (elw <- eventLogWriter) {
      elw.stop()
    }
    eventLogWriter = None
    reporterActor = null
  }

  /** Used for reporting from either the master or a slave. */
  private def report(message: EventReporterMessage) {
    // ActorRef.tell is thread-safe.
    reporterActor.tell(message)
  }

  /** Used only for reporting from the master. */
  private def report(entry: EventLogEntry) {
    for (elw <- eventLogWriter) {
      // EventLogWriter.log is thread-safe.
      elw.log(entry)
    }
  }

  /**
   * Sends a message to the reporterActor and gets its result within a default timeout, or throws a
   * SparkException if this fails.
   */
  private def askReporter(message: Any): Any = {
    try {
      val future = reporterActor.ask(message)(timeout)
      return Await.result(future, timeout)
    } catch {
      case e: Exception =>
        throw new SparkException("Error communicating with EventReporter", e)
    }
  }
}
