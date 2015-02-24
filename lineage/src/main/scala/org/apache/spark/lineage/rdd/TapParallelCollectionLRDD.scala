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
import org.apache.spark.lineage.LineageContext

import scala.reflect.ClassTag

private[spark]
class TapParallelCollectionLRDD[T: ClassTag](
    @transient lc: LineageContext, @transient deps: Seq[Dependency[_]]
  ) extends TapLRDD[T](lc, deps)
{
  def this(@transient prev: ParallelCollectionLRDD[_]) =
    this(prev.lineageContext, List(new OneToOneDependency(prev)))

  override def tap(record: T) = {
    recordId = (id.toShort, splitId, newRecordId)
    //tContext.currentRecordInfos = Seq(recordId)
    addRecordInfo(recordId, Seq(record))

    record
  }
}
