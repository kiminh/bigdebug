package org.apache.spark.lineage.perfdebug.utils

import org.apache.spark.util.PackIntIntoLong
import org.apache.spark.util.collection.CompactBuffer

/** Global class to define PartitionWithRecId and the valid cache values. These classes are
 * intended to provide an external-friendly storage format for various storage/tracing solutions.
 */
object CacheDataTypes {
  
  /** PartitionWithRecId is a common format used throughout Titian - I've simply created a
    * wrapper to abstract out the details
    */
  case class PartitionWithRecId(value: Long) {
    def this(partition: Int, id: Int) = this(PackIntIntoLong(partition, id))
    def this(tuple: (Int, Int)) = this((PackIntIntoLong.apply _).tupled(tuple))
    def partition = PackIntIntoLong.getLeft(value)
    def split = partition // alias
    def recordId = PackIntIntoLong.getRight(value)
    def asTuple = PackIntIntoLong.extractToTuple(value)
    
    override def toString: String = asTuple.toString()
  }
  
  object PartitionWithRecId {
    // https://stackoverflow.com/a/19348339/6890456
    // specifically the end of the accepted solution, 'enterprise'
    implicit def ordering: Ordering[PartitionWithRecId] = Ordering.by(_.value)
  }

  /** Base class for easy interpretation of record key in ignite.
   * As of initial implementation, every cache actually has records of (value.key, value)
   */
  abstract class CacheValue {
    val outputId: PartitionWithRecId
    /** Alias for outputId, used for k-v storage */
    final val key: PartitionWithRecId = outputId
    def inputIds: Seq[PartitionWithRecId]
    def cacheValueString: String // set up for future serialization perhaps?
    
    override final def toString: String = cacheValueString
  
    /** Define hash and equals to avoid any possible clashes with internal data representations
     * and unnecessary checks - each record within a cache should be uniquely identified by the
     * key, so that's what we use here. It's possible to have a "wrong" equals
     * comparison as a result of this definition, but that would imply incorrect usage of these
     * classes. */
    override final def hashCode(): Int = key.hashCode()
  
    override final def equals(obj: scala.Any): Boolean = obj match {
      case other: CacheValue =>
        // oversimplification, but cache values of different classes shouldn't be compared in the
        // first place. This is primarily a developer class.
        this.key == other.key
      case _ => false
    }
  }
  
  /** Cache values that correspond to tap RDDs at the end of a stage. As of
   * 8/17/2018, these are TapLRDD, TapPreShuffleLRDD, TapPreCoGroupLRDD.
   */
  abstract class EndOfStageCacheValue extends CacheValue {
    def partialLatencies: Seq[Long]
    /** Returns input IDs zipped with partial latencies. */
    def inputKeysWithPartialLatencies: Seq[(PartitionWithRecId, Long)] =
      inputIds.zip(partialLatencies)
  }
  trait ValueWithMultipleOutputLatencies extends CacheValue {
    /** Returns the input IDs zipped with their measured latencies. This is primarily used for
     * the pre-CoGroup and pre-Shuffle RDDs.
     * In general, this is implemented by zipping inputIDs with latencies in the same
     * corresponding order.
     */
    def inputKeysWithLatencies: Seq[(PartitionWithRecId, Long)]
  }
  
  /** TapLRDD cache value */
  case class TapLRDDValue(override val outputId: PartitionWithRecId,
                          private  val inputId: PartitionWithRecId,
                          private  val latency: Long)
    extends EndOfStageCacheValue {
    
    override def inputIds: Seq[PartitionWithRecId] = Seq(inputId)
  
    override def partialLatencies: Seq[Long] = Seq(latency)
    
    override def cacheValueString = s"($outputId => $inputId, $latency)"
  }
  
