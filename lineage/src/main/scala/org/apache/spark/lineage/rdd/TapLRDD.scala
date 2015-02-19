/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.lineage.rdd

import org.apache.spark._
import org.apache.spark.lineage.{LCacheManager, LineageContext, NewtWrapper}
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ArrayBuffer
import scala.reflect._
import scala.util.Random

private[spark]
class TapLRDD[T: ClassTag](@transient lc: LineageContext, @transient deps: Seq[Dependency[_]])
    extends RDD[T](lc.sparkContext, deps) with Lineage[T] {

  setCaptureLineage(true)

  @transient private[spark] var splitId: Short = 0

  @transient private[spark] var tContext: TaskContext = null

  @transient private[spark] var recordId: (Short, Short, Int) = (0, 0, 0)

  @transient private[spark] var recordIdShort: (Short, Int) = (0, 0)

  // TODO make recordInfo grow in memory and spill to disk if needed
  @transient private[spark] var recordInfo: ArrayBuffer[(Any, Any)] = null

  @transient private[spark] var nextRecord: Int = 0

  private[spark] var shuffledData: Lineage[_] = null

  private[spark] def newRecordId = {
    nextRecord += 1
    nextRecord
  }

  private[spark] def addRecordInfo(key: (Short, Int), value: Any) = {
    recordInfo += key -> value
  }

  private[spark] def addRecordInfo(key: (Short, Short, Int), value: Seq[(_)]) = {
    recordInfo += key -> value
  }

  //TODO Ksh
  var newt: NewtWrapper = null;

  /**
   * Compute an RDD partition or read it from a checkpoint if the RDD was checkpointed.
   */
  private[spark] override def computeOrReadCheckpoint(
     split: Partition,
     context: TaskContext): Iterator[T] = compute(split, context)

  override def ttag = classTag[T]

  override def lineageContext: LineageContext = lc

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  override def materializeRecordInfo: Array[Any] = recordInfo.toArray

  override def compute(split: Partition, context: TaskContext) = {
    if(tContext == null) {
      tContext = context
    }
    splitId = split.index.toShort

    recordInfo = new ArrayBuffer[(Any, Any)]()

    //TODO Ksh
    //Using Random Int to avoid same table names
    val newtId:Int = splitId + Random.nextInt(Integer.MAX_VALUE);
    newt = new NewtWrapper(newtId)


    SparkEnv.get.cacheManager.asInstanceOf[LCacheManager].initMaterialization(this, split)

    val iterator = firstParent[T].iterator(split, context).map(tap)

    iterator
  }

  override def cleanTable = {
    recordInfo.clear()
    recordInfo = null
  }

  override def filter(f: T => Boolean): Lineage[T] =
    new FilteredLRDD[T](this, sparkContext.clean(f))

  def setCached(cache: Lineage[_]): TapLRDD[T] = {
    shuffledData = cache
    this
  }

  def getCachedData = shuffledData.setIsPostShuffleCache()

  def tap(record: T) = {
    recordIdShort = (splitId, newRecordId)
    addRecordInfo(recordIdShort, tContext.currentRecordInfo)
    tContext.currentRecordInfo = recordIdShort

    record
  }
}