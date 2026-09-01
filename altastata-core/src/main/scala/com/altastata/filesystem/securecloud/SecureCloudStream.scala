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

package com.altastata.filesystem.securecloud

import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.api.CloudFileOperationStatus
import org.slf4j.LoggerFactory
import com.altastata.filesystem.common.CloudFile

import java.io.InputStream
import scala.concurrent._
import java.nio.ByteBuffer
import com.altastata.utils.Account

import scala.collection.JavaConverters._
import java.io.OutputStream
import java.io.IOException
import java.io.FileInputStream
import com.altastata.utils.Constants

import scala.concurrent.ExecutionContext.Implicits.global
import java.util.Collections
import java.util.HashMap
import com.altastata.filesystem.common.FileSystemHandler
import com.altastata.filesystem.utils.ByteBufferChannelHandler

import java.io.FileNotFoundException
import scala.util.{Try, Failure}
import scala.collection.mutable
import scala.language.postfixOps
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.Duration

object SecureCloudStream extends SecureCloudOperations {

  /**
   * Safe, high-performance chunked InputStream for reading secure encrypted AltaStata cloud files.
   * 
   * It transparently retrieves encrypted file chunks from cloud storage, decrypts them on the fly 
   * using AES-GCM, and validates metadata signatures. Includes advanced features such as:
   * - Lazy and parallel look-ahead prefetching of chunk blocks.
   * - Sequential read optimizations.
   * - Fully thread-safe caching of decrypted chunks using a local Caffeine map.
   *
   * @param filePath The absolute cloud path of the secure file.
   * @param skipTo Byte offset to start reading from.
   * @param readChunksTogether Number of contiguous chunks to prefetch concurrently.
   * @param timestamp Version epoch timestamp to resolve.
   * @param trustCachedSize True to skip remote metadata verification on size checks.
   */
  class AltaStataChunkedInputStream(filePath: String, skipTo: Long, readChunksTogether: Int, timestamp: Long = System.currentTimeMillis, trustCachedSize: Boolean = false)(implicit account: Account) extends InputStream {

    private val logger = LoggerFactory.getLogger(getClass)

    var cloudFile = account.fileSystemModel.listCloudFiles(filePath, true).asScala.toList.last

    val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
    val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)

    val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
    val dataSizeAttribute = retrieveCloudFileDataAttribute(storageObjectMetadata, "size", trustCachedSize).get.toLong

    logger.info(s"new AltaStataChunkedInputStream objectPath: ${storageObjectMetadata.getObjectPath}")

    checkIfMetadataIsSignedByMyself(storageObjectMetadata)

    val totalChunksNumber = totalChunks(dataSizeAttribute, Constants.PLAIN_CHUNK_MAX_SIZE)

    val inputStreamCacheMap = Collections.synchronizedMap(new HashMap[java.lang.Long, ByteBuffer])

    var inputStreamPosition = skipTo
    var markedPosition = 0L
    var backgroundFetchInProgress = false

    // "Current chunk" model: serve reads directly from the plaintext of the chunk that
    // holds the read position, instead of re-allocating and re-copying a multi-MiB buffer
    // on every read call. currentChunkId == -1 means nothing is loaded yet.
    private var currentChunkId: Long = -1L
    private var currentChunkBuffer: ByteBuffer = null

    // Async look-ahead: while the caller consumes the current batch, fetch the next batch
    // in the background so crossing a batch boundary does not block on the network. Only
    // one prefetch is in flight at a time; on a cache miss inside the prefetched range the
    // reader waits on this future instead of issuing a duplicate fetch.
    @volatile private var prefetchFuture: Future[Unit] = null
    @volatile private var prefetchStart: Long = -1L
    @volatile private var prefetchEnd: Long = -1L

    override def markSupported(): Boolean = true

    /**
     * Marks the current position in the input stream.
     * Supports mark/reset operations to allow seeking backward if needed.
     */
    override def mark(readlimit: Int) = {
      markedPosition = inputStreamPosition
    }

