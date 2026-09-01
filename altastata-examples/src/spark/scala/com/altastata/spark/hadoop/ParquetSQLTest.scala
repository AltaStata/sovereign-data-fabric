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
import org.apache.hadoop.fs.LocalFileSystem
import org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem
import org.apache.hadoop.hdfs.DistributedFileSystem

import scala.reflect.api.materializeTypeTag
import com.altastata.utils.Account

import java.io.File

case class MyCaseClass(key: String, group: String, value: Int, someints: Seq[Int], somemap: Map[String, Int])

object ParquetSQLTest extends App {
  val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123"
  val password =
    if (System.console == null) args(0)
    else new String(System.console.readPassword("Enter your secret password: "))

  val sc = SparkSession.builder
    .appName("ParquetSQLTest")
    .master("local")
    .getOrCreate().sparkContext

  val hadoopConfig: Configuration = sc.hadoopConfiguration

  hadoopConfig.set("fs.hdfs.impl", classOf[org.apache.hadoop.hdfs.DistributedFileSystem].getName)
  hadoopConfig.set("fs.file.impl", classOf[org.apache.hadoop.fs.LocalFileSystem].getName)
  hadoopConfig.set("fs.altastata.impl", classOf[org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem].getName)
  hadoopConfig.set("altastata.account.home", accountDir)
  hadoopConfig.set("altastata.account.password", password)

  val array = Array(
    MyCaseClass("a", "vowels", 1, Array(1), Map("a" -> 1)),
    MyCaseClass("b", "consonants", 2, Array(2, 2), Map("b" -> 2)),
    MyCaseClass("c", "consonants", 3, Array(3, 3, 3), Map("c" -> 3)),
    MyCaseClass("d", "consonants", 4, Array(4, 4, 4, 4), Map("d" -> 4)),
    MyCaseClass("e", "vowels", 5, Array(5, 5, 5, 5, 5), Map("e" -> 5)))

  val sqlContext = new org.apache.spark.sql.SQLContext(sc)
  
  import sqlContext.implicits._
    
  val dataframe = sc.parallelize(array).toDF()
  
  println("\n Start writing ...\n")
  
  // now write it to altastata fs
  dataframe.write.mode("overwrite").parquet("altastata:///testParquet")
  
  println("\n Start reading ...\n")

  val data = sqlContext.read.parquet("altastata:///testParquet")
  data.show()
  
}
