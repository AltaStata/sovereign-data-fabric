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

import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import java.io.FileInputStream

object TestSpark extends App {
  val sqlContext = SparkSession
    .builder()
    .appName("Spark In Action")
    .master("local")
    .getOrCreate()

  val spark = sqlContext.newSession()

  // create Spark context with Spark configuration
  val df = spark.read.format("csv").option("header", "false").load(System.getProperty("user.dir") + "/README.md")
  
  df.show()
}
