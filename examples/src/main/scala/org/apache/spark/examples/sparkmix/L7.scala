/**
 * Created by shrinidhihudli on 2/7/15.
 *
 * -- This script covers having a nested plan with splits.
 * register '/usr/local/Cellar/pig/0.14.0/test/perf/pigmix/pigmix.jar'
 * A = load '/user/pig/tests/data/pigmix/page_views' using org.apache.pig.test.pigmix.udf.PigPerformanceLoader()
 *     as (user, action, timespent, query_term, ip_addr, timestamp,
 *        estimated_revenue, page_info, page_links);
 * B = foreach A generate user, timestamp;
 * C = group B by user parallel $PARALLEL;
 * D = foreach C {
 *     morning = filter B by timestamp < 43200;
 *     afternoon = filter B by timestamp >= 43200;
 *     generate group, COUNT(morning), COUNT(afternoon);
 * }
 * store D into '$PIGMIX_OUTPUT/L7out';
 *
 */
package org.apache.spark.examples.sparkmix

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import java.io.File

import org.apache.spark.lineage.LineageContext._
import org.apache.spark.lineage.LineageContext


object L7 {
  def main(args: Array[String]) {

    val properties = SparkMixUtils.loadPropertiesFile()
    val dataSize = args(0)
    val lineage: Boolean = args(1).toBoolean

    val pigMixPath = properties.getProperty("pigMix") + "pigmix_" + dataSize + "/"
    val outputRoot = properties.getProperty("output") + "pigmix_" + dataSize + "_" + (System.currentTimeMillis() / 100000 % 1000000) + "/"

    new File(outputRoot).mkdir()

    val conf = new SparkConf().setAppName("SparkMix").setMaster("local[2]")
    val sc = new SparkContext(conf)
    val lc = new LineageContext(sc)

    val pageViewsPath = pigMixPath + "page_views/"

    lc.setCaptureLineage(lineage)
    val pageViews = lc.textFile(pageViewsPath)

    val A = pageViews.map(x => (SparkMixUtils.safeSplit(x, "\u0001", 0), 
      SparkMixUtils.safeSplit(x, "\u0001", 1), SparkMixUtils.safeSplit(x, "\u0001", 2), 
      SparkMixUtils.safeSplit(x, "\u0001", 3), SparkMixUtils.safeSplit(x, "\u0001", 4), 
      SparkMixUtils.safeSplit(x, "\u0001", 5), SparkMixUtils.safeSplit(x, "\u0001", 6),
      SparkMixUtils.createMap(SparkMixUtils.safeSplit(x, "\u0001", 7)),
      SparkMixUtils.createBag(SparkMixUtils.safeSplit(x, "\u0001", 8))))

    val B = A.map(x => (x._1, SparkMixUtils.safeInt(x._6)))

    val C = B.groupByKey(properties.getProperty("PARALLEL").toInt)

    val D = C.mapValues(x => x.map(y => if (y < 43200) "morning" else "afternoon"))
      .map(x => (x._1, x._2.groupBy(identity))).map(x => (x._1, x._2.mapValues(x => x.size).map(identity)))

    D.collect.foreach(println)

    lc.setCaptureLineage(false)

    // Step by step full trace backward
    var linRdd = D.getLineage()
    linRdd.collect().foreach(println)
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show

    // Full trace backward
    linRdd = D.getLineage()
    linRdd.collect().foreach(println)
    linRdd = linRdd.goBackAll()
    linRdd.collect.foreach(println)
    linRdd.show

    // Step by step trace backward one record
    linRdd = D.getLineage()
    linRdd.collect().foreach(println)
    linRdd = linRdd.filter(1)
    linRdd.collect.foreach(println)
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show
    linRdd = linRdd.goBack()
    linRdd.collect.foreach(println)
    linRdd.show

    // Full trace backward one record
    linRdd = D.getLineage()
    linRdd.collect().foreach(println)
    linRdd = linRdd.filter(1)
    linRdd.collect.foreach(println)
    linRdd = linRdd.goBackAll()
    linRdd.collect.foreach(println)
    linRdd.show
    sc.stop()
  }
}
