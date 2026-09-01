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

import org.apache.spark.Partition
import org.apache.spark.SparkContext
import org.apache.spark.TaskContext

import scala.collection.Map
import org.apache.spark.rdd.RDD

import scala.collection.JavaConverters._
import org.apache.spark.SparkConf

import java.io.{File, FilterInputStream, InputStream, FileInputStream}
import scala.annotation.tailrec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.io.Codec

// Import StreamState from the existing BatchApp.scala file
import com.altastata.spark.batch.StreamState._

object BatchAppLocalFileTest extends App {
  val conf = new SparkConf().setMaster("local[*]").setAppName("LocalFileTest")
  val sc = new SparkContext(conf)
        
  // Use a local file path instead of AltaStata cloud path
  val localFilePath = System.getProperty("user.home") + "/Desktop/britannica.txt" // Change this to your local file path
  
  val myRDD = new LocalFileReaderRDD(sc, Map("filePath" -> localFilePath, "hosts" -> List("1.1.1.1", "2.2.2.2", "3.3.3.3")))
  val counts = myRDD.flatMap(_.split(" "))
        .mapPartitionsWithIndex((index, iterator) => {
            // println("Called in Partition -> " + index)
            iterator.map(word => (word, 1))
          })
        .reduceByKey(_ + _)
                   
  println("# words.count "  + counts.count + "  words: " + counts.take(10).mkString(", ") + ", ...")    
}

class LocalFileReaderRDD[T](@transient sc: SparkContext, 
               params: Map[String, Any] = Map.empty)
  extends RDD[String](sc, Nil) {

  val filePath = params.get("filePath").get.asInstanceOf[String]
  val hosts = params.get("hosts").get.asInstanceOf[List[String]]
  
  // Get file size for partitioning
  val file = new File(filePath)
  val fileSize = file.length()
  
  // Use similar partitioning logic but based on file size instead of chunks
  val PARTITION_SIZE = 5 * 1024 * 1024 // 5MB per partition (similar to chunk size)
  val totalPartitions = (fileSize / PARTITION_SIZE).toInt + (if (fileSize % PARTITION_SIZE == 0) 0 else 1)
    
  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val mySplit = split.asInstanceOf[LocalFileReaderPartition]
    
    val is = new FileInputStream(filePath)
    
    // Skip to the appropriate position for this partition
    val startPosition = mySplit.index * PARTITION_SIZE
    if (startPosition > 0) {
      is.skip(startPosition)
    }
    
    // Create a filter to read only this partition's data
    val streamFilter = 
      new LocalFilePartitionInputStreamFilter(is, mySplit.index, PARTITION_SIZE, if (mySplit.index == 0) READ_PARTITION_STREAM else BEFORE_PARTITION_STARTS) 
  
    val linesStream = Source.fromInputStream(streamFilter)(Codec.UTF8).getLines.toStream
  
    linesStream.toIterator
  }
  
  override def getPartitions: Array[Partition] = {
    val sparkPartitions = new Array[Partition](totalPartitions)
    		
	  for (partitionNumber <- 0 until totalPartitions) {
		  sparkPartitions(partitionNumber) = 
		    new LocalFileReaderPartition(partitionNumber, List(hosts(partitionNumber % hosts.size)))
		  
		  println("getPartitions: " + sparkPartitions(partitionNumber))
	  }
	      
    sparkPartitions
  }

  override def getPreferredLocations(split: Partition): Seq[String] = 
    split.asInstanceOf[LocalFileReaderPartition].getHostNames
}

class LocalFileReaderPartition(id: Int, hostNames: Seq[String]) extends Partition {
  override val index: Int = id
  /**
   * Retrieves the sequence of hostname strings assigned to this Spark partition.
   *
   * @return preferred partition hostnames
   */
  def getHostNames = hostNames
  
  override def toString = s"LocalFileReaderPartition: ${id} hostNames: ${hostNames}"
}

class LocalFilePartitionInputStreamFilter(filtered: InputStream, partitionIndex: Int, partitionSize: Long, var streamState: StreamState) extends FilterInputStream(filtered) {
  // counts the bytes read from the stream
  var counter = 0L
  
  override def read(): Int = {
    var character: Int = -1
    
    // filter out the first line for non-first partitions
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
      if (counter <= partitionSize) {
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
    
    // filter out the first line for non-first partitions
    while (streamState == BEFORE_PARTITION_STARTS) {
      character = in.read()
      counter = counter + 1

      if (character == '\n') {
        streamState = READ_PARTITION_STREAM
            
        //println("\nLocalFilePartitionInputStreamFilter: READ_PARTITION_STREAM starting -- " + partitionIndex + " counter: " + counter)
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
      if (counter <= partitionSize) {
        return length
      }
      else {
        streamState = READ_CHUNKS_AFTER_THE_PARTITION
        //println("\nLocalFilePartitionInputStreamFilter: READ_CHUNKS_AFTER_THE_PARTITION starting -- " + partitionIndex  + " counter: " + counter)
      }
    }

    // read up to '\n' at the next chunks
    if (streamState == READ_CHUNKS_AFTER_THE_PARTITION) {
      val numberOfBytesAfterPartitionBorder = counter - partitionSize
      
      // look behind the partition border
      val index = bytes.indexOf('\n', length - numberOfBytesAfterPartitionBorder.toInt)
      
      if (index >= 0) {
        //println("\nLocalFilePartitionInputStreamFilter: goto EOF_REACHED -- " + partitionIndex  + " counter: " + counter + " asked: " + len + " received: " + index)
        streamState = EOF_REACHED
        return index
      }
      else {
        return length
      }
    }
    
    if (streamState == EOF_REACHED) {
      //println("\nLocalFilePartitionInputStreamFilter: EOF_REACHED -- " + partitionIndex  + " counter: " + counter)
      return -1
    }
    
    return -1
  }
} 
