package org.apache.spark.lineage.rdd

import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.spark.SparkContext._
import org.apache.spark.lineage.LineageContext
import org.apache.spark.rdd._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils
import org.apache.spark.util.collection.CompactBuffer
import org.apache.spark.{Dependency, OneToOneDependency}

import scala.language.implicitConversions
import scala.reflect.ClassTag

trait Lineage[T] extends RDD[T] {

  implicit def ttag: ClassTag[T]

  @transient def lineageContext: LineageContext

  protected var tapRDD : Option[TapLRDD[_]] = None

  private[spark] var captureLineage: Boolean = false

  def tapRight(): TapLRDD[T] =
  {
    val tap = new TapLRDD[T](lineageContext,  Seq(new OneToOneDependency(this)))
    setTap(tap)
    setCaptureLineage(true)
    tap
  }

  def tapLeft(): TapLRDD[T] =
  {
    tapRight()
  }

  def tap(deps: Seq[Dependency[_]]): TapLRDD[T] =
  {
    val tap = new TapLRDD[T](lineageContext, deps)
    tap.checkpointData = checkpointData
    checkpointData = None
    tap
  }

  def materialize =
  {
    storageLevel = StorageLevel.MEMORY_ONLY
    this
  }

  def setTap(tap: TapLRDD[_] = null) =
  {
    if(tap == null) {
      tapRDD = None
    } else {
      tapRDD = Some(tap)
    }
  }

  def getTap() = tapRDD

  def setCaptureLineage(newLineage :Boolean) =
  {
    captureLineage = newLineage
    this
  }

  def isLineageActive: Boolean = captureLineage

  def getLineage(): LineageRDD =
  {
    if(getTap().isDefined) {
      lineageContext.setCurrentLineagePosition(getTap())
      return new LineageRDD(getTap().get)
    }
    throw new UnsupportedOperationException("no lineage support for this RDD")
  }

  private[spark] def rightJoin(
    prev: Lineage[((Int, Int, Long), Any)],
    next: Lineage[((Int, Int, Long), Any)]) =
  {
    prev.zipPartitions(next) {
      (buildIter, streamIter) =>
        val hashSet = new java.util.HashSet[(Int, Int, Long)]()
        var rowKey: (Int, Int, Long) = null


        // Create a Hash set of buildKeys
        while (buildIter.hasNext) {
          rowKey = buildIter.next()._1
          val keyExists = hashSet.contains(rowKey)
          if (!keyExists) {
            hashSet.add(rowKey)
          }
        }

        streamIter.filter(current => {
          hashSet.contains(current._1)
        })
    }
  }

  private[spark] def join3Way(
    prev: Lineage[((Int, Int, Long), Any)],
    next1: Lineage[((Int, Int, Long), (String, Long))],
    next2: Lineage[(Long, String)]) =
  {
    prev.zipPartitions(next1,next2) {
      (buildIter, streamIter1, streamIter2) =>
        val hashSet = new java.util.HashSet[(Int, Int, Long)]()
        val hashMap = new java.util.HashMap[Long, CompactBuffer[(Int, Int, Long)]]()
        var rowKey: (Int, Int, Long) = null

        while (buildIter.hasNext) {
          rowKey = buildIter.next()._1
          val keyExists = hashSet.contains(rowKey)
          if (!keyExists) {
            hashSet.add(rowKey)
          }
        }

        while(streamIter1.hasNext) {
          val current = streamIter1.next()
          if(hashSet.contains(current._1)) {
            var values = hashMap.get(current._2)
            if(values == null) {
              values = new CompactBuffer[(Int, Int, Long)]()
            }
            values += current._1
            hashMap.put(current._2._2, values)
          }
        }
        streamIter2.flatMap(current => {
          val values = if(hashMap.get(current._1) != null) {
            hashMap.get(current._1)
          } else {
            new CompactBuffer[(Int, Int, Long)]()
          }
          values.map(record => (record, current._2))
        })
    }
  }

  /** Returns the first parent Lineage */
  protected[spark] override def firstParent[U: ClassTag]: Lineage[U] =
    dependencies.head.rdd.asInstanceOf[Lineage[U]]

  /**
   * Return an array that contains all of the elements in this RDD.
   */
  override def collect(): Array[T] =
  {
    val results = lineageContext.runJob(this, (_: Iterator[T]).toArray)

    if(lineageContext.isLineageActive) {
      lineageContext.setLastLineagePosition(this.getTap())
    }

    Array.concat(results: _*)
  }

  /**
   * Return the number of elements in the RDD.
   */
  override def count(): Long = {
    val result = lineageContext.runJob(this, Utils.getIteratorSize _).sum

    if(lineageContext.isLineageActive) {
      lineageContext.setLastLineagePosition(this.getTap())
    }

    result
  }

