package spark

import org.scalatest.BeforeAndAfter
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import collection.mutable
import java.util.Random
import scala.math.exp
import scala.math.signum
import spark.SparkContext._

class AccumulatorSuite extends FunSuite with ShouldMatchers with BeforeAndAfter {

  var sc: SparkContext = null

  after {
    if (sc != null) {
      sc.stop()
      sc = null
    }
  }

  test ("basic accumulation"){
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0)

    val d = sc.parallelize(1 to 20)
    d.foreach{x => acc += x}
    acc.value should be (210)
  }

  test ("value not assignable from tasks") {
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0)

    val d = sc.parallelize(1 to 20)
    evaluating {d.foreach{x => acc.value = x}} should produce [Exception]
  }

  test ("add value to collection accumulators") {
    import SetAccum._
    val maxI = 1000
    for (nThreads <- List(1, 10)) { //test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc: Accumulable[mutable.Set[Any], Any] = sc.accumulable(new mutable.HashSet[Any]())
      val d = sc.parallelize(1 to maxI)
      d.foreach {
        x => acc += x
      }
      val v = acc.value.asInstanceOf[mutable.Set[Int]]
      for (i <- 1 to maxI) {
        v should contain(i)
      }
      sc.stop()
      sc = null
    }
  }


  implicit object SetAccum extends AccumulableParam[mutable.Set[Any], Any] {
    def addInPlace(t1: mutable.Set[Any], t2: mutable.Set[Any]) : mutable.Set[Any] = {
      t1 ++= t2
      t1
    }
    def addAccumulator(t1: mutable.Set[Any], t2: Any) : mutable.Set[Any] = {
      t1 += t2
      t1
    }
    def zero(t: mutable.Set[Any]) : mutable.Set[Any] = {
      new mutable.HashSet[Any]()
    }
  }


  test ("value not readable in tasks") {
    import SetAccum._
    val maxI = 1000
    for (nThreads <- List(1, 10)) { //test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc: Accumulable[mutable.Set[Any], Any] = sc.accumulable(new mutable.HashSet[Any]())
      val d = sc.parallelize(1 to maxI)
      evaluating {
        d.foreach {
          x => acc.value += x
        }
      } should produce [SparkException]
      sc.stop()
      sc = null
    }
  }

}
