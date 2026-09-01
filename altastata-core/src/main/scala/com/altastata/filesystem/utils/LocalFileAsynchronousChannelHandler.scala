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

package com.altastata.filesystem.utils

import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.util.{Try, Success, Failure}
import scala.util.control.Exception.catching
import org.slf4j.LoggerFactory
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.io.File
import com.altastata.utils.{Constants, DataChannel}
import com.altastata.utils.ChannelType._

object LocalFileAsynchronousChannelHandler {
  /**
   * One 8 MiB heap buffer per chunk-store worker. G1 treats each
   * {@code ByteBuffer.allocate(8MiB)} as humongous; allocating a fresh one
   * per chunk OOMs while VisualVM still shows most of {@code -Xmx} free.
   * {@code G1HeapRegionSize} cannot be changed from running Java.
   */
  private val chunkReadBuffer = new ThreadLocal[ByteBuffer] {
    override def initialValue(): ByteBuffer =
      ByteBuffer.allocate(Constants.PLAIN_CHUNK_MAX_SIZE)
  }
}

case class LocalFileAsynchronousChannelHandler(filePath: String, channelType: ChannelType) extends DataChannel {

  private val logger = LoggerFactory.getLogger(getClass)

  lazy val fileChannel = channelType match {
    case READ => {
      val path = Paths.get(filePath)
  
      assert(Files.exists(path), s"File $filePath does not exist")
  
      AsynchronousFileChannel.open(path, StandardOpenOption.READ)
    }
    case WRITE => { // write
      val parent = new File(filePath).getParentFile
      if (parent != null && !parent.exists()) {
        // mkdirs() returns false when another parallel download already created the dir;
        // Files.createDirectories is idempotent and avoids that race.
        Files.createDirectories(parent.toPath)
      }
      assert(parent != null && parent.isDirectory, s"Cannot create dir: $parent")

      AsynchronousFileChannel.open(Paths.get(filePath), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    }
  }
  
  override def size(): Long = fileChannel.size
  
  /**
   * http://examples.javacodegeeks.com/core-java/nio/channels/asynchronousfilechannel/java-nio-channels-asynchronousfilechannel-example/
   */
  override def read(position: Long, bufferSize: Int)(implicit ec: ExecutionContext): Try[ByteBuffer] = Try {
    val buffer =
      if (bufferSize <= Constants.PLAIN_CHUNK_MAX_SIZE) {
        val reused = LocalFileAsynchronousChannelHandler.chunkReadBuffer.get()
        reused.clear()
        reused.limit(bufferSize)
        reused
      } else {
        ByteBuffer.allocate(bufferSize)
      }

    val readFuture = fileChannel.read(buffer, position)
    readFuture.get()

    buffer.flip()

    buffer
  }

  /**
   * http://niklasschlimm.blogspot.com/2012/04/java-7-asynchronous-file-channels-part.html
   */
  override def write(position: Long, buffer: ByteBuffer)(implicit ec: ExecutionContext): Try[Int] = Try {
    val writeFuture = fileChannel.write(buffer, position)
        
    writeFuture.get()
  }

  override def closeOnError(): Unit = {
    close()

    try {
      Files.delete(Paths.get(filePath))
      logger.warn(s"closeOnError: ${filePath} deleted successfully.")
    } catch {
      case e: Exception => logger.error(s"closeOnError: Failed to delete the file ${filePath} ${e.getMessage}")
    }
  }

  override def close(): Unit = {
    if (fileChannel.isOpen) {
      fileChannel.close
    }
  }
  
  override def toString(): String = s"LocalFileAsynchronousChannelHandler -- ${filePath}"
}
