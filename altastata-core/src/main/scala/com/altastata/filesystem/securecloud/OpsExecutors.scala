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

import com.altastata.utils.Constants
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import org.apache.commons.lang3.concurrent.BasicThreadFactory
import org.slf4j.LoggerFactory

object OpsExecutors {

  private val logger = LoggerFactory.getLogger(getClass)

  val FILE_PROCESSING_THREAD_POOL_SIZE = 100
  val FAST_FILE_PROCESSING_THREAD_POOL_SIZE = 200

  val BACKGROUND_FILE_PROCESSING_THREAD_POOL_SIZE = 128
  // Delete path in SecureCloudFileSystemModel is blocking (network + Await in upper layers),
  // so an oversized pool amplifies contention on SDK HTTP connections and cloud rate limits.
  val FILE_DELETING_THREAD_POOL_SIZE = 64

  val BACKGROUND_EVENTS_PROCESSING_THREAD_POOL_SIZE = 64

  val SECURE_CLOUD_OPS_THREAD_POOL_SIZE = 96

  val TOTAL_CHUNKS_FOR_ALL_UPLOADS_IN_PROCESS = 64
  val TOTAL_CHUNKS_FOR_ALL_DOWNLOADS_IN_PROCESS = 64
  // Per-stream write-ahead cap for AltaStataChunkedOutputStream (not the global upload pool).
  // 8 × PLAIN_CHUNK_MAX_SIZE ≈ 64 MiB per open stream — enough to pipeline while the writer
  // fills the next chunk; global chunk-store concurrency stays at 100 for bulk uploads.
  val MAX_PENDING_CHUNKS_PER_STREAM = 8
  // Delete has much smaller per-task footprint than upload/download and is mostly
  // metadata/network-bound, so we can allow higher global chunk-delete concurrency.
  // Still bounded to avoid overwhelming provider rate limits or SDK HTTP pools.
  val TOTAL_CHUNKS_FOR_ALL_DELETES_IN_PROCESS = 160

  // Two SDK HTTP pools (see THREAD_POOL_CONGESTION_DESIGN.md): 8 MiB chunk PUTs must
  // not occupy the connections that catalog / dataattributes / changes need.
  // Chunks: cover upload/download (64) and bulk chunk-delete (160). Idle sockets are
  // cheap; in-flight 8 MiB bodies are still capped by the dispatcher at 64.
  val CLOUD_HTTP_MAX_CONNECTIONS_CHUNKS = TOTAL_CHUNKS_FOR_ALL_DELETES_IN_PROCESS
  // System: small objects, high fan-out (ecCloudObject* 128 + inner/fast-file).
  val CLOUD_HTTP_MAX_CONNECTIONS_SYSTEM = 500

  // Cloud object pools are unbounded (cached): bulk chunk concurrency is capped by the SRPT
  // dispatchers (100 workers each); streaming still uses ecChunkRetrieveOps / ecChunkStoreOps.
  // Metadata/catalog and other tiny objects must not be throttled by a fixed cloud-object cap.

  // Per-file upload inner phase (size/readers/content/share futures). Must NOT use ecFastFileOps
  // (deadlock with bulk orchestration) or ecCloudObjectUploadOps cached pool (OOM: 1100×4 threads).
  val FILE_STORE_INNER_THREAD_POOL_SIZE = 128

  val CLOUD_OBJECT_THREAD_POOL_SIZE = 128