  /**
   * Return a new LRDD containing only the elements that satisfy a predicate.
   */
  override def filter(f: T => Boolean): Lineage[T] =
  {
    if(this.getTap().isDefined) {
      lineageContext.setCurrentLineagePosition(this.getTap())
      var result: ShowRDD = null
      this.getTap().get match {
        case _: TapPreShuffleLRDD[_] =>
          val tmp = this.getTap().get
            .getCachedData.setCaptureLineage(false)
            .asInstanceOf[Lineage[(Any, (Any, (Int, Int, Long)))]]
          tmp.setTap()
          result = new ShowRDD(tmp
            .map(r => ((r._1, r._2._1), r._2._2)).asInstanceOf[Lineage[(T, (Int, Int, Long))]]
            .filter(r => f(r._1))
            .map(r => (r._2, r._1.toString()))
          )
          tmp.setTap(lineageContext.getCurrentLineagePosition.get)
        case _: TapPostShuffleLRDD[_] =>
          val tmp = this.getTap().get
            .getCachedData.setCaptureLineage(false)
            .asInstanceOf[Lineage[(T, (Int, Int, Long))]]
          tmp.setTap()
          result = new ShowRDD(tmp.filter(r => f(r._1)).map(r => (r._2, r._1.toString())))
          tmp.setTap(lineageContext.getCurrentLineagePosition.get)
        case _ => throw new UnsupportedOperationException
      }
      result.setTap(this.getTap().get)
      return result.asInstanceOf[Lineage[T]]
    } else {
      this.dependencies(0).rdd match {
        case _: TapHadoopLRDD[_, _] =>
          var result: ShowRDD = null
          lineageContext.setCurrentLineagePosition(
            Some(this.dependencies(0).rdd.asInstanceOf[TapHadoopLRDD[_, _]])
          )
          result = new ShowRDD(this.dependencies(0).rdd
            .firstParent.asInstanceOf[HadoopLRDD[LongWritable, Text]]
            .map(r=> (r._1.get(), r._2.toString)).asInstanceOf[RDD[(Long, T)]]
            .filter(r => f(r._2))
            .join(this.dependencies(0).rdd.asInstanceOf[Lineage[((Int, Int, Long), (String, Long))]]
            .map(r => (r._2._2, r._1)))
            .distinct()
            .map(r => (r._2._2, r._2._1)).asInstanceOf[Lineage[((Int, Int, Long), String)]])
          result.setTap(lineageContext.getCurrentLineagePosition.get)
          return result.asInstanceOf[Lineage[T]]
        case _ =>
      }
    }
    new FilteredLRDD[T](this, context.clean(f))
  }

  /**
   *  Return a new RDD by first applying a function to all elements of this
   *  RDD, and then flattening the results.
   */
  override def flatMap[U: ClassTag](f: T => TraversableOnce[U]): Lineage[U] =
    new FlatMappedLRDD[U, T](this, lineageContext.sparkContext.clean(f))

  /**
   * Return a new RDD by applying a function to all elements of this RDD.
   */
  override def map[U: ClassTag](f: T => U): Lineage[U] = new MappedLRDD(this, sparkContext.clean(f))

  override def zipPartitions[B: ClassTag, V: ClassTag]
  (rdd2: RDD[B])
  (f: (Iterator[T], Iterator[B]) => Iterator[V]): Lineage[V] =
    new ZippedPartitionsLRDD2[T, B, V](
      lineageContext,
      lineageContext.sparkContext.clean(f),
      this,
      rdd2.asInstanceOf[Lineage[B]],
      false
    )

  override def zipPartitions[B: ClassTag, C: ClassTag, V: ClassTag]
  (rdd2: RDD[B], rdd3: RDD[C])
  (f: (Iterator[T], Iterator[B], Iterator[C]) => Iterator[V]): Lineage[V] =
    new ZippedPartitionsLRDD3[T, B, C, V](
      lineageContext,
      lineageContext.sparkContext.clean(f),
      this,
      rdd2.asInstanceOf[Lineage[B]],
      rdd3.asInstanceOf[Lineage[C]],
      false
    )
}

object Lineage {
  implicit def castLineage1(rdd: Lineage[_]): Lineage[((Int, Int, Long), Any)] =
    rdd.asInstanceOf[Lineage[((Int, Int, Long), Any)]]

  implicit def castLineage2(rdd: Lineage[(Any, (Int, Int, Long))]): Lineage[((Int, Int, Long), Any)] =
    rdd.asInstanceOf[Lineage[((Int, Int, Long), Any)]]

  implicit def castLineage3(rdd: Lineage[_]): TapLRDD[_] =
    rdd.asInstanceOf[TapLRDD[_]]

  implicit def castLineage4(rdd: Lineage[((Int, Int, Long), Any)]): Lineage[((Int, Int, Long), String)] =
    rdd.asInstanceOf[Lineage[((Int, Int, Long), String)]]
}