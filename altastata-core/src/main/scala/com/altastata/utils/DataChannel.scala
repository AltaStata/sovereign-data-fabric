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

package com.altastata.utils

import scala.util.Try
import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext

/**
 * Core abstraction representing an asynchronous, random-access data channel.
 *
 * `DataChannel` encapsulates the source or destination of raw file data. Implementations 
 * can represent local disk files (`LocalFileAsynchronousChannelHandler`), in-memory byte buffers 
 * (`ByteBufferChannelHandler`), or potentially network streams. 
 *
 * It natively supports concurrent, chunk-based reads and writes at specific offsets, which 
 * is fundamental to how AltaStata handles parallel chunk transfers to and from the cloud.
 * It also defines an interface for optional chunk caching to optimize repeated reads (like video streaming).
 */
trait DataChannel {
   
  /**
   * Checks whether a cache layer exists and is supported by this channel.
   *
   * @return True if a local cache is supported.
   */
  def cacheExist(): Boolean = false

  /**
   * Checks whether the specified chunk has already been cached.
   *
   * @param chunkId The index of the chunk.
   * @return True if the chunk is present in the cache.
   */
  def precached(chunkId: java.lang.Long): Boolean = false

  /**
   * Retrieves a decrypted chunk from the local cache instead of making a cloud network call.
   *
   * @param chunkId The index of the chunk.
   * @return The ByteBuffer containing decrypted plaintext, or throws a NotSupportedException.
   */
  def getFromCache(chunkId: java.lang.Long): ByteBuffer = ???

  /**
   * Places a decrypted chunk into the local cache.
   *
   * @param chunkId    The index of the chunk.
   * @param byteBuffer The ByteBuffer containing decrypted plaintext to cache.
   */
  def putToCache(chunkId: java.lang.Long, byteBuffer: ByteBuffer): Unit = ???

  /**
   * Reads a slice of data from the data channel starting at the given position.
   *
   * @param position   The byte offset position to start reading from.
   * @param bufferSize The maximum number of bytes to read.
   * @return A Try containing the ByteBuffer of the read data.
   */
  def read(position: Long, bufferSize: Int)(implicit ec: ExecutionContext): Try[ByteBuffer]

  /**
   * Writes a ByteBuffer of data to the data channel at the given position.
   *
   * @param position The byte offset position to write the data.
   * @param buffer   The source ByteBuffer to write.
   * @return A Try containing the number of bytes successfully written.
   */
  def write(position: Long, buffer: ByteBuffer)(implicit ec: ExecutionContext): Try[Int]

  /**
   * Returns the total current size of the data channel in bytes.
   */
  def size(): Long

  /**
   * Closes the data channel immediately when an error occurs, discarding temporary resources if necessary.
   */
  def closeOnError(): Unit

  /**
   * Gracefully closes the data channel, ensuring all buffered writes are flushed to disk or storage.
   */
  def close(): Unit
}

object ChannelType extends Enumeration {
  type ChannelType = Value
  val READ, WRITE = Value
}
