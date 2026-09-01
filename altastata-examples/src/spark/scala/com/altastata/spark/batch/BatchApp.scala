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

/**
 	http://stackoverflow.com/questions/30446706/implementing-custom-spark-rdd-in-java
	https://github.com/elastic/elasticsearch-hadoop/blob/master/spark/core/main/scala/org/elasticsearch/spark/rdd/ScalaEsRDD.scala
*/

import org.apache.spark.Partition
import org.apache.spark.SparkContext
import org.apache.spark.TaskContext

import scala.collection.Map
import org.apache.spark.rdd.RDD

import scala.collection.JavaConverters._
import org.apache.spark.SparkConf
import com.altastata.filesystem.securecloud.{SecureCloudFileSystemModel, SecureCloudStream}
import com.altastata.utils.Account

import java.io.{File, FilterInputStream, InputStream}
import scala.annotation.tailrec
import com.altastata.utils.Constants

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.io.Codec

object StreamState extends Enumeration {
  type StreamState = Value
  val BEFORE_PARTITION_STARTS, READ_PARTITION_STREAM, READ_CHUNKS_AFTER_THE_PARTITION, EOF_REACHED = Value
}

import com.altastata.spark.batch.StreamState._
import java.util.Properties
import java.io.FileInputStream
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream

object BatchApp extends App {
  val conf = new SparkConf().setMaster("local[*]").setAppName("Learn")
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
        
  val myRDD = new CloudFileReaderRDD(sc, Map("filePath" -> "Applications/britannica.txt", "hosts" -> List("1.1.1.1", "2.2.2.2", "3.3.3.3")))
  val counts = myRDD.flatMap(_.split(" "))
        .mapPartitionsWithIndex((index, iterator) => {
            // println("Called in Partition -> " + index)
            iterator.map(word => (word, 1))
          })
        .reduceByKey(_ + _)
                   
  println("# words.count "  + counts.count + "  words: " + counts.take(10).mkString(", ") + ", ...")
}

class CloudFileReaderRDD[T](@transient sc: SparkContext, 
               params: Map[String, Any] = Map.empty)
  extends RDD[String](sc, Nil) {

  val filePath = params.get("filePath").get.asInstanceOf[String]

  // check that the new files are in the cloud
  val lastCloudFile = BatchApp.account.fileSystemModel.listCloudFiles(filePath, true).asScala.toList.last

  // there is only one version as we ignored the other ones at the previous line, lets take it
  val totalChunks = BatchApp.account.secureCloudFileSystemModel.totalChunks(lastCloudFile.getVersions.last.getVersionDataAttribute("size").toLong).toInt
  val hosts = params.get("hosts").get.asInstanceOf[List[String]]
  
  var PARTITION_SIZE = 5
  
  val totalPartitions = totalChunks / PARTITION_SIZE + (if (totalChunks % PARTITION_SIZE == 0) 0 else 1)
    
  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val mySplit = split.asInstanceOf[CloudFileReaderPartition]
    
    val is = new AltaStataChunkedInputStream(filePath, mySplit.index * PARTITION_SIZE * Constants.PLAIN_CHUNK_MAX_SIZE, Constants.CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM)(BatchApp.account)
    
    // start reading the first partition from the beginning, and other partitions after the '\n' symbol 
    val streamFilter = 
      new CloudFilePartitionInputStreamFilter(is, mySplit.index, PARTITION_SIZE, if (mySplit.index == 0) READ_PARTITION_STREAM else BEFORE_PARTITION_STARTS) 
  
    val linesStream = Source.fromInputStream(streamFilter)(Codec.UTF8).getLines.toStream
  
    linesStream.toIterator
  }
  
  override def getPartitions: Array[Partition] = {
    val sparkPartitions = new Array[Partition](totalPartitions)
    		
	  for (partitionNumber <- 0 until totalPartitions) {
		  sparkPartitions(partitionNumber) = 
		    new CloudFileReaderPartition(partitionNumber, List(hosts(partitionNumber % hosts.size)))
		  
		  println("getPartitions: " + sparkPartitions(partitionNumber))
	  }
	      
    sparkPartitions
  }

  override def getPreferredLocations(split: Partition): Seq[String] = 
    split.asInstanceOf[CloudFileReaderPartition].getHostNames
}