    /**
     * Resets the input stream position back to the previously marked position.
     */
    override def reset() = {
      inputStreamPosition = markedPosition
    }

    /**
     * Reads a single byte from the input stream.
     *
     * @return The read byte as an integer (0-255), or -1 if the end of the stream is reached.
     */
    override def read(): Int = {
      if (logger.isDebugEnabled) logger.debug(s"read(): Starting read at inputStreamPosition=${inputStreamPosition}")

      if (inputStreamPosition >= dataSizeAttribute) return -1

      val chunkId = inputStreamPosition / Constants.PLAIN_CHUNK_MAX_SIZE
      ensureChunkLoaded(chunkId)

      val intra = (inputStreamPosition % Constants.PLAIN_CHUNK_MAX_SIZE).toInt
      if (intra >= currentChunkBuffer.limit()) return -1

      currentChunkBuffer.position(intra)
      val b = currentChunkBuffer.get() & 0xFF
      inputStreamPosition += 1
      b
    }

    /**
     * Reads up to len bytes of data from the input stream into an array of bytes.
     * Automatically handles chunk boundaries and pulls additional decrypted chunks from the cache or cloud.
     *
     * @param bytes The destination buffer.
     * @param off   The start offset in destination array.
     * @param len   The maximum number of bytes to read.
     * @return The total number of bytes read, or -1 if the end of stream is reached.
     */
    override def read(bytes: Array[Byte], off: Int, len: Int): Int = {
      if (logger.isDebugEnabled) logger.debug(s"read(bytes): Starting read at inputStreamPosition=${inputStreamPosition}, len=${len}")

      if (len == 0) return 0
      if (inputStreamPosition >= dataSizeAttribute) return -1

      // Internal loop across chunk boundaries: fill the caller's buffer until len is
      // reached or EOF, so consumers never see a short read before EOF. Each chunk is
      // served from currentChunkBuffer (no per-call multi-MiB allocation/copy).
      var total = 0
      while (total < len && inputStreamPosition < dataSizeAttribute) {
        val chunkId = inputStreamPosition / Constants.PLAIN_CHUNK_MAX_SIZE
        ensureChunkLoaded(chunkId)

        val intra = (inputStreamPosition % Constants.PLAIN_CHUNK_MAX_SIZE).toInt
        if (intra >= currentChunkBuffer.limit()) {
          // Chunk is shorter than expected (truncated/last chunk) → stop here.
          return if (total == 0) -1 else total
        }

        val availInChunk = currentChunkBuffer.limit() - intra
        val remainingInFile = dataSizeAttribute - inputStreamPosition
        val n = math.min(math.min((len - total).toLong, availInChunk.toLong), remainingInFile).toInt

        currentChunkBuffer.position(intra)
        currentChunkBuffer.get(bytes, off + total, n)

        inputStreamPosition += n
        total += n
      }

      total
    }

    /**
     * Make currentChunkBuffer hold the plaintext of the requested chunk. Evicts cached
     * chunks below the new chunk to keep the in-memory window bounded.
     */
    private def ensureChunkLoaded(chunkId: Long): Unit = {
      if (chunkId != currentChunkId || currentChunkBuffer == null) {
        // Bounded cache: drop chunks we have already moved past. Synchronize the compound
        // iterate-and-remove against concurrent puts from the background prefetch.
        inputStreamCacheMap.synchronized {
          inputStreamCacheMap.keySet().removeIf(_ < java.lang.Long.valueOf(chunkId))
        }

        currentChunkBuffer = loadChunkBuffer(chunkId)
        currentChunkId = chunkId

        if (logger.isDebugEnabled) logger.debug(s"Reading chunk ${chunkId}")

        // Phase 4 (async prefetch) disabled: the end-of-batch trigger gives only a small
        // overlap window and brings no benefit for greedy sequential reads. Re-enable by
        // uncommenting this call and maybeSchedulePrefetch below if a workload (busy
        // consumer between reads) is shown to benefit.
        // maybeSchedulePrefetch(chunkId)
      }
    }

