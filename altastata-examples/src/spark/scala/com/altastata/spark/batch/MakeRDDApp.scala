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

package com.altastata.spark.batch

import org.slf4j.LoggerFactory
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import com.altastata.filesystem.common.CloudFile

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.io.Codec
import java.io.{File, FileInputStream, InputStream}
import org.apache.spark.rdd.RDD
import com.altastata.filesystem.securecloud.SecureCloudFileSystemModel

import scala.concurrent.ExecutionContext.Implicits.global
import com.altastata.filesystem.securecloud.SecureCloudStream
import com.altastata.utils.Account

import java.nio.ByteBuffer
import com.altastata.utils.Constants

import java.util.Properties
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream

object MakeRDDApp extends App {

  private val logger = LoggerFactory.getLogger(getClass)
  
  val conf = new SparkConf().setMaster("local[*]").setAppName("MakeRDDApp")  
  val sc = new SparkContext(conf)

  val account = new Account()

  account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123")

	val console = System.console
  if (console == null) {
    account.setPassword(args(0).toCharArray)
  }
  else {
    val passwordArray = console.readPassword("Enter your secret password: ")
    account.setPassword(passwordArray)
  }

  val is = new AltaStataChunkedInputStream("Applications/britannica.txt", 0L, Constants.CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM)(account)
  
  val linesStream = Source.fromInputStream(is)(Codec.UTF8).getLines.toStream
  
//  val linesStreamIt = linesStream.iterator
//  
//  var stop = 5
//  while (linesStreamIt.hasNext && stop > 0) {
//      println(linesStreamIt.next)
//      stop = stop - 1
//  }

  val partitionsNumber = 5
  
  val counts = sc.makeRDD(linesStream, partitionsNumber)
                   .flatMap(_.split(" "))
                   .map(word => (word, 1))
                   .reduceByKey(_ + _)
                   
  println("# words.count "  + counts.count + "  words: " + counts.take(10).mkString(", ") + ", ...")    
}
