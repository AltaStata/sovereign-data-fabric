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
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import com.altastata.utils.DataChannel

case class ByteBufferChannelHandler(memoryBuffer: ByteBuffer, inputStreamCache: java.util.Map[java.lang.Long, ByteBuffer] = null) extends DataChannel {

  private val logger = LoggerFactory.getLogger(getClass)

  override def cacheExist(): Boolean = if (inputStreamCache == null) false else true
  
  override def precached(chunkId: java.lang.Long): Boolean = if (!cacheExist() || inputStreamCache.get(chunkId) == null) false else true
   
  /**
   * If the chunk is cached, get the buffer from the cache instead of
   * going to the cloud 
   */
  override def getFromCache(chunkId: java.lang.Long): ByteBuffer = inputStreamCache.get(chunkId)
  
  override def putToCache(chunkId: java.lang.Long, byteBuffer: ByteBuffer): Unit = {
    inputStreamCache.put(chunkId, byteBuffer)
  }

  override def size(): Long = {
    memoryBuffer.capacity
  }

  override def read(position: Long, bufferSize: Int)(implicit ec: ExecutionContext): Try[ByteBuffer] = Try {
    val buffer = ByteBuffer.allocate(bufferSize)
    
    synchronized {
      memoryBuffer.position(position.toInt)
      memoryBuffer.get(buffer.array)
    }
    
    buffer
  }

  override def write(offset: Long, chunkPlainText: ByteBuffer)(implicit ec: ExecutionContext): Try[Int] = Try {
    synchronized {

      if (offset < 0 || offset > memoryBuffer.limit()) {
        throw new IllegalArgumentException(s"Invalid offset: $offset. Must be between 0 and ${memoryBuffer.limit()}.")
      }

      memoryBuffer.position(offset.toInt)
      memoryBuffer.put(chunkPlainText.array, 0, Math.min(memoryBuffer.limit() - memoryBuffer.position(), chunkPlainText.limit()))
    }

    chunkPlainText.limit()
  }

  override def closeOnError(): Unit = {
    close()

    memoryBuffer.clear()
    logger.warn("closeOnError: memoryBuffer cleared successfully.")
  }

  override def close(): Unit = {
    // TODO: check if its need to be implemented
  }
  
  override def toString(): String = s"ByteBufferChannelHandler -- buffer ${size()} bytes"
}