    /**
     * Return an independent read view (duplicate) of the plaintext for chunkId, fetching
     * it (with readChunksTogether look-ahead) on a cache miss. If a background prefetch
     * already covers this chunk, wait on it instead of issuing a duplicate fetch.
     */
    private def loadChunkBuffer(chunkId: Long): ByteBuffer = {
      val key = java.lang.Long.valueOf(chunkId)

      val cached = inputStreamCacheMap.get(key)
      if (cached != null) return cached.duplicate()

      val pf = prefetchFuture
      if (pf != null && chunkId >= prefetchStart && chunkId <= prefetchEnd) {
        try Await.result(pf, Duration.Inf) catch { case _: Throwable => () }
        val afterPrefetch = inputStreamCacheMap.get(key)
        if (afterPrefetch != null) return afterPrefetch.duplicate()
      }

      val big = retrieveChunksWithExpandedRange(chunkId, chunkId)

      val reCached = inputStreamCacheMap.get(key)
      if (reCached != null) reCached.duplicate()
      else big.duplicate() // single-chunk path that bypasses the per-chunk cache
    }

    /**
     * If the next chunk is not yet cached and no prefetch is in flight, fetch the next
     * batch (readChunksTogether) in the background on the download pool.
     *
     * Phase 4 (async prefetch) is currently DISABLED — see the commented call in
     * ensureChunkLoaded. Kept here so it can be re-enabled without re-deriving it.
     */
    /*
    private def maybeSchedulePrefetch(currentChunk: Long): Unit = {
      val nextChunk = currentChunk + 1L
      if (nextChunk >= totalChunksNumber) return
      if (prefetchFuture != null) return
      if (inputStreamCacheMap.get(java.lang.Long.valueOf(nextChunk)) != null) return

      implicit val ec = OpsExecutors.ecFileDownloadOps

      prefetchStart = nextChunk
      prefetchEnd = Math.min(nextChunk + readChunksTogether - 1, totalChunksNumber - 1)

      val f: Future[Unit] = Future {
        retrieveChunksWithExpandedRange(nextChunk, nextChunk)
        ()
      }
      prefetchFuture = f
      f.onComplete { res =>
        res match {
          case Failure(e) => logger.warn(s"prefetch from ${nextChunk} failed: ${e.getMessage}")
          case _ =>
        }
        prefetchStart = -1L
        prefetchEnd = -1L
        prefetchFuture = null
      }
    }
    */

    /**
     * Estimates the number of remaining bytes that can be read from this input stream.
     * Used by HTTP streaming layers (such as Play Framework servers).
     *
     * @return The estimated remaining bytes or Int.MaxValue if exceeding limits.
     */
    override def available(): Int = {
      if (dataSizeAttribute < Int.MaxValue) dataSizeAttribute.toInt
      else Int.MaxValue
    }

    /**
     * Skips forward or jumps directly to a specific byte position (equivalent to seek()).
     * Invalidates current chunk pointers and clears prefetch queues to align with the new position.
     *
     * @param n The destination absolute byte offset position.
     * @return The resulting absolute position offset.
     */
    override def skip(n: Long): Long = {
      logger.info(s"skip to ${n}")

      inputStreamPosition = n

      // Jumping to a new position: drop the cache and invalidate the loaded chunk so the
      // next read reloads. (Used by Hadoop seek and by PositionedReadable mark/seek/reset.)
      inputStreamCacheMap.synchronized {
        inputStreamCacheMap.clear()
      }
      currentChunkId = -1L
      currentChunkBuffer = null

      // Drop our handle to any in-flight prefetch (it targets the old position); let it
      // finish harmlessly. Stale chunks it caches are evicted as we advance.
      prefetchFuture = null
      prefetchStart = -1L
      prefetchEnd = -1L

      inputStreamPosition
    }