  object TapLRDDValue {
    def fromRecord(r: Any) = {
      val (outputLong, inputRecId, latency) = r.asInstanceOf[(Long, Long,Long)]
      // note from jteoh:
      // In Titian, the input consists solely of the record ID as partition ID is the same and
      // the join procedure was within partitions anyways. As this is no longer the case, we
      // augment the input ID with partition after the fact. Note that this is technically
      // slightly suboptimal in that we would ideally directly modify Titian. For the time being,
      // I'm trying to preserve Titian code appearance for reference purposes. Feel free to write
      // this directly into TapLRDD in the future though, to simplify the coupling.
      // note from 8/7/2018 - the inputRecId.toInt is safe - it's actually an int originally, but
      // the buffer used in TapLRDD happens to use Long instead (which I've preserved to
      // minimalize changes)
      val output = PartitionWithRecId(outputLong)
      val split = output.split
      val inputWithSplit = new PartitionWithRecId(split, inputRecId.toInt)
      TapLRDDValue(output, inputWithSplit, latency)
    }
    def readableSchema = s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) " +
      "=> ((InputPartitionId,InputRecId), LatencyMs)"
  }
  
  case class TapHadoopLRDDValue(override val outputId: PartitionWithRecId,
                                byteOffset: Long, // in hadoop file
                                latency: Long)
    extends CacheValue {
    
    override def inputIds: Seq[PartitionWithRecId] =
      throw new UnsupportedOperationException("TapHadoopLRDDs represent the start of an execution tree and do not have other lineage input")
    
    override def cacheValueString = s"($outputId => $byteOffset, $latency)"
  }
  
  object TapHadoopLRDDValue {
    def fromRecord(r: Any) = {
      val tuple = r.asInstanceOf[(Long, Long,Long)]
      // implicitly rely on conversions to proper data types here, rather than using
      // `tupled` and using native types
      // As noted in TapHadoopLRDD, the first and second argument need to be swapped.
      TapHadoopLRDDValue(PartitionWithRecId(tuple._2), tuple._1, tuple._3)
    }
    def readableSchema = s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) " +
      "=> (ByteOffset, LatencyMs)"
  }
  
  // ----------- SHUFFLE VALUES start ---------
  
  // Note (jteoh): inputIds is int[] because the split is always the same as the split found in
  // outputId. (Not sure why TapLRDD decided to specify long, but that's not the case here)
  // These names might not be the most appropriate and are subject to change.
  case class TapPreShuffleLRDDValue(override val outputId: PartitionWithRecId,
                                    private  val inputRecIds: Array[Int],
                                    private  val outputRecordLatencies: Array[Long])
    extends EndOfStageCacheValue {
  
    override def inputIds: Seq[PartitionWithRecId] =
      inputRecIds.map(new PartitionWithRecId(outputId.partition, _))
  
    override def partialLatencies: Seq[Long] = outputRecordLatencies
    
    override def cacheValueString = s"$outputId => ([${inputRecIds.mkString(",")}], " +
      s"[${outputRecordLatencies.mkString(",")}])"
  }
  
  object TapPreShuffleLRDDValue {
    def fromRecord(r: Any) = {
      val tuple = r.asInstanceOf[((Int, Int), Array[Int], Array[Long])]
      TapPreShuffleLRDDValue(new PartitionWithRecId(tuple._1), tuple._2, tuple._3)
    }
    // input partition is always same as output
    def readableSchema = s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) => " +
      "([InputRecId*], [OutputLatencyMs*])"
  }
  
  // outputId: split + hashcode for key (not murmur!)
  // inputPartitionLongs: inputIDs that mapped to the same key. Tentative, depending on ext sorter.
  //  7/12/2018 jteoh - these only appear to be used for their partitions right now, but it used
  // to be a long.
  // inputKeyHash: murmur hash (ideally unique) of the corresponding key
  // outputRecordLatency: how long it took within the stage to tap this record. Currently unused
  // (0).
  case class TapPostShuffleLRDDValue(override val outputId: PartitionWithRecId,
                                     private  val inputPartitionLongs: CompactBuffer[Long],
                                     private  val inputKeyHash: Int
                                    )
    extends CacheValue {
    
    // See comments above - inputIds is only used for partition.
    override def inputIds: Seq[PartitionWithRecId] =
      // getLeft == partition
      inputPartitionLongs.map(inp =>
                                new PartitionWithRecId(PackIntIntoLong.getLeft(inp), inputKeyHash))
  
    override def cacheValueString = s"$outputId => ([${inputIds.mkString(",")}], " +
      s"$inputKeyHash)"
  }
  
  object TapPostShuffleLRDDValue {
    def fromRecord(r: Any) = {
      // jteoh: Looking at LineageRDD#goBack(), the CompactBuffer[Long]'s values are only used with
      // the PackIntIntoLeft.getLeft method. I haven't pinpointed where exactly the Longs here are
      // being created s.t. the left side consists of input splits though. For now we keep this
      // consistent with Titian, but it might be possible to convert this compact buffer to an Int
      // collection.
      // For exact location: search "jteoh" - it's not explicitly labeled as a
      // TapPostShuffleLRDD, but the schema is identical.
      val tuple = r.asInstanceOf[(Long, (CompactBuffer[Long], Int))]
      TapPostShuffleLRDDValue(PartitionWithRecId(tuple._1), tuple._2._1, tuple._2._2)
    }
    def readableSchema =
      s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) => ([(InputPartitionId, " +
        "InputRecId)*], InputKeyHash)"
  }
  
  case class TapPreCoGroupLRDDValue(override val outputId: PartitionWithRecId,
                                    private  val inputRecIds: Array[Int],
                                    private  val outputRecordLatencies: Array[Long])
    extends EndOfStageCacheValue {
  
    override def inputIds: Seq[PartitionWithRecId] =
      inputRecIds.map(new PartitionWithRecId(outputId.partition, _))
    
    override def partialLatencies: Seq[Long] = outputRecordLatencies
  
    override def cacheValueString = s"$outputId => ([${inputRecIds.mkString(",")}], " +
      s"[${outputRecordLatencies.mkString(",")}])"
  
  }
  // ----------- SHUFFLE VALUES end ---------
  
  
  // ----------- COGROUP VALUES start ---------
  object TapPreCoGroupLRDDValue {
    def fromRecord(r: Any) = {
      val tuple = r.asInstanceOf[((Int, Int), Array[Int], Array[Long])]
      // jteoh: 8/7/2018 - not using latencies for cogroup
      TapPreCoGroupLRDDValue(new PartitionWithRecId(tuple._1), tuple._2, tuple._3)
    }
    // input partition is always same as output
    def readableSchema = s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) => " +
      "([InputRecId*], [OutputLatencyMs*])"
  }
  
  
  /**
   * Essentially identical to TapPostShuffleLRDDValue. It should, however, be noted that the
   * inputIds are a union all (ie with dups) of the input partitions across all dependencies.
   */
  case class TapPostCoGroupLRDDValue(override val outputId: PartitionWithRecId,
                                     private  val inputPartitionLongs: CompactBuffer[Long],
                                     private  val inputKeyHash: Int)
  extends CacheValue {
    
    override def inputIds: Seq[PartitionWithRecId] =
      inputPartitionLongs.map(inp => new PartitionWithRecId(PackIntIntoLong.getLeft(inp),inputKeyHash))
  
    override def cacheValueString = s"$outputId => ([${inputIds.mkString(",")}], " +
      s"$inputKeyHash)"
  }
  
  object TapPostCoGroupLRDDValue {
    def fromRecord(r: Any) = {
      val tuple = r.asInstanceOf[(Long, (CompactBuffer[Long], Int))]
      TapPostCoGroupLRDDValue(PartitionWithRecId(tuple._1), tuple._2._1, tuple._2._2)
    }
    def readableSchema = s"[${getClass.getSimpleName}] (OutputPartitionId, OutputRecId) => ([(InputPartitionId, " +
      "InputRecId)*], InputKeyHash)"
  }
  
  // ----------- COGROUP VALUES end ---------
}