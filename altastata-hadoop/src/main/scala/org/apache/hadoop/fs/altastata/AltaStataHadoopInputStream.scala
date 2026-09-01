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

package org.apache.hadoop.fs.altastata

import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream
import com.altastata.utils.Account
import org.apache.hadoop.fs.Seekable
import org.apache.hadoop.fs.PositionedReadable
import org.apache.hadoop.fs.ByteBufferReadable

import java.nio.ByteBuffer
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

/**
 * Custom Apache Hadoop {@link org.apache.hadoop.fs.FSInputStream} implementation for reading secure AltaStata files.
 * 
 * Mixes in Hadoop's {@link Seekable}, {@link PositionedReadable}, and {@link ByteBufferReadable} traits
 * to support advanced distributed query engines like Apache Spark SQL, Presto, and Hive. Translates
 * random-access seeks, position-based reads, and vector IO operations into standard chunk-based retrievals
 * with background prefetching.
 *
 * @param filePath The absolute cloud path of the secure file.
 * @param skipTo Byte offset to start reading from.
 * @param readChunksTogether Number of contiguous chunks to prefetch concurrently.
 * @param timestamp Version epoch timestamp to resolve.
 */
class AltaStataHadoopInputStream(filePath: String, skipTo: Long, readChunksTogether: Int, timestamp: Long = System.currentTimeMillis)(implicit account: Account)
  extends AltaStataChunkedInputStream(filePath, skipTo, readChunksTogether, timestamp) with Seekable with PositionedReadable with ByteBufferReadable {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Performs a thread-safe, non-destructive positioned read. Reads up to {@code length} bytes
   * starting from the specified absolute file {@code position} into the target {@code buffer}, 
   * starting at the given {@code offset} in the buffer. 
   *
   * This method saves the current stream position, seeks to the desired target position, reads
   * the requested bytes, and then restores the original position using a mark/reset sequence, 
   * ensuring that the underlying stream's read cursor is not permanently modified.
   *
   * @param position The absolute byte offset in the cloud file to read from.
   * @param buffer   The destination byte array.
   * @param offset   The starting offset within the destination buffer array to write to.
   * @param length   The maximum number of bytes to read.
   * @return The actual number of bytes read, or -1 if the end of the stream has been reached.
   */
  def read(position: Long, buffer: Array[Byte], offset: Int, length: Int): Int = {
    mark(inputStreamPosition.toInt)
    
    seek(position)
    
    val result = read(buffer, offset, length)
    
    reset
    
    return result
  }

  /**
   * Positioned read fully: reads exactly {@code length} bytes from the given absolute {@code position}
   * into the target {@code buffer} starting at the specified {@code offset}.
   *
   * This method blocks until the exact requested number of bytes are read, or throws an exception
   * if the end of the file is reached prematurely.
   *
   * @param position The absolute byte offset in the cloud file to read from.
   * @param buffer   The destination byte array.
   * @param offset   The starting offset within the destination buffer array to write to.
   * @param length   The exact number of bytes to read.
   */
  def readFully(position: Long, buffer: Array[Byte], offset: Int, length: Int): Unit = {
    read(position, buffer, offset, length)
  }

  /**
   * Positioned read fully: reads bytes from the given absolute {@code position} to completely 
   * fill the provided {@code buffer}.
   *
   * This method blocks until the buffer is entirely filled, or throws an exception if the end 
   * of the file is reached.
   *
   * @param position The absolute byte offset in the cloud file to read from.
   * @param buffer   The destination byte array to be filled.
   */
  def readFully(position: Long, buffer: Array[Byte]): Unit = {
    read(position, buffer, 0, buffer.length)
  }
  
  /**
   * Reads data from the stream directly into a {@link java.nio.ByteBuffer}. 
   * 
   * This is a critical compatibility work-around specifically to support advanced Hadoop2 APIs 
   * and optimized readers (such as Parquet or ORC vectorized execution) on modern execution engines.
   *
   * @param buf The target {@link java.nio.ByteBuffer} into which the data is written.
   * @return The actual number of bytes read and loaded into the buffer.
   */
  override def read(buf: ByteBuffer): Int = { 
    //logger.info("read: this.getPos() -- " + this.getPos()  + " inputStreamPosition: " + inputStreamPosition.toInt + " buf.remaining: " + buf.remaining)
        
    val bytes = IOUtils.toByteArray(this, buf.remaining)
    
    buf.put(bytes)
    
    inputStreamPosition += bytes.length
    
    return bytes.length
  }
  
  // Members declared in org.apache.hadoop.fs.Seekable

  /**
   * Retrieves the current absolute read position (byte offset) in the stream.
   *
   * @return The current position in bytes from the start of the file.
   */
  def getPos(): Long = inputStreamPosition

  /**
   * Seeks to the specified absolute byte offset position in the stream.
   * Future read operations will resume from this new position.
   *
   * @param n The absolute target byte offset to seek to.
   */
  def seek(n: Long): Unit = skip(n)

  /**
   * Instructs the stream to look for an alternative data source if the current one is
   * unavailable or degraded. Not supported for the AltaStata secure cloud transport layer.
   *
   * @param n The target position on the alternative source.
   * @return Always returns {@code false} to indicate alternative sources are not supported.
   */
  def seekToNewSource(n: Long): Boolean = false
}