class CloudFileReaderPartition(id: Int, hostNames: Seq[String]) extends Partition {
  override val index: Int = id
  /**
   * Retrieves the sequence of hostname strings assigned to this Spark partition.
   *
   * @return preferred partition hostnames
   */
  def getHostNames = hostNames
  
  override def toString = s"CloudFileReaderPartition: ${id} hostNames: ${hostNames}"
}

class CloudFilePartitionInputStreamFilter(filtered: InputStream, partitionIndex: Int, chunksInPartition: Long, var streamState: StreamState) extends FilterInputStream(filtered) {
  // counts the bytes read from the stream
  var counter = 0L
  
  override def read(): Int = {
    var character: Int = -1
    
    // filter out the first line
    while (streamState == BEFORE_PARTITION_STARTS) {
      character = in.read()
      counter = counter + 1

      if (character == '\n') {
        streamState = READ_PARTITION_STREAM       
      }
      else if (character == -1) {
        return -1
      }      
    }
    
    character = in.read()    
    counter = counter + 1

    // if within the partition
    if (streamState == READ_PARTITION_STREAM) {
      if (counter <= chunksInPartition * Constants.PLAIN_CHUNK_MAX_SIZE) {
        return character
      }
      else {
        streamState = READ_CHUNKS_AFTER_THE_PARTITION
      }
    }

    // read up to '\n' at the next chunks
    if (streamState == READ_CHUNKS_AFTER_THE_PARTITION) {
      if (character == '\n') {
        streamState = EOF_REACHED
        return -1
      }
      else {
        return character
      }
    }
    
    if (streamState == EOF_REACHED) {
      return -1
    }
    
    return -1
  }
  
  override def read(bytes: Array[Byte], off: Int, len: Int): Int = {
    var character: Int = -1
    
    // filter out the first line
    while (streamState == BEFORE_PARTITION_STARTS) {
      character = in.read()
      counter = counter + 1

      if (character == '\n') {
        streamState = READ_PARTITION_STREAM
            
        //println("\nCloudFilePartitionInputStreamFilter: READ_PARTITION_STREAM starting -- " + partitionIndex + " counter: " + counter)
      }
      else if (character == -1) {
        return -1
      }      
    }
        
    // read the asked buffer
    val length = in.read(bytes, off, len)
    counter = counter + length

    // if within the partition
    if (streamState == READ_PARTITION_STREAM) {      
      if (counter <= chunksInPartition * Constants.PLAIN_CHUNK_MAX_SIZE) {
        return length
      }
      else {
        streamState = READ_CHUNKS_AFTER_THE_PARTITION
        //println("\nCloudFilePartitionInputStreamFilter: READ_CHUNKS_AFTER_THE_PARTITION starting -- " + partitionIndex  + " counter: " + counter)
      }
    }

    // read up to '\n' at the next chunks
    if (streamState == READ_CHUNKS_AFTER_THE_PARTITION) {
      val numberOfBytesAfterPartitionBorder = counter - chunksInPartition * Constants.PLAIN_CHUNK_MAX_SIZE
      
      // look behind the partition border
      val index = bytes.indexOf('\n', length - numberOfBytesAfterPartitionBorder.toInt)
      
      if (index >= 0) {
        //println("\nCloudFilePartitionInputStreamFilter: goto EOF_REACHED -- " + partitionIndex  + " counter: " + counter + " asked: " + len + " received: " + index)
        streamState = EOF_REACHED
        return index
      }
      else {
        return length
      }
    }
    
    if (streamState == EOF_REACHED) {
      //println("\nCloudFilePartitionInputStreamFilter: EOF_REACHED -- " + partitionIndex  + " counter: " + counter)
      return -1
    }
    
    return -1
  }

}