  val secureCloudOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("securecloudops-thread-%d")
      .daemon(true)
      .priority(Thread.MAX_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(SECURE_CLOUD_OPS_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"secureCloudOps reportFailure: $t")
    }
  }

  val ecFastFileOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("fastfileops-thread-%d")
      .daemon(true)
      .priority(Thread.MAX_PRIORITY - 1)
      .build();

    val threadPool = Executors.newFixedThreadPool(FAST_FILE_PROCESSING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecFastFileOps reportFailure: $t")
    }
  }

  // File-level orchestration EC (implicit for storeChunk/readChunk). Bulk chunk scheduling is
  // the SRPT dispatcher's job; this pool no longer limits in-flight chunk buffers.
  val ecFileDownloadOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("fileDownloadOps-thread-%d")
      .daemon(true)
      .priority(Thread.NORM_PRIORITY + 1)
      .build();

    val threadPool = Executors.newFixedThreadPool(FILE_PROCESSING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecFileDownloadOps reportFailure: $t")
    }
  }

  val ecEventsOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("eventsops-thread-%d")
      .daemon(true)
      .priority(Thread.NORM_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(BACKGROUND_EVENTS_PROCESSING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecEventsOps reportFailure: $t")
    }
  }


  // File-level orchestration EC (implicit for storeChunk). Bulk chunk scheduling is the SRPT
  // dispatcher's job; this pool no longer limits in-flight chunk buffers.
  val ecFileUploadOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("fileUploadOps-thread-%d")
      .daemon(true)
      .priority(Thread.NORM_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(FILE_PROCESSING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecFileUploadOps reportFailure: $t")
    }
  }

  // Streaming download chunk pool (bulk download uses chunkRetrieveDispatcher instead).
  val ecChunkRetrieveOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("chunk-retrieve-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY + 4) // need to process data faster than to upload it
      .build();

    val threadPool = Executors.newFixedThreadPool(TOTAL_CHUNKS_FOR_ALL_DOWNLOADS_IN_PROCESS, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecChunkRetrieveOps reportFailure: $t")
    }
  }

  // Streaming upload chunk pool (bulk upload uses chunkStoreDispatcher instead).
  val ecChunkStoreOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("chunk-store-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY + 3)
      .build();

    val threadPool = Executors.newFixedThreadPool(TOTAL_CHUNKS_FOR_ALL_UPLOADS_IN_PROCESS, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecChunkStoreOps reportFailure: $t")
    }
  }

  // SRPT priority dispatchers (see TRANSFER_SCHEDULING_DESIGN.md). These replace the flat
  // ecChunkStoreOps / ecChunkRetrieveOps pools as the chunk scheduler: chunks are ordered
  // by their file's remaining bytes so small files (incl. from concurrent requests) overtake
  // large ones, while still bounding in-flight chunks (= worker count) to bound memory.
  // Thread priorities mirror the legacy chunk pools (retrieve slightly above store:
  // "process data faster than upload it").
  val chunkStoreDispatcher = new ChunkPriorityDispatcher(
    TOTAL_CHUNKS_FOR_ALL_UPLOADS_IN_PROCESS, "chunk-store", Thread.MIN_PRIORITY + 3)
  val chunkRetrieveDispatcher = new ChunkPriorityDispatcher(
    TOTAL_CHUNKS_FOR_ALL_DOWNLOADS_IN_PROCESS, "chunk-retrieve", Thread.MIN_PRIORITY + 4)

  val ecBackgroundOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("backgroundOps-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY + 2) // delete will be in background
      .build();

    val threadPool = Executors.newFixedThreadPool(BACKGROUND_FILE_PROCESSING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecBackgroundOps reportFailure: $t")
    }
  }

  val ecFileDeleteOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("fileDeleteOps-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY + 1)
      .build();

    val threadPool = Executors.newFixedThreadPool(FILE_DELETING_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecFileDeleteOps reportFailure: $t")
    }
  }

  val ecChunkDeleteOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("chunk-delete-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(TOTAL_CHUNKS_FOR_ALL_DELETES_IN_PROCESS, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecChunkDeleteOps reportFailure: $t")
    }
  }

  // Per-file inner upload phase (size/readers/content/share). Fixed pool: tasks queue instead of
  // spawning unbounded threads per cloud PUT/GET.
  val ecFileStoreInnerOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("file-store-inner-thread-%d")
      .daemon(true)
      .priority(Thread.NORM_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(FILE_STORE_INNER_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecFileStoreInnerOps reportFailure: $t")
    }
  }

  // Cloud object upload operations (MIN_PRIORITY for background object operations)
  val ecCloudObjectUploadOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("cloud-object-upload-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(CLOUD_OBJECT_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecCloudObjectUploadOps reportFailure: $t")
    }
  }

  // Cloud object download operations (MIN_PRIORITY for background object operations)
  val ecCloudObjectDownloadOps = new ExecutionContext {
    val namedThreadFactory = new BasicThreadFactory.Builder()
      .namingPattern("cloud-object-download-thread-%d")
      .daemon(true)
      .priority(Thread.MIN_PRIORITY)
      .build();

    val threadPool = Executors.newFixedThreadPool(CLOUD_OBJECT_THREAD_POOL_SIZE, namedThreadFactory)

    /**
     * Executes the given runnable.
     *
     * @param runnable the runnable to execute
     */
    def execute(runnable: Runnable): Unit = {
      threadPool.submit(runnable)
    }

    /**
     * Reports a failure during execution.
     *
     * @param t the throwable cause
     */
    def reportFailure(t: Throwable): Unit = {
      logger.error(s"ecCloudObjectDownloadOps reportFailure: $t")
    }
  }
}
