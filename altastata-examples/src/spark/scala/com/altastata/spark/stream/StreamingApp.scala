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

package com.altastata.spark.stream

import org.apache.spark.streaming.receiver._
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.storage.StorageLevel

import scala.io.Source
import scala.io.Codec
import org.apache.spark.rdd.RDD

import java.io.{File, FileInputStream}
import org.apache.spark.streaming.Milliseconds
import org.apache.spark.rdd.RDD.rddToPairRDDFunctions
import org.apache.spark.streaming.dstream.DStream.toPairDStreamFunctions
import com.altastata.filesystem.securecloud.SecureCloudStream

import scala.concurrent.ExecutionContext.Implicits.global
import com.altastata.utils.Account

import java.util.Properties
import com.altastata.utils.Constants
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream

object StreamingApp extends App {

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
	  
  // === Configurations for Spark Streaming ===
  
  // Create a SparkContext
  val conf = new SparkConf().setMaster("local[*]").setAppName("StreamingAppExample")
  val sc = new SparkContext(conf)
    
  // Create a StreamingContext
  val ssc = new StreamingContext(sc, Milliseconds(1000))

  // Create a stream that returns file lines
  val stream = ssc.receiverStream(new LinesSource("Applications/britannica.txt"))
  
  // Split the lines into words, and then do word count
  val wordStream = stream.flatMap { _.split(" ") }
  val wordCountStream = wordStream.map(word => (word, 1)).reduceByKey(_ + _)
  
  var masterRDD: RDD[(String, Int)] = sc.emptyRDD
  
  wordCountStream.foreachRDD {rdd =>    
    // add microbatch to masterRDD and reduce it by key
    masterRDD = masterRDD.union(rdd).reduceByKey( (x, y) => x + y)
    
    println("# words.count in microbatch = " + rdd.count + " out of "  + masterRDD.count + "  words: " + rdd.take(10).mkString(", ") + ", ...")    
  }
  
  ssc.remember(Milliseconds(10000)) // To make sure data is not deleted by the time we query it interactively
  
  // Start the streaming context in the background.
  ssc.start
  ssc.awaitTermination
  
  // Stop streaming context
  //StreamingContext.getActive.foreach { _.stop(stopSparkContext = false) }
}

class LinesSource(path: String) extends Receiver[String](StorageLevel.MEMORY_ONLY) {
    
  /**
   * Lifecycle method triggered when the Receiver starts. Spawns a background thread
   * to fetch and parse the encrypted file contents.
   */
  def onStart() {    
    // Start the thread that receives data over a connection
    new Thread("Encrypted S3 File Source") {
      override def run() { receive() }
    }.start()
  }

  /**
   * Lifecycle method triggered when the Receiver stops. Since the worker thread checks isStopped(),
   * no heavy teardown logic is required.
   */
  def onStop() {
    // There is nothing much to do as the thread calling receive()
    // is designed to stop by itself isStopped() returns false
  }

  /** Create a connection and receive data until receiver is stopped */
  private def receive() {
    val is = new AltaStataChunkedInputStream(path, 0L, Constants.CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM)(StreamingApp.account)
    val linesStreamIt = Source.fromInputStream(is)(Codec.UTF8).getLines
    
    while (!isStopped() && linesStreamIt.hasNext) {
      store(linesStreamIt.next)   
    }
    
    println("DONE READING THE FILE: " + path)
  }
}
 
