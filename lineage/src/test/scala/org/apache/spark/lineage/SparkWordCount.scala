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

package org.apache.spark.lineage

import org.apache.spark.lineage.LineageContext._
import org.apache.spark.{SparkConf, SparkContext}

object WordCount {
  def main(args: Array[String]) {
    val logFile = args(0)
    val conf = new SparkConf()
      .setMaster("local[2]")
      //.setMaster("mesos://SCAI01.CS.UCLA.EDU:5050")
      //.setMaster("spark://SCAI01.CS.UCLA.EDU:7077")
      .setAppName("WordCount")
    val sc = new SparkContext(conf)
    val lc = new LineageContext(sc)
    lc.setCaptureLineage(args(1).toBoolean)
    val file = lc.textFile(logFile, 2)
    val pairs = file.flatMap(line => line.trim().split(" ")).map(word => (word, 1))
    val counts = pairs.reduceByKey(_ + _)
    counts.collect().foreach(println)
    print(counts.collect().size)
  }
}
