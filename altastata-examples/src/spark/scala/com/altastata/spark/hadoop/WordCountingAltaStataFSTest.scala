/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0
 * license on the Change Date.
 *
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.spark.hadoop

import org.apache.spark.sql.SparkSession
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import java.io.File
import java.net.URI
import org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem
import org.apache.spark.rdd.RDD.rddToPairRDDFunctions
import com.altastata.utils.Account

object WordCountingAltaStataFSTest extends App {
  val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123"
  val password = args(0)
  val cloudPath = "altastata:///Applications/britannica.txt"

  val sparkBuilder = SparkSession.builder
    .appName("WordCountingAltaStataFSTest")
    .master("local[4]")
    .getOrCreate()

  val hadoopConfig: Configuration = sparkBuilder.sparkContext.hadoopConfiguration
  hadoopConfig.set("fs.altastata.impl", classOf[AltaStataHadoopFileSystem].getName)
  hadoopConfig.set("altastata.account.home", accountDir)
  hadoopConfig.set("altastata.account.password", password)

  if (args.length > 1) {
    val fs = FileSystem.get(new URI("altastata:///"), hadoopConfig)
    val dest = new Path(cloudPath)
    println("Uploading " + args(1) + " -> " + dest)
    fs.copyFromLocalFile(false, true, new Path(args(1)), dest)
  } else {
    Option(System.getenv("BRITANNICA_LOCAL")).filter(p => new File(p).isFile).foreach { local =>
      val fs = FileSystem.get(new URI("altastata:///"), hadoopConfig)
      val dest = new Path(cloudPath)
      println("Uploading " + local + " -> " + dest)
      fs.copyFromLocalFile(false, true, new Path(local), dest)
    }
  }

  val tf = sparkBuilder.sparkContext.textFile(cloudPath, 5)

  val counts = tf.flatMap(_.split(" "))
    .map(word => (word, 1))
    .reduceByKey(_ + _)

  println("# words.count " + counts.count + "  words: " + counts.take(10).mkString(", ") + ", ...")

  counts.saveAsTextFile("altastata:///Applications/results/counts")
}
