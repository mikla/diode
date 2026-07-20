package diode.bench

import java.util.concurrent.TimeUnit

import diode._
import diode.data._
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.Future

case class BenchModel(counter: Int, text: String)

case class SetCounter(value: Int) extends Action

class BenchCircuit extends Circuit[BenchModel] {
  override def initialModel = BenchModel(0, "init")

  override val actionHandler: HandlerFunction = (model, action) =>
    action match {
      case SetCounter(v) => Some(ActionResult.ModelUpdate(model.copy(counter = v)))
      case _             => None
    }
}

object NoFetch extends Fetch[String] {
  override def fetch(key: String): Unit            = ()
  override def fetch(keys: Iterable[String]): Unit = ()
}

object NoFetchInt extends Fetch[Int] {
  override def fetch(key: Int): Unit            = ()
  override def fetch(keys: Iterable[Int]): Unit = ()
}

/**
  * Measures the dispatch/notification hot path with a varying number of subscribers.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class CircuitDispatchBench {

  @Param(Array("10", "100", "1000"))
  var subscribers: Int = 0

  var circuitChanged: BenchCircuit   = null
  var circuitUnrelated: BenchCircuit = null
  var circuitZip: BenchCircuit       = null
  var circuitMapped: BenchCircuit    = null
  var counter                        = 0

  @Setup
  def setup(): Unit = {
    // subscribers whose cursor changes on every dispatch
    circuitChanged = new BenchCircuit
    (1 to subscribers).foreach(_ => circuitChanged.subscribe(circuitChanged.zoom(_.counter))(_ => ()))
    // subscribers whose cursor never changes (model changes elsewhere)
    circuitUnrelated = new BenchCircuit
    (1 to subscribers).foreach(_ => circuitUnrelated.subscribe(circuitUnrelated.zoom(_.text))(_ => ()))
    // zipped subscribers (counter changes, text does not)
    circuitZip = new BenchCircuit
    val zipped = circuitZip.zoom(_.counter).zip(circuitZip.zoom(_.text))
    (1 to subscribers).foreach(_ => circuitZip.subscribe(zipped)(_ => ()))
    // mapped subscribers over an unchanging slice
    circuitMapped = new BenchCircuit
    val mapped = circuitMapped.zoomMap((m: BenchModel) => Option(m.text))(_.length)
    (1 to subscribers).foreach(_ => circuitMapped.subscribe(mapped)(_ => ()))
  }

  @Benchmark
  def dispatchChangedSubscribers(): Unit = {
    counter += 1
    circuitChanged.dispatch(SetCounter(counter))
  }

  @Benchmark
  def dispatchUnrelatedSubscribers(): Unit = {
    counter += 1
    circuitUnrelated.dispatch(SetCounter(counter))
  }

  @Benchmark
  def dispatchZipSubscribers(): Unit = {
    counter += 1
    circuitZip.dispatch(SetCounter(counter))
  }

  @Benchmark
  def dispatchMappedSubscribers(): Unit = {
    counter += 1
    circuitMapped.dispatch(SetCounter(counter))
  }
}

/**
  * Measures building sequential effect chains with `>>`.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class EffectChainBench {
  import scala.concurrent.ExecutionContext.Implicits.global

  val base: Effect = Effect(Future.successful(NoAction))

  @Benchmark
  def buildEffectChain100(bh: Blackhole): Unit = {
    var e: Effect = base
    var i         = 0
    while (i < 100) {
      e = e >> base
      i += 1
    }
    bh.consume(e)
  }
}

/**
  * Measures Pot collection updates: the old AsyncAction "rebuild everything" pattern vs the new "touch only affected
  * keys" pattern, plus single-element PotVector updates.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class PotCollectionBench {

  @Param(Array("1000", "10000"))
  var size: Int = 0

  var map: PotMap[String, String] = null
  var vector: PotVector[String]   = null
  var keys: Set[String]           = null

  @Setup
  def setup(): Unit = {
    map = PotMap(NoFetch, (0 until size).map(i => s"key$i" -> (Ready(s"value$i"): Pot[String])).toMap)
    vector = PotVector(NoFetchInt, size, (0 until size).map(i => Ready(s"value$i")))
    keys = (0 until 10).map(i => s"key$i").toSet
  }

  // the pre-optimization AsyncAction.updateInCollection pattern: rebuilds the whole map
  @Benchmark
  def mapUpdateRebuildAll(bh: Blackhole): Unit = {
    val updated = map.map { (k, v) =>
      if (keys.contains(k)) v.pending() else v
    } ++ (keys -- map.keySet).map(k => k -> (Pending(): Pot[String]))
    bh.consume(updated)
  }

  // the optimized pattern: only the affected keys are touched
  @Benchmark
  def mapUpdateTouchedOnly(bh: Blackhole): Unit = {
    val updated = map.updated(keys.iterator.map(k => k -> (Pending(): Pot[String])).toList)
    bh.consume(updated)
  }

  @Benchmark
  def vectorUpdateSingle(bh: Blackhole): Unit =
    bh.consume(vector.updated(size / 2, Ready("updated")))

  @Benchmark
  def vectorUpdateBatch10(bh: Blackhole): Unit =
    bh.consume(vector.updated((0 until 10).map(i => i -> (Ready("updated"): Pot[String]))))
}
