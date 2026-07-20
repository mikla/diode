package diode.data

import diode.Implicits.runAfterImpl

import scala.annotation.tailrec

class PotVector[V](
    private val fetcher: Fetch[Int],
    private val length: Int,
    private val elems: Vector[Option[Pot[V]]]
) extends PotCollection[Int, V] {

  // returns a backing vector that is at least `newSize` long, padding with `None` when needed
  private def extended(newSize: Int): Vector[Option[Pot[V]]] =
    if (newSize <= elems.length) elems else elems.padTo(newSize, None)

  override def updated(idx: Int, value: Pot[V]): PotVector[V] = {
    if (idx < 0 || idx >= length)
      throw new IndexOutOfBoundsException
    new PotVector(fetcher, length, extended(idx + 1).updated(idx, Some(value)))
  }

  override def updated(kvs: Iterable[(Int, Pot[V])]): PotVector[V] = {
    val (minIdx, maxIdx) = kvs.foldLeft((Int.MaxValue, Int.MinValue)) {
      case ((min, max), (idx, _)) =>
        (math.min(min, idx), math.max(max, idx))
    }
    if (minIdx < 0 || maxIdx >= length)
      throw new IndexOutOfBoundsException
    val newElems = kvs.foldLeft(extended(maxIdx + 1)) {
      case (v, (idx, value)) => v.updated(idx, Some(value))
    }
    new PotVector(fetcher, length, newElems)
  }

  override def updated(start: Int, values: Iterable[Pot[V]])(implicit num: Numeric[Int]): PotVector[V] = {
    val end = start + values.size
    if (start < 0 || end > length)
      throw new IndexOutOfBoundsException
    if (end <= start)
      return this

    var newElems = extended(end)
    var idx      = start
    values.foreach { value =>
      newElems = newElems.updated(idx, Some(value))
      idx += 1
    }

    new PotVector(fetcher, length, newElems)
  }

  override def seq: Iterable[(Int, Pot[V])] = {
    var out = List.empty[(Int, Pot[V])]
    // iterate in reverse order to get the output list in correct order
    for (idx <- elems.indices.reverse) {
      elems(idx) match {
        case Some(value) =>
          out ::= idx -> value
        case _ =>
      }
    }
    out
  }

  override def iterator: Iterator[(Int, Pot[V])] = new Iterator[(Int, Pot[V])] {
    @tailrec private def findNext(idx: Int): Option[Int] = {
      if (idx >= elems.length)
        None
      else if (elems(idx).isEmpty)
        findNext(idx + 1)
      else
        Some(idx)
    }
    private var current                = findNext(0)
    override def hasNext: Boolean      = current.nonEmpty
    override def next(): (Int, Pot[V]) = {
      val idx = current.get
      current = findNext(idx + 1)
      idx -> elems(idx).get
    }
  }

  override def remove(idx: Int): PotVector[V] = {
    if (idx < 0 || idx >= length)
      throw new IndexOutOfBoundsException
    if (idx < elems.length)
      new PotVector(fetcher, length, elems.updated(idx, None))
    else
      this
  }

  override def refresh(idx: Int): Unit = {
    if (idx < 0 || idx >= length)
      throw new IndexOutOfBoundsException
    // perform fetch asynchronously
    runAfterImpl.runAfter(0)(fetcher.fetch(idx))
  }

  override def refresh(indices: Iterable[Int]): Unit = {
    if (indices.exists(idx => idx < 0 || idx >= length))
      throw new IndexOutOfBoundsException
    // perform fetch asynchronously
    runAfterImpl.runAfter(0)(fetcher.fetch(indices))
  }

  override def clear: PotVector[V] =
    new PotVector(fetcher, length, Vector.empty[Option[Pot[V]]])

  override def get(idx: Int): Pot[V] = {
    if (idx < 0 || idx >= length)
      throw new IndexOutOfBoundsException
    if (idx >= elems.length || elems(idx).isEmpty) {
      refresh(idx)
      Pending().asInstanceOf[Pot[V]]
    } else {
      elems(idx).get
    }
  }

  /**
    * Returns the raw value at `idx` without triggering a refresh for missing values.
    */
  def rawGet(idx: Int): Option[Pot[V]] =
    if (idx >= 0 && idx < elems.length) elems(idx) else None

  override def map(f: (Int, Pot[V]) => Pot[V]): PotVector[V] = {
    val newElems = Vector.tabulate(elems.length)(idx => elems(idx).map(value => f(idx, value)))
    new PotVector(fetcher, length, newElems)
  }

  def slice(start: Int, end: Int): Seq[Pot[V]] = {
    if (start < 0 || end >= length)
      throw new IndexOutOfBoundsException
    if (end <= start)
      return Seq()
    var missing = List.empty[Int]
    val values  = (start until end).map { idx =>
      if (idx >= elems.length || elems(idx).isEmpty) {
        missing ::= idx
        Pending().asInstanceOf[Pot[V]]
      } else {
        elems(idx).get
      }
    }
    // are all missing?
    if (missing.size == end - start)
      runAfterImpl.runAfter(0)(fetcher.fetch(start, end))
    else if (missing.nonEmpty)
      refresh(missing)

    values
  }

  def resized(newLength: Int) =
    new PotVector(fetcher, newLength, if (newLength < elems.length) elems.take(newLength) else elems)

  def contains(idx: Int): Boolean = {
    if (idx < 0 || idx >= length)
      throw new IndexOutOfBoundsException
    idx < elems.length && elems(idx).isDefined
  }
}

object PotVector {
  def apply[V](fetcher: Fetch[Int], length: Int, elems: Seq[Pot[V]] = Seq.empty[Pot[V]]) =
    new PotVector[V](fetcher, length, elems.iterator.map(Some(_)).toVector)
}