    /**
     * High-performance, bulk chunk retrieval method. Expands the requested chunk range to align with 
     * readChunksTogether prefetching size, allocates appropriate buffers, decrypts, and maps them to the local cache.
     *
     * @param startChunkId The start chunk index.
     * @param endChunkId   The end chunk index.
     * @return A ByteBuffer holding the loaded decrypted plaintext data.
     */
    private def retrieveChunksWithExpandedRange(startChunkId: Long, endChunkId: Long): ByteBuffer = {
      // Check if startChunkId is within bounds
      if (startChunkId >= totalChunksNumber) {
        logger.warn(s"retrieveChunksWithExpandedRange: startChunkId ${startChunkId} >= totalChunksNumber ${totalChunksNumber}")
        return ByteBuffer.allocate(0)
      }
      
      // Increase range if it's smaller than readChunksTogether, but don't go beyond totalChunksNumber
      val requestedRange = endChunkId - startChunkId + 1
      val adjustedEndChunkId = if (requestedRange < readChunksTogether) {
        val newEndChunkId = startChunkId + readChunksTogether - 1
        Math.max(startChunkId, Math.min(newEndChunkId, totalChunksNumber - 1))
      } else {
        endChunkId
      }

      // Allocate buffer for the adjusted range
      val buffer = ByteBuffer.allocate(Constants.PLAIN_CHUNK_MAX_SIZE * (adjustedEndChunkId - startChunkId + 1).toInt)

      if (logger.isDebugEnabled) logger.debug(s"retrieveChunksWithExpandedRange START read from ${startChunkId} to ${adjustedEndChunkId} due to initially requested chunks missing")

      val cloudFileOperationStatus =
        new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.DOWNLOADING)

      // read the entire buffer using cache
      tryServeSingleChunkFromCacheIfSizeMatches(
        storageObjectMetadata,
        ByteBufferChannelHandler(buffer, inputStreamCacheMap),
        startChunkId,
        adjustedEndChunkId,
        dataSizeAttribute,
        waitUntilDone = true)(cloudFile, cloudFileOperationStatus)
        .getOrElse(
          retrieveCloudFileContent(storageObjectMetadata,
            ByteBufferChannelHandler(buffer, inputStreamCacheMap),
            startChunkId,
            adjustedEndChunkId,
            true,
            dataSizeAttribute)(cloudFile, cloudFileOperationStatus))

      if (logger.isDebugEnabled) logger.debug(s"retrieveChunksWithExpandedRange END read from ${startChunkId} to ${adjustedEndChunkId}")

      buffer.flip
      buffer
    }
  }
 
  /**
   * We use this static map as work around 
   * due to strange behavior of the AltaStataChunkedOutputStream object that lost the reference to ByteBuffer
   */
  val filesBuffersMap = Collections.synchronizedMap(new HashMap[String, ByteBuffer])

  /**
   * Safe, high-performance chunked OutputStream for writing secure encrypted AltaStata cloud files.
   * 
   * It transparently splits output data into standard size chunks, encrypts each chunk on the fly
   * using AES-GCM, signs metadata envelopes, and uploads them to the configured cloud storage.
   * Includes support for append operations by dynamically reading, decrypting, appending, and rewriting the last chunk.
   *
   * @param filePath The absolute cloud destination path.
   * @param timestamp The creation timestamp for this file version.
   * @param isAppend True to append to an existing file instead of overwriting.
   */
  class AltaStataChunkedOutputStream(filePath: String, timestamp: Long = System.currentTimeMillis, isAppend: Boolean = false)(implicit account: Account) extends OutputStream {

    private val logger = LoggerFactory.getLogger(getClass)

    val foundList = account.fileSystemModel.listCloudFiles(filePath, true).asScala.toList
    
    val cloudFile = 
      if (foundList.isEmpty) {
        // create it
        val cf = account.getFileSystemHandler().createCloudFileVersion(filePath, false, timestamp)
        account.fileSystemModel.storeByteBufferToCloudFile(ByteBuffer.allocate(0), cf)

        cf
      }
      else foundList.last
      
    val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
    val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)

    val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get

    logger.info(s"new AltaStataChunkedOutputStream: ${storageObjectMetadata.getObjectPath}")

    checkIfMetadataIsSignedByMyself(storageObjectMetadata)
    
    val dataSizeTry = retrieveCloudFileDataAttribute(storageObjectMetadata, "size")
    if (dataSizeTry.isFailure) {
      throw new FileNotFoundException(s"AltaStataChunkedOutputStream ${storageObjectMetadata.getObjectPath}")
    }
    
    val dataSizeAttribute = dataSizeTry.get.toLong

    // TODO(security): Verify whether concurrent writers to one object are supported; if so,
    // enforce a cross-writer lock or metadata generation/CAS for this object path.
    // Multiple AltaStataChunkedOutputStream instances currently share filesBuffersMap but retain
    // independent currentFileSize and upload queues; synchronizing a reader does not serialize writers.
    // A durable lock must be recoverable after a crashed writer.
    //      if (dataWriteLockAttribute.writeLock == true) {
    //        throw new IOException(s"${filePath} is locked by another writer")
    //      }

    filesBuffersMap.put(storageCloudObjectPathIncludingVersion, ByteBuffer.allocate(Constants.PLAIN_CHUNK_MAX_SIZE))

    var currentFileSize = dataSizeAttribute

    logger.info(s"currentFileSize: " + dataSizeAttribute + " totalChunks: " + totalChunks(dataSizeAttribute))

    private def currentChunkId() = totalChunks(currentFileSize) - 1

    // load the last chunk to currentPlaintextBuffer and we will append more bytes to it
    if (dataSizeAttribute % Constants.PLAIN_CHUNK_MAX_SIZE > 0) {
      val lastChunk = readChunk(storageObjectMetadata, currentChunkId()).array

      logger.info(s"currentChunkId(): " + currentChunkId() + " lastChunk.length: " + lastChunk.length)
      
      filesBuffersMap.get(storageCloudObjectPathIncludingVersion).put(lastChunk)
    }

    // Bound the number of in-flight chunk uploads so a fast writer with a slow cloud
    // backend cannot accumulate unbounded chunk arrays in memory (each up to 8 MiB).
    private val MAX_PENDING_CHUNKS = math.max(1, OpsExecutors.MAX_PENDING_CHUNKS_PER_STREAM)
    // FIFO so we can await the oldest first when applying backpressure.
    private val pendingChunkFutures = mutable.Queue[Future[Unit]]()
    // First failure from any in-flight chunk upload, recorded by the upload's onComplete
    // callback. Lets checkPendingFailures be O(1) instead of scanning the whole queue.
    private val firstUploadError = new java.util.concurrent.atomic.AtomicReference[Throwable](null)

    /**
     * Fail fast: if any in-flight chunk upload has already failed, surface it on the next
     * write/flush/close instead of silently buffering more data.
     */
    private def checkPendingFailures(): Unit = {
      val e = firstUploadError.get()
      if (e != null) throw new IOException(s"AltaStataChunkedOutputStream chunk upload failed for ${filePath}", e)
    }

    def sync() = {
      storePlainTextBuffer(clearBuffer = false)

      // Wait for all pending chunks to complete, so we guarantee durability
      if (pendingChunkFutures.nonEmpty) {
        logger.info(s"sync(): Waiting for ${pendingChunkFutures.size} chunk storage operations to complete...")
        val allFutures = Future.sequence(pendingChunkFutures.toList)
        try Await.result(allFutures, Duration.Inf)
        catch {
          case e: Throwable =>
            throw new IOException(s"AltaStataChunkedOutputStream chunk upload failed for ${filePath}", e)
        }
        logger.info("sync(): All chunk storage operations completed")
        pendingChunkFutures.clear()
      }
    }

    private def storePlainTextBuffer(clearBuffer: Boolean = true)(implicit account: Account) = {
      val buffer = filesBuffersMap.get(storageCloudObjectPathIncludingVersion)

      if (buffer.position() > 0) {
        if (logger.isDebugEnabled) logger.debug(s"\tstorePlainTextBuffer: store $filePath (${currentChunkId()})")

        val currentPosition = buffer.position()
        buffer.flip
        
        val array = new Array[Byte](buffer.limit())
        buffer.get(array)
        
        if (clearBuffer) {
          buffer.clear
        } else {
          buffer.position(currentPosition)
          buffer.limit(buffer.capacity())
        }

        if (logger.isDebugEnabled) logger.debug(s"\tstorePlainTextBuffer array.length: " + array.length)

        // update fileSize
        storeCloudFileDataAttribute(storageObjectMetadata, currentFileSize.toString, "size")

        // Backpressure: drop completed successful uploads, surface failures, and block if
        // we are already at the in-flight cap (await the oldest before submitting a new one).
        checkPendingFailures()
        while (pendingChunkFutures.nonEmpty && pendingChunkFutures.front.value.exists(_.isSuccess)) {
          pendingChunkFutures.dequeue()
        }
        if (pendingChunkFutures.size >= MAX_PENDING_CHUNKS) {
          val oldest = pendingChunkFutures.dequeue()
          try Await.result(oldest, Duration.Inf)
          catch {
            case e: Throwable =>
              throw new IOException(s"AltaStataChunkedOutputStream chunk upload failed for ${filePath}", e)
          }
        }

        val chunkId = currentChunkId()
        val future = Future {
          storeChunk(array, storageObjectMetadata, chunkId)(OpsExecutors.ecChunkStoreOps, account)
        } (OpsExecutors.ecChunkStoreOps)

        // Record the first failure so checkPendingFailures stays O(1) (no queue scan).
        future.onComplete {
          case Failure(e) => firstUploadError.compareAndSet(null, e)
          case _ =>
        }(OpsExecutors.ecChunkStoreOps)

        pendingChunkFutures.enqueue(future)
      }
    }

    override def write(b: Int): Unit = {
      checkPendingFailures()

      val buffer = filesBuffersMap.get(storageCloudObjectPathIncludingVersion)
      buffer.put(b.toByte)
      currentFileSize += 1

      if (!buffer.hasRemaining()) {
        storePlainTextBuffer()
      }
    }

    override def write(bytes: Array[Byte]): Unit = {
      write(bytes, 0, bytes.length)
    }

    override def write(bytes: Array[Byte], off: Int, len: Int): Unit = {
      checkPendingFailures()

      // Bulk copy with chunk-boundary handling: one map lookup per call (not per byte),
      // and a handful of bulk ByteBuffer.put calls instead of N single-byte puts.
      var p = off
      val end = off + len
      while (p < end) {
        val buffer = filesBuffersMap.get(storageCloudObjectPathIncludingVersion)
        val n = math.min(buffer.remaining(), end - p)
        buffer.put(bytes, p, n)
        p += n
        currentFileSize += n

        if (!buffer.hasRemaining()) {
          storePlainTextBuffer()
        }
      }
    }

    override def flush() = {
      // Wait for all pending chunks to complete
      if (pendingChunkFutures.nonEmpty) {
        logger.info(s"flush(): Waiting for ${pendingChunkFutures.size} chunk storage operations to complete...")
        val allFutures = Future.sequence(pendingChunkFutures.toList)
        try Await.result(allFutures, Duration.Inf)
        catch {
          case e: Throwable =>
            throw new IOException(s"AltaStataChunkedOutputStream chunk upload failed for ${filePath}", e)
        }
        logger.info("flush(): All chunk storage operations completed")
        pendingChunkFutures.clear() // Clear the list after completion
      }
    }

    override def close() = {
      storePlainTextBuffer() // Add the final chunk future

      // Wait for all pending chunks to complete
      if (pendingChunkFutures.nonEmpty) {
        logger.info(s"close(): Waiting for ${pendingChunkFutures.size} chunk storage operations to complete...")
        val allFutures = Future.sequence(pendingChunkFutures.toList)
        try Await.result(allFutures, Duration.Inf)
        catch {
          case e: Throwable =>
            throw new IOException(s"AltaStataChunkedOutputStream chunk upload failed for ${filePath}", e)
        }
        logger.info("close(): All chunk storage operations completed")
        pendingChunkFutures.clear()
      }
      
      filesBuffersMap.remove(storageCloudObjectPathIncludingVersion)

      // TODO: unlock the write access
    }
  }
}
