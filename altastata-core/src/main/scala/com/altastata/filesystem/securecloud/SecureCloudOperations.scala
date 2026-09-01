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

import scala.concurrent.{Await, Future}
import scala.util.control.Exception.catching
import org.slf4j.LoggerFactory

import java.util.concurrent.{ExecutionException, Semaphore}
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.collection.mutable.{ArrayBuffer, Buffer}
import java.io.Closeable
import com.altastata.filesystem.utils.LocalFileAsynchronousChannelHandler

import scala.collection._
import java.nio.ByteBuffer
import java.util.UUID
import com.altastata.crypto.{AsymmetricCryptoHandler, AsymmetricKeysGenerator, SecurityUtils}
import com.altastata.utils.Constants._
import com.altastata.filesystem.StoreFileAndMetadataException
import com.altastata.filesystem.RetrieveFileException
import com.altastata.filesystem.DeleteFileException

import scala.util.Success
import scala.util.Failure
import com.altastata.filesystem.{AuthorityAttributes, UserMetadata}
import com.altastata.utils.Account
import com.altastata.filesystem.common.CloudFile
import com.altastata.filesystem.common.FileSystemHandler

import java.io.File
import scala.util.Try
import com.altastata.utils.Compress
import org.apache.commons.lang3.concurrent.BasicThreadFactory

import java.security.{Key, PublicKey}
import com.altastata.utils.DataChannel
import com.altastata.filesystem.OperationCanceledCloudObjectException
import com.altastata.api.CloudFileOperationStatus
import com.altastata.api.AltaStataFileSystem
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.crypto.X509.extractPEMsFromCertificate
import com.altastata.crypto.CertSubject

import java.util.Base64
import java.io.FileNotFoundException
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.Breaks._

/**
 * Represents the comprehensive metadata record for a securely stored cloud object.
 * 
 * This structure is typically serialized to JSON and stored alongside the encrypted 
 * data chunks. It contains all the necessary cryptographic and locational information 
 * required to retrieve, decrypt, and verify the integrity of the file.
 *
 * @param metadataVersion The schema version of this metadata structure.
 * @param metadataSignature Optional cryptographic signature verifying the integrity of this metadata.
 * @param fileAttrs Attributes defining the file's logical path, version, and tags.
 * @param encryptionAttrs Cryptographic details (algorithms, encrypted keys, IVs) used to protect the data.
 * @param storageAttrs Locational and access management details for the underlying storage layer.
 * @param authorityAttrs Information about the authority that signed this metadata.
 */
case class StorageObjectMetadata(var metadataVersion: String, var metadataSignature: Option[String] = None,
                                 var fileAttrs: FileAttrs = null,
                                 var encryptionAttrs: EncryptionAttrs = null,
                                 var storageAttrs: StorageAttrs = null,
                                 var authorityAttrs: Option[AuthorityAttributes] = None) {

  /**
   * Constructs the unique canonical storage object path for this version.
   * Format: filePath + "AS_MARK_" + tag + "_" + version
   *
   * @return the unique canonical object path string
   */
  def getObjectPath: String = {
    fileAttrs.filePath + AltaStataFileSystem.FILE_MARK_SIGN + fileAttrs.tag + "_" + fileAttrs.version
  }
}

/**
 * Identifies the logical file in the file system hierarchy.
 */
case class FileAttrs(var filePath: String, var tag: String, var createdTime:Long, var version: String)

/**
 * Contains the data encryption key (DEK) and algorithm details inside envelope-encrypted metadata.
 * `encryptedAESIV` is retained only for legacy v1 formats; v2 stores a fresh nonce with each record.
 */
case class EncryptionAttrs(var encryptionAlgorithm: String, var encryptedAESKey: String, var encryptedAESIV: String)

/**
 * Specifies where the actual encrypted chunks and associated access control data reside in the cloud.
 */
case class StorageAttrs(var dataOwner: String, var dataLocator: String, var isCompressed: Boolean,
                        var accessManager: String, var dataAttributesLocator: String)

/** Per-user attributes sent from admin to custodian (e.g. for Azure: SAS to access that user's catalog and dataattributes). */
case class UserAttributesForCustodian(
  userName: String,
  catalogSas: Option[String] = None,
  dataattributesSas: Option[String] = None
)

// DataAttribute classes removed - now using simple String and Long types

/**
 * Provides core cryptographic and data transfer operations for the secure cloud file system.
 *
 * This trait handles the heavy lifting of:
 * - Chunking data streams into manageable, encrypted blocks.
 * - Dispatching chunks to cloud storage asynchronously using priority queues.
 * - Retrieving, decrypting, and assembling chunks back into local plaintext channels.
 * - Managing the lifecycle of [[StorageObjectMetadata]] (EncryptionAttrs, StorageAttrs).
 *
 * It acts as the critical bridge between high-level file system models and the underlying
 * asynchronous cloud I/O layer.
 */
trait SecureCloudOperations extends SecurityUtils with Compress {

  private def pqcCertParams(account: Account): (java.security.PublicKey, Boolean) =
    (account.getCertTrustPublicKey, account.enforceAltaStataIssuerCn)

  private val logger = LoggerFactory.getLogger(getClass)

  val CHUNK_FILE_NAME_FORMAT = "data.%021d/%04d.chunk"
  val CHANGE_TIME_FORMAT = "%017d"

  /** Copy only remaining bytes so a reused 8 MiB ByteBuffer cannot encrypt leftover tail. */
  private def heapBytesExact(buf: ByteBuffer): Array[Byte] = {
    val n = buf.remaining()
    if (buf.hasArray && buf.arrayOffset() == 0 && buf.position() == 0 && buf.array().length == n) {
      buf.array
    } else {
      val a = new Array[Byte](n)
      buf.duplicate().get(a)
      a
    }
  }

  // ---- Change-object path parsing / batch planning (pure, no cloud access) ----
  // A change object key has the shape: QUEUE/<time>/<EVENT>/<params>/<file-path...>

  /** Event timestamp encoded in a change object path. */
  private[securecloud] def changeEventTime(changeObjectPath: String): Long =
    changeObjectPath.split("/")(1).toLong

  /** Event type encoded in a change object path. */
  private[securecloud] def changeEventType(changeObjectPath: String): String =
    changeObjectPath.split("/")(2)

  /**
   * Whether a change-object key has the minimum well-formed shape required by the event processor.
   * Invalid keys must be isolated before planning; otherwise a malformed object could prevent an
   * entire queue batch from being processed.
   */
  private[securecloud] def isValidChangeObjectPath(changeObjectPath: String): Boolean = Try {
    val parts = changeObjectPath.split("/", -1)
    require(parts.length >= 5, "change path must contain queue, time, event, parameters, and target")
    parts(1).toLong
    require(parts(2).nonEmpty, "change event is empty")

    val parameters = parts(3).split("&", -1).map(_.split("=", 2))
    require(parameters.forall(_.length == 2), "change parameter is malformed")
    require(parameters.exists(p => p(0) == "from" && p(1).nonEmpty), "change sender is missing")
  }.isSuccess

  /**
   * Target object of a (non ADD_USERDATA) change: the file path including version.
   * Used to partition changes so that all events for the same object are processed
   * sequentially in timestamp order, while different objects run in parallel.
   */
  private[securecloud] def changeTargetPath(changeObjectPath: String): String = {
    val parts = changeObjectPath.split("/")
    parts.slice(4, parts.length).mkString("/")
  }

  /**
   * Plans how a batch of change object paths should be executed.
   *
   * Returns (userdataChanges, objectGroups):
   *  - userdataChanges: all ADD_USERDATA changes (provisioning), ordered by time.
   *    They gate later ADD_READER events (a reader's userdata must already exist),
   *    so callers run them first (in parallel) with a barrier before phase 2.
   *  - objectGroups: the remaining changes grouped by target object; within each
   *    group ordered by event timestamp so operations on the same object apply in
   *    order (e.g. ADD_READER before REMOVE_READER), while different groups may run
   *    in parallel. Groups are ordered by target path for deterministic behavior.
   */
  def planChanges(changes: Seq[String]): (Seq[String], Seq[Seq[String]]) = {
    val (userdataChanges, fileChanges) =
      changes.partition(changeEventType(_) == EVENT_ADD_USERDATA)

    val groups = fileChanges
      .groupBy(changeTargetPath)
      .toList
      .sortBy(_._1)
      .map(_._2.sortBy(changeEventTime))

    (userdataChanges.sortBy(changeEventTime), groups)
  }

  // AES-GCM: store iv||ciphertext with a fresh IV each time (do not reuse metadata IV).
  protected def encryptGcmObject(plaintext: Array[Byte], key: Array[Byte]): Array[Byte] = {
    val encrypted = encryptAES_GCM(plaintext, key).get
    encrypted.iv ++ encrypted.ciphertext
  }

  protected def decryptGcmObject(record: Array[Byte], key: Array[Byte]): Array[Byte] = {
    val minimumLength = AES_GCM_IV_SIZE + GCM_TAG_LENGTH_IN_BITS / 8
    if (record == null || record.length < minimumLength) {
      throw new SecurityException(s"Invalid AES-GCM object: expected at least $minimumLength bytes")
    }
    decryptAES_GCM(record.drop(AES_GCM_IV_SIZE), key, record.take(AES_GCM_IV_SIZE)).get
  }

  // Same string for sign and verify (ADD_USERDATA / metadataSignature).
  protected def userdataAuthorityString(signer: String, m: UserMetadata): String =
    signer + "/" + m.userName + "/" +
      m.publicKey.orElse(m.publicKeyCert).orElse(m.publicKyberKeyPEM)
        .orElse(m.publicDilithiumKeyPEM).orElse(m.publicPQCKeyCertPEM).orElse(m.hsmKeyId).getOrElse("")

  protected def metadataAuthorityString(signer: String, m: StorageObjectMetadata): String =
    signer + "/" +
      m.storageAttrs.dataLocator + "/" +
      m.storageAttrs.dataOwner + "/" +
      m.storageAttrs.dataAttributesLocator + "/" +
      m.storageAttrs.accessManager + "/" +
      m.storageAttrs.isCompressed + "/" +
      m.encryptionAttrs.encryptionAlgorithm + "/" +
      m.encryptionAttrs.encryptedAESKey + "/" +
      Option(m.encryptionAttrs.encryptedAESIV).getOrElse("") + "/" +
      m.fileAttrs.filePath + "/" +
      m.fileAttrs.version + "/" +
      m.metadataVersion

  /**
   * Stores the content of a local data channel to a cloud object in encrypted chunks.
   *
   * This method determines the total number of required chunks based on the file size,
   * wraps them into a prioritized sequence, and dispatches them asynchronously to the 
   * cloud storage backend via the `chunkStoreDispatcher`. It also updates the progress 
   * tracker for the `CloudFile` as chunks complete.
   *
   * @param localSource The local plaintext data channel (e.g., file or stream) to read from.
   * @param storageObjectMetadata Metadata describing how the object should be encrypted and stored.
   * @param cloudFile The domain object representing the file in the file system.
   * @param cloudFileOperationStatus Tracks the status and allows for operation cancellation.
   * @param account The implicit user account context.
   * @return An iterable containing the byte sizes of the uploaded chunks.
   * 
   * @todo Implement upload as a pure stream (similar to the download implementation) to avoid
   *       relying heavily on predefined local file sizes.
   */
  def storeCloudFileContent(localSource: DataChannel, storageObjectMetadata: StorageObjectMetadata)(cloudFile: CloudFile, cloudFileOperationStatus: CloudFileOperationStatus)(implicit account: Account): Iterable[Long] = {
    // Kept in lexical scope so storeChunk resolves the same implicit ExecutionContext as before
    // (it forwards ec to storeObjectToCloud). Chunk scheduling itself is the dispatcher's job.
    implicit val ec = OpsExecutors.ecFileUploadOps

    logger.trace(s"\tstoreFileAndMetadata START: ${localSource}")

    val fileSize = localSource.size()
    val totalChunksNumber = totalChunks(fileSize, PLAIN_CHUNK_MAX_SIZE)

    // Chunk list with exact per-chunk byte sizes — the size feeds the SRPT priority key.
    val chunkSeq = (0L until totalChunksNumber).map { id =>
      ChunkPriorityDispatcher.Chunk(id, chunkSize(fileSize, id, PLAIN_CHUNK_MAX_SIZE).toLong)
    }

    /**
     * Processes a single chunk for upload.
     *
     * @param c The chunk to process
     */
    def processChunk(c: ChunkPriorityDispatcher.Chunk): Unit = {
      val chunkId = c.id
      if (cloudFileOperationStatus.checkIfOperationCanceling()) {
        logger.warn(s"storeFile ${cloudFile.getPath} was canceled while processing ${chunkId} out of ${totalChunksNumber}")

        throw new OperationCanceledCloudObjectException("upload", cloudFile.getPath, cloudFile.getProgressValue())
      } else {
        val currentChunkSize = c.sizeBytes.toInt

        logger.trace(s"\tstoreProcessor (${chunkId}) from ${localSource} size: ${currentChunkSize}")

        val plaintextBuffer = localSource.read(chunkId * PLAIN_CHUNK_MAX_SIZE, currentChunkSize).get

        // Reused 8 MiB buffers are larger than a partial last chunk; never pass .array().
        storeChunk(heapBytesExact(plaintextBuffer), storageObjectMetadata, chunkId)

        val part = if (totalChunksNumber < 50) totalChunksNumber / 10
        else if (totalChunksNumber < 100) totalChunksNumber / 25
        else totalChunksNumber / 100

        if (part != 0 && chunkId % part == 0) logger.debug(s"store ${localSource} -- ${chunkId * 100 / totalChunksNumber}%")

        val percentage = chunkId.toDouble / totalChunksNumber.toDouble;
        if (cloudFile.getProgressValue() < percentage) {
          cloudFile.setProgressValue(percentage)
          cloudFileOperationStatus.setProgressValue(percentage)
        }
      }
    }

    val futuresComposition = OpsExecutors.chunkStoreDispatcher.submit(chunkSeq)(processChunk)

    try {
      Await.result(futuresComposition, Duration.Inf)
      logger.trace(s"\tstoreFileAndMetadata END ${localSource} inputFileSize: ${fileSize}")
      localSource.close()
      chunkSeq.map(_.sizeBytes)
    } catch {
      case t: Throwable =>
        logger.error(s"storeFileAndMetadata with error for ${localSource}")
        localSource.close()
        throw t
    }
  }

  /**
   * Chunk 0 plaintext from [[account.caches]] only when bundled cached `size` decrypts to `dataSizeAttribute`
   * (same logical length the caller already obtained from cloud). Otherwise [[scala.None]] — no throws from bad decrypt/size parse.
   */
  private def cachedChunkZeroPlainIfBundleMatchesStorageSize(
      m: StorageObjectMetadata,
      localDest: DataChannel,
      dataSizeAttribute: Long
  )(implicit account: Account): Option[ByteBuffer] = {
    val last = totalChunks(dataSizeAttribute, PLAIN_CHUNK_MAX_SIZE) - 1
    if (last != 0L) {
      None
    } else {
      val path = m.getObjectPath
      val encSz = account.caches.getAttr(path, "size")
      val encCh = account.caches.getChunk(path)

      if (encSz == null || encCh == null) {
        None
      } else {
        val encryptedAESKey = Base64.getDecoder().decode(m.encryptionAttrs.encryptedAESKey)

        val cachedLenMaybe = Try {
          val sizePlain = decryptGcmObject(encSz, encryptedAESKey)
          new String(sizePlain, "UTF-8").toLong
        }.toOption

        cachedLenMaybe match {
          case Some(cachedLen) if cachedLen == dataSizeAttribute =>
            val plainBuf =
              if (localDest.precached(0L)) {
                localDest.getFromCache(0L)
              } else {
                val plainOrCompressedText = decryptGcmObject(encCh, encryptedAESKey)

                val plainText = if (m.storageAttrs.isCompressed) inflate(plainOrCompressedText).get else plainOrCompressedText
                val buf = ByteBuffer.wrap(plainText)
                if (localDest.cacheExist()) {
                  localDest.putToCache(0L, buf.duplicate())
                }

                buf
              }

            Some(plainBuf)

          case _ =>
            None
        }
      }
    }
  }

  /**
   * Skip CHUNKS retrieve when ciphertext bundle matches [[cachedChunkZeroPlainIfBundleMatchesStorageSize]].
   * Only for single-chunk files (`dataSizeAttribute` implies one chunk). Chunk indices are inclusive and 0-based, so the only chunk is index `0`
   * (not `1`). Requires `startChunk == 0` and a non-negative `finishChunk`; values such as [[Long.MaxValue]] are fine because
   * [[retrieveCloudFileContent]] clamps with `min(finishChunk, lastFileChunk)` — here `lastFileChunk == 0`, so the effective range is `0..0`.
   */
  def tryServeSingleChunkFromCacheIfSizeMatches(
      storageObjectMetadata: StorageObjectMetadata,
      localDest: DataChannel,
      startChunk: Long,
      finishChunk: Long,
      dataSizeAttribute: Long,
      waitUntilDone: Boolean
  )(cloudFile: CloudFile, cloudFileOperationStatus: CloudFileOperationStatus)(implicit ec: ExecutionContext, account: Account): Option[Iterable[Long]] = {
    val lastFileChunk = totalChunks(dataSizeAttribute, PLAIN_CHUNK_MAX_SIZE) - 1
    val effectiveLastChunk = math.min(finishChunk, lastFileChunk)
    if (!waitUntilDone || lastFileChunk != 0L || startChunk != 0L || finishChunk < 0L || effectiveLastChunk != 0L) None
    else if (cloudFileOperationStatus.checkIfOperationCanceling())
      throw new OperationCanceledCloudObjectException("download", cloudFile.getPath, cloudFile.getProgressValue())
    else
      // On cache miss this stays None; on hit we finish the whole single-chunk download without CHUNKS retrieve.
      cachedChunkZeroPlainIfBundleMatchesStorageSize(storageObjectMetadata, localDest, dataSizeAttribute) map { plain =>
        // Same side effect as readChunk — keeps password-expiry bookkeeping aligned with the normal download path.
        account.updateAccountPasswordNextExpiredTime()
        // Entire plaintext is chunk 0 at logical offset 0 (single-chunk file only).
        val n = localDest.write(0L, plain).get.toLong
        // Match retrieveChunkProcessor(chunkId == startChunk): stream started and full progress for one chunk.
        cloudFileOperationStatus.setStreamStarted()
        cloudFile.setProgressValue(1.0)
        cloudFileOperationStatus.setProgressValue(1.0)
        logger.debug(s"\ttryServeSingleChunkFromCacheIfSizeMatches HIT ${storageObjectMetadata.getObjectPath}")
        // Same Iterable[Long] shape as retrieveCloudFileContent (bytes written for this chunk).
        List(n)
      }
  }

  /**
   * Retrieves a cloud file (or a specified range of its chunks) and writes it to a local data channel.
   *
   * This operation manages the complexities of downloading encrypted chunks from the cloud backend,
   * decrypting them on the fly using the provided metadata, verifying their cryptographic hashes, 
   * and assembling them sequentially or asynchronously into the local destination channel.
   *
   * It handles cancellation requests, progress tracking, and can utilize local caches to 
   * speed up repeated chunk retrievals.
   *
   * @param storageObjectMetadata Cryptographic and locational metadata needed to fetch and decrypt the file.
   * @param localDest The local data channel where plaintext bytes will be written.
   * @param startChunk The index of the first chunk to retrieve (inclusive).
   * @param finishChunk The index of the last chunk to retrieve (inclusive).
   * @param isStreaming If true, chunks are fetched sequentially to support media streaming.
   * @param dataSizeAttribute Total logical size of the data, used to bound chunk ranges.
   * @param waitUntilDone If true, blocks until all requested chunks are fully processed.
   * @param isPreview If true, optimizes retrieval for small previews.
   * @param cloudFile The domain object representing the file in the file system.
   * @param cloudFileOperationStatus Tracks the status and handles cancellation requests.
   * @param account The implicit user account context.
   * @return An iterable of byte sizes corresponding to each successfully written chunk.
   */
  def retrieveCloudFileContent(storageObjectMetadata: StorageObjectMetadata, localDest: DataChannel, startChunk: Long, finishChunk: Long, isStreaming: Boolean, dataSizeAttribute: Long, waitUntilDone: Boolean = true, isPreview: Boolean = false)(cloudFile: CloudFile, cloudFileOperationStatus: CloudFileOperationStatus)(implicit account: Account): Iterable[Long] = {
    implicit val ec = OpsExecutors.ecFileDownloadOps
    val lastFileChunk = totalChunks(dataSizeAttribute, PLAIN_CHUNK_MAX_SIZE) - 1

    if (lastFileChunk == -1) {
      throw new RetrieveFileException(s"${cloudFile.getPath()}", new ArrayIndexOutOfBoundsException("lastFileChunk == -1"))
    }

    logger.debug(s"\tretrieveFile START: ${storageObjectMetadata.storageAttrs.dataOwner}/${storageObjectMetadata.getObjectPath} from ${startChunk} to ${finishChunk}")

    val totalChunksNumber = Math.min(finishChunk, lastFileChunk) - startChunk + 1L
    // Whole file fits in one chunk → eligible for the small-file ciphertext cache in readChunk.
    val isSingleChunkFile = lastFileChunk == 0L

    // Shared per-chunk body (returns plaintext bytes written). Used directly by the SRPT
    // dispatcher (bulk path) and wrapped in a Future for the streaming path.
    /**
     * Processes a single chunk for download.
     *
     * @param chunkId The ID of the chunk to process
     * @return The number of bytes processed
     */
    def processChunk(chunkId: Long): Long = {
      if (cloudFileOperationStatus.checkIfOperationCanceling()) {
        logger.warn(s"retrieveFile ${cloudFile.getPath} was canceled while processing ${chunkId - startChunk} out of ${totalChunksNumber} starting from ${startChunk}")

        throw new OperationCanceledCloudObjectException("download", cloudFile.getPath, cloudFile.getProgressValue())
      } else {
        val chunkPlainText: ByteBuffer = if (localDest.precached(chunkId))
          localDest.getFromCache(chunkId)
        else {
          val buffer = readChunk(storageObjectMetadata, chunkId, isSingleChunkFile)
          if (localDest.cacheExist()) {
            localDest.putToCache(chunkId, buffer)
          }

          buffer
        }

        // TODO: in case file owner wanted to start sharing file before he is done with store operation
        // If the CloudFile.VersionAttributes has a very recent createTime, that means that its storage might be still in process
        // in that case we might get RetrieveFileException that should be processed by pausing and calling readChunk() again

        val numOfBytes = localDest.write((chunkId - startChunk) * PLAIN_CHUNK_MAX_SIZE, chunkPlainText).get

        logger.trace(s"\tretrieveFile ${cloudFile.getPath} chunk: (${chunkId}) to ${localDest} size: ${chunkPlainText.position()}")

        val part = if (totalChunksNumber < 50) totalChunksNumber / 10
        else if (totalChunksNumber < 100) totalChunksNumber / 25
        else totalChunksNumber / 100

        if (part != 0 && chunkId % part == 0) logger.debug(s"retrieve ${storageObjectMetadata.storageAttrs.dataOwner}/${storageObjectMetadata.fileAttrs.filePath} to ${localDest} -- ${chunkId * 100 / totalChunksNumber}%")

        if (chunkId == startChunk) {
          cloudFileOperationStatus.setStreamStarted();
        }

        val percentage = chunkId.toDouble / totalChunksNumber.toDouble;
        if (cloudFile.getProgressValue() < percentage) {
          cloudFile.setProgressValue(percentage)
          cloudFileOperationStatus.setProgressValue(percentage)
        }

        logger.debug(s"retrieveChunkProcessor ${storageObjectMetadata.getObjectPath} chunk ${chunkId}")

        numOfBytes.toLong
      }
    }

    // Streaming keeps its own grouped flow-control on the legacy chunk pool (v1: not migrated).
    /**
     * Asynchronously retrieves and processes a chunk.
     *
     * @param chunkId The ID of the chunk
     * @return A Future containing the number of bytes processed
     */
    def retrieveChunkProcessor(chunkId: Long): Future[Long] =
      Future { processChunk(chunkId) }(OpsExecutors.ecChunkRetrieveOps)

    if (isStreaming) {
      var totalSize = 0L
      var lastGroupFinished: Seq[Long] = Seq(0) // initial value

      breakable {
        for (group <- (startChunk to Math.min(finishChunk, lastFileChunk)).iterator.sliding(INPUT_STREAM_GROUP_SIZE, INPUT_STREAM_GROUP_SIZE)) {

          if (isPreview && group.contains(15L)) {
            break
          }

          val futuresComposition = Future.traverse(group)(retrieveChunkProcessor)

          futuresComposition onComplete {
            case Success(seq) => {
              logger.info(s"retrieveCloudFileContent group ${group}: ${localDest} from ${startChunk} to ${finishChunk} totalNumberOfBytesRetrieved: ${seq.reduce(_ + _)}")
              totalSize += seq.reduce(_ + _)

              lastGroupFinished = group
            }
            case Failure(t) => {
              logger.error(s"retrieveCloudFileContent group ${group}: ${localDest} from ${startChunk} to ${finishChunk} with error ${t.getMessage} for ${localDest}")
            }
          }

          // wait if waitUntilDone or for the first group that contains chunk 0's
          // if waitUntilDone == false, do not let to start new groups until the previous ones are finished
          // for example if the last group finished is (3, 4, 5), do not start (9, 10, 11)
          if (waitUntilDone || group.contains(0L) ||
            (group.min - lastGroupFinished.min >= INPUT_STREAM_GROUP_SIZE * INPUT_STREAM_GROUPS_PROCESSED_TOGETHER)) {

            Await.result(futuresComposition, Duration.Inf)
          }
        }
      }

      List[Long](totalSize)
    } else {
      // Bulk path → SRPT dispatcher. Per-chunk size = plaintext bytes = the priority key.
      val chunkSeq = (startChunk to Math.min(finishChunk, lastFileChunk)).map { id =>
        ChunkPriorityDispatcher.Chunk(id, chunkSize(dataSizeAttribute, id, PLAIN_CHUNK_MAX_SIZE).toLong)
      }
      val futuresComposition = OpsExecutors.chunkRetrieveDispatcher.submit(chunkSeq)(c => processChunk(c.id))

      if (waitUntilDone) {
        try {
          Await.result(futuresComposition, Duration.Inf)
          logger.debug(s"retrieveCloudFileContent END: ${localDest} from ${startChunk} to ${finishChunk} outputFileSize: ${dataSizeAttribute}")
          localDest.close()
          chunkSeq.map(_.sizeBytes)
        } catch {
          case t: Throwable =>
            logger.error(s"retrieveCloudFileContent with error for ${localDest} from ${startChunk} to ${finishChunk} -- ${t.getMessage}")
            localDest.closeOnError()
            throw t
        }
      } else {
        // Fire-and-forget: close the destination when the transfer eventually finishes.
        futuresComposition.onComplete {
          case Success(_) => localDest.close()
          case Failure(t) =>
            logger.error(s"retrieveCloudFileContent with error for ${localDest} from ${startChunk} to ${finishChunk} -- ${t.getMessage}")
            localDest.closeOnError()
        }
        List[Long](0) // if no need to wait until done
      }
    }
  }

  /**
   * Reads a single encrypted chunk from cloud storage, decrypts it, decompresses it if necessary,
   * and verifies its digital hash against the decrypted plaintext.
   *
   * It also utilizes a small-file ciphertext cache to speed up single-chunk file reads.
   *
   * @param storageObjectMetadata The metadata structure containing encryption keys and cloud locators.
   * @param chunkId The index of the chunk to read.
   * @param isSingleChunkFile Indicates if the file consists of exactly one chunk, enabling cache usage.
   * @param ec The execution context for asynchronous/blocking operations.
   * @param account The implicitly passed user account.
   * @return A ByteBuffer containing the verified and decrypted plaintext of the chunk.
   * @throws SecurityException if the encryption algorithm is unsupported or digital hash verification fails.
   */
  def readChunk(storageObjectMetadata: StorageObjectMetadata, chunkId: Long, isSingleChunkFile: Boolean = false)(implicit ec: ExecutionContext, account: Account): ByteBuffer = {

    account.updateAccountPasswordNextExpiredTime()

    val objectId = storageObjectMetadata.storageAttrs.dataLocator + "/" + formatChunkId(chunkId)

    // Small-file ciphertext cache: only single-chunk files (chunkId 0, totalChunks == 1) are
    // admitted — a multi-chunk file would otherwise put its 8 MiB chunk-0 in the cache with
    // no reuse value.
    val cachePath = storageObjectMetadata.getObjectPath
    val cached =
      if (isSingleChunkFile && chunkId == 0L) account.caches.getChunk(cachePath) else null

    val ciphertext =
      if (cached != null) cached
      else {
        val fetched = account.cloudObjectHandler.retrieveObjectFromCloud(account.CHUNKS_BUCKET,
                                                            storageObjectMetadata.storageAttrs.dataOwner,
                                                            objectId).get
        if (isSingleChunkFile && chunkId == 0L)
          account.caches.putChunk(cachePath, fetched)
        fetched
      }

    val plainOrCompressedText = decryptGcmObject(
      ciphertext,
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey))

    val plainText = if (storageObjectMetadata.storageAttrs.isCompressed) inflate(plainOrCompressedText).get else plainOrCompressedText
    ByteBuffer.wrap(plainText)
  }

  /**
   * Compresses (if configured), encrypts, and uploads a single chunk of plaintext data to cloud storage.
   *
   * The chunk is encrypted using AES-256-GCM with the keys specified in the object's metadata.
   *
   * @param plaintext The raw byte array of data to store.
   * @param storageObjectMetadata The metadata structure defining the encryption settings and storage locator.
   * @param chunkId The sequence index of the chunk within the file.
   * @param ec The execution context for managing the upload tasks.
   * @param account The implicitly passed user account.
   */
  def storeChunk(plaintext: Array[Byte], storageObjectMetadata: StorageObjectMetadata, chunkId: Long)(implicit ec: ExecutionContext, account: Account): Unit = {

    account.updateAccountPasswordNextExpiredTime()

    val ciphertext = encryptGcmObject(
      if (storageObjectMetadata.storageAttrs.isCompressed) deflate(plaintext).get else plaintext,
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey))

    val objectId = storageObjectMetadata.storageAttrs.dataLocator + "/" + formatChunkId(chunkId)

    account.cloudObjectHandler.storeObjectToCloud(ciphertext,
                                                  account.CHUNKS_BUCKET,
                                                  storageObjectMetadata.storageAttrs.dataOwner,
                                                  objectId).get

    account.caches.clearChunk(storageObjectMetadata.getObjectPath)
  }

  /**
   * Deletes all encrypted data chunks belonging to a specific cloud file.
   *
   * It calculates the total number of chunks based on the provided total logical file size,
   * then groups deletion requests into parallel batches to utilize network parallelism efficiently
   * without overwhelming the executor for very large files. It also purges the local ciphertext cache.
   *
   * @param storageObjectMetadata The metadata containing the data locator prefix for the chunks.
   * @param dataSizeAttribute The total logical size of the file, used to deduce the number of chunks.
   * @param account The implicitly passed user account.
   * @return An empty iterable upon successful deletion.
   */
  def deleteCloudFileContent(storageObjectMetadata: StorageObjectMetadata, dataSizeAttribute: Long)(implicit account: Account): Iterable[Unit] = {
    implicit val ec = OpsExecutors.ecChunkDeleteOps

    logger.trace(s"\tdeleteFile START: ${storageObjectMetadata.storageAttrs.dataOwner}/${storageObjectMetadata.getObjectPath}")

    val totalChunksNumber = totalChunks(dataSizeAttribute, PLAIN_CHUNK_MAX_SIZE)

    // Drop the whole bundle once: the only cached chunk is chunk 0, and once content is
    // gone the rest of the bundle (metadata, attrs) is also being torn down by callers.
    account.caches.invalidate(storageObjectMetadata.getObjectPath)

    // Delete chunks in bounded parallel batches so one file can utilize network parallelism
    // without creating unbounded futures for very large files.
    val chunkBatchSize = Math.max(1, OpsExecutors.TOTAL_CHUNKS_FOR_ALL_DELETES_IN_PROCESS)
    (0L until totalChunksNumber).grouped(chunkBatchSize).foreach { batch =>
      val batchFutures = batch.map { chunkId =>
        Future {
          account.cloudObjectHandler
            .deleteObjectFromCloud(
              account.CHUNKS_BUCKET,
              storageObjectMetadata.storageAttrs.dataOwner,
              storageObjectMetadata.storageAttrs.dataLocator + "/" + formatChunkId(chunkId))
            .get
        }
      }
      Await.result(Future.sequence(batchFutures), Duration.Inf)
    }
    Iterable.empty
  }

  /**
   * Encrypts and stores the cloud file metadata object securely in the catalog bucket.
   *
   * The metadata is first serialized to JSON, then encrypted using the current user's asymmetric key.
   * The specific encryption mechanism (RSA, PQC Kyber, or HSM/HPCS) depends on the `metadata-encryption` 
   * property of the authenticated user's account.
   *
   * @param storageObjectMetadata The fully constructed metadata object to be stored.
   * @param ec The execution context.
   * @param account The implicitly passed user account.
   * @return A `Try` containing the string representation of the stored and encrypted metadata upon success.
   */
  def storeCloudFileMetadata(storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext, account: Account): Try[String] = Try {
    val serializedMetadata = account.userProps.getProperty("metadata-encryption") match {
      case "RSA" => {
        val (myPublicKey, _) = decodeRSAPublicKeyFromCertForUser(
          account.fileSystemModel.retrieveUserdata(account.MY_USER).publicKeyCert.get.getBytes,
          account.MY_USER,
          checkEndDate = false,
          account
        )
        encryptArrayWithRSA(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), myPublicKey, RSA_OAEP)
      }
      case "PQC" => {
        val (certIssuer, enforceCn) = pqcCertParams(account)
        val extractedPEMs = extractPEMsFromCertificate(
          account.fileSystemModel.retrieveUserdata(account.MY_USER).publicPQCKeyCertPEM.get,
          certIssuer,
          CertSubject.forUser(account.ACCOUNT_CONTAINER_PREFIX, account.MY_USER),
          enforceAltaStataIssuerCn = enforceCn,
        )
        val myPublicKey = getPQCPublicKeyFromEncoded("KYBER", AsymmetricKeysGenerator.pemToByteArray(extractedPEMs._1)).get

        encryptArrayWithKyber(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), myPublicKey)
      }
      case "HSM" => encryptArrayWithHSM(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), account.fileSystemModel.retrieveUserdata(account.MY_USER).hsmKeyId.get)
    }

    account.cloudObjectHandler.storeObjectToCloud(serializedMetadata, account.CATALOG_BUCKET, account.MY_USER, encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath))

    account.caches.putMetadata(storageObjectMetadata.getObjectPath, serializedMetadata)
    logger.debug(s"Stored metadata in cache: ${storageObjectMetadata.getObjectPath}")

    storageObjectMetadata.getObjectPath
  }

  /**
   * Retrieves and decrypts the metadata for a specific cloud file object.
   *
   * It checks the local metadata cache first. If it's a cache miss, it retrieves the encrypted
   * metadata from the user's catalog bucket, populates the cache, and then decrypts the payload
   * using the appropriate cryptographic method (RSA, PQC Kyber, or HSM/HPCS) defined by the user's profile.
   *
   * A custodian user may also supply another user's ID to retrieve their metadata, given proper cloud permissions.
   *
   * @param userId The ID of the user who owns the catalog bucket.
   * @param storageFilePathIncludingVersion The complete logical path including the version tag.
   * @param ec The execution context.
   * @param account The implicitly passed user account context.
   * @return A `Try` containing the parsed `StorageObjectMetadata` upon successful retrieval and decryption.
   */
  def retrieveCloudFileMetadata(userId: String, storageFilePathIncludingVersion: String)(implicit ec: ExecutionContext, account: Account): Try[StorageObjectMetadata] = Try {
    val serializedMetadata = Option(account.caches.getMetadata(storageFilePathIncludingVersion)) match {
      case Some(cached) =>
        logger.debug(s"Retrieved metadata from cache: $storageFilePathIncludingVersion")
        cached
      case None =>
        val retrieved = account.cloudObjectHandler.retrieveObjectFromCloud(account.CATALOG_BUCKET, userId, encryptObjectPathIfNeeded(storageFilePathIncludingVersion)).get
        account.caches.putMetadata(storageFilePathIncludingVersion, retrieved)
        retrieved
    }

    account.userProps.getProperty("metadata-encryption") match {
      case "RSA" => decode[StorageObjectMetadata](new String(decryptArrayWithRSA(serializedMetadata, RSA_OAEP), "UTF-8")).toOption.get
      case "PQC" => decode[StorageObjectMetadata](new String(decryptArrayWithKyber(serializedMetadata), "UTF-8")).toOption.get
      case "HSM" => // deserialize flattenArray to StorageObjectMetadata
        decode[StorageObjectMetadata](new String(decryptArrayWithHSM(serializedMetadata, account.fileSystemModel.retrieveUserdata(account.MY_USER).hsmKeyId.get), "UTF-8")).toOption.get
    }
  }

  /**
   * Generates a cryptographic signature for a given string using the user's private signing key.
   *
   * The signature algorithm (RSA, PQC Dilithium, or HSM) is dynamically selected based on 
   * the user's `metadata-encryption` configuration.
   *
   * @param str The plaintext string to sign.
   * @param account The implicitly passed user account holding the private key material.
   * @return A Base64-encoded string representing the cryptographic signature.
   */
  def signString(str: String)(implicit account: Account): String = {
    val signed = account.userProps.getProperty("metadata-encryption") match {
      case "RSA" => signStringWithRSA(str.getBytes("UTF-8"))
      case "PQC" => signWithDilithium(str.getBytes("UTF-8"))
      case "HSM" => account.cloudHSMHandler.encryptObjectWithHSM(str.getBytes("UTF-8"), account.fileSystemModel.retrieveUserdata(account.MY_USER).hsmSignKeyId.get)
    }

    Base64.getEncoder().encodeToString(signed)
  }

  /**
   * Verifies the cryptographic signature of a given keyword or payload against a user's public key.
   *
   * It retrieves or extracts the signing public key from the `UserMetadata` (caching it locally to avoid 
   * repeated certificate parsing). It supports RSA, PQC (Dilithium), and HSM verification pathways.
   *
   * @param userMetadata The metadata containing the target user's public certificate and encryption type.
   * @param signature The Base64-encoded signature to verify.
   * @param keyWord The original payload string that was signed.
   * @param account The implicitly passed user account context.
   * @return `true` if the signature is valid; `false` otherwise.
   */
  def verifySignature(userMetadata: UserMetadata, signature: String, keyWord: String)(implicit account: Account): Boolean = {
    logger.trace(s"verifySignature user=${userMetadata.userName}, payloadLength=${keyWord.length}")

    /**
     * Retrieves or extracts the public key for the user.
     *
     * @return The public key
     */
    def getOrExtractPublicKey(): PublicKey = {

      if (account.userNameToSigningPublicKey.contains(userMetadata.userName) == false) {
        userMetadata.metadataEncryption match {
          case Some("RSA") =>
            val publicKey =
              if (userMetadata.publicKeyCert.isDefined) {
                decodeRSAPublicKeyFromCertForUser(
                  userMetadata.publicKeyCert.get.getBytes, userMetadata.userName, checkEndDate = false, account)._1
              } else {
                // First ADD_USERDATA: only the raw PEM is present, cert is issued later.
                extractRSAPublicKeyFromPEM(userMetadata.publicKey.get)
              }
            account.userNameToSigningPublicKey.update(userMetadata.userName, publicKey)

          case Some("PQC") =>
            val publicKey =
              if (userMetadata.publicPQCKeyCertPEM.isDefined) {
                val (certIssuer, enforceCn) = pqcCertParams(account)
                val extractedPEMs = extractPEMsFromCertificate(
                  userMetadata.publicPQCKeyCertPEM.get,
                  certIssuer,
                  CertSubject.forUser(account.ACCOUNT_CONTAINER_PREFIX, userMetadata.userName),
                  enforceAltaStataIssuerCn = enforceCn,
                )
                getPQCPublicKeyFromEncoded("DILITHIUM", AsymmetricKeysGenerator.pemToByteArray(extractedPEMs._2)).get
              } else {
                getPQCPublicKeyFromEncoded(
                  "DILITHIUM",
                  AsymmetricKeysGenerator.pemToByteArray(userMetadata.publicDilithiumKeyPEM.get)
                ).get
              }
            account.userNameToSigningPublicKey.update(userMetadata.userName, publicKey)
        }
      }

      account.userNameToSigningPublicKey.get(userMetadata.userName).get
    }

    val decodedSignature = Base64.getDecoder().decode(signature)

    userMetadata.metadataEncryption match {
      case Some("RSA") =>

        // Match signString (UTF-8). Bare getBytes() breaks on Windows Cp1252 (✹ in version paths).
        verifySignatureWithRSA(getOrExtractPublicKey(), keyWord.getBytes("UTF-8"), decodedSignature)

      case Some("PQC") =>

        verifySignatureWithDilithium(getOrExtractPublicKey(), keyWord.getBytes("UTF-8"), decodedSignature)

      case Some("HSM") =>
        // deserialize flattenArray to StorageObjectMetadata
        keyWord == new String(account.cloudHSMHandler.decryptObjectWithHSM(decodedSignature, userMetadata.hsmSignKeyId.get), "UTF-8")

      case None => throw new SecurityException("No metadata encryption schema found")
    }
  }

  /**
   * Deletes a cloud file's metadata from the catalog and invalidates its memory cache.
   *
   * @param storageFilePathIncludingVersion the canonical storage file path including version information
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) if successfully deleted; Failure with exception otherwise
   */
  def deleteCloudFileMetadata(storageFilePathIncludingVersion: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] =
    account.cloudObjectHandler
      .deleteObjectFromCloud(account.CATALOG_BUCKET, account.MY_USER, encryptObjectPathIfNeeded(storageFilePathIncludingVersion))
      .map { _ =>
        // Drop the whole bundle only after the cloud delete succeeds: otherwise a
        // failed catalog deletion would be hidden by an invalidated local cache.
        account.caches.invalidate(storageFilePathIncludingVersion)
        logger.debug(s"Deleted metadata cache entry for: $storageFilePathIncludingVersion")
      }

  /**
   * Custodian directly deletes CloudFileMetadata from another user's catalog bucket.
   * Requires custodian-level delete permissions on the target user's catalog.
   * Not supported on Azure (no scalable cross-container SAS).
   */
  def deleteCloudFileMetadataFromUserCatalog(userId: String, storageFilePathIncludingVersion: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] = {
    logger.info(s"Custodian deleting metadata from user $userId catalog: $storageFilePathIncludingVersion")
    account.cloudObjectHandler.deleteObjectFromCloud(account.CATALOG_BUCKET, userId, encryptObjectPathIfNeeded(storageFilePathIncludingVersion))
  }

  /**
   * Submits a request to share cloud file metadata with another user by encrypting the DEK for them.
   *
   * @param userId the recipient user ID
   * @param storageObjectMetadata the metadata of the cloud file being shared
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) on successful sharing; Failure with exception otherwise
   */
  def shareCloudFileMetadataRequest(userId: String, storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    // sign the authorityAttrs
    storageObjectMetadata.authorityAttrs = Some(AuthorityAttributes(account.MY_USER,
                                                               signString(account.MY_USER + "/" + storageObjectMetadata.storageAttrs.dataLocator)))

    val userMetadata = account.fileSystemModel.retrieveUserdata(userId)

    val (serializedMetadata: Array[Byte], shareMsg: String) = userMetadata.hsmKeyId match {
      case None => {
        if (userMetadata.publicKeyCert.isDefined) { // RSA
          val (userIdPublicKey, _) =
            decodeRSAPublicKeyFromCertForUser(
              userMetadata.publicKeyCert.get.getBytes, userMetadata.userName, checkEndDate = true, account)

          (encryptArrayWithRSA(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), userIdPublicKey, RSA_OAEP),
            s"/${EVENT_SHARE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath, true, userIdPublicKey)}")
        }
        else if (userMetadata.publicPQCKeyCertPEM.isDefined) { // PQC

          val (certIssuer, enforceCn) = pqcCertParams(account)
          val extractedPEMs = extractPEMsFromCertificate(
            userMetadata.publicPQCKeyCertPEM.get,
            certIssuer,
            CertSubject.forUser(account.ACCOUNT_CONTAINER_PREFIX, userMetadata.userName),
            checkEndDate = true,
            enforceAltaStataIssuerCn = enforceCn,
          )
          val userIdPublicKey = getPQCPublicKeyFromEncoded("KYBER", AsymmetricKeysGenerator.pemToByteArray(extractedPEMs._1)).get

          (encryptArrayWithKyber(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), userIdPublicKey),
            s"/${EVENT_SHARE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath, true, userIdPublicKey)}")
        }
        else {
          throw new SecurityException(
            s"Cannot share with '${userMetadata.userName}': no signed user certificate yet " +
              "(custodian must finish certificate signing before share)")
        }
      }
      case Some(keyId) => {
        (encryptArrayWithHSM(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), keyId),
          s"/${EVENT_SHARE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath)}")
      }
    }

    account.cloudObjectHandler.storeObjectToCloud(serializedMetadata, account.CHANGES_BUCKET, userId,
      account.QUEUE_NAME + "/" + CHANGE_TIME_FORMAT.format(System.currentTimeMillis) + shareMsg)
  }

  /**
   * It places the command to the <changes> bucket
   * TODO: Use "all" as the version if you want to remove all the versions
   */
  def deleteSharedCloudFileMetadataRequest(userId: String, storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    // sign the authorityAttrs
    storageObjectMetadata.authorityAttrs = Some(AuthorityAttributes(account.MY_USER,
                                                               signString(account.MY_USER + "/" + storageObjectMetadata.storageAttrs.dataLocator)))

    val userMetadata = account.fileSystemModel.retrieveUserdata(userId)

    val (serializedMetadata: Array[Byte], deleteMsg: String) = userMetadata.hsmKeyId match {
      case None => {
        if (userMetadata.publicKeyCert.isDefined) { // RSA
          val (userIdPublicKey, _) =
            decodeRSAPublicKeyFromCertForUser(
              userMetadata.publicKeyCert.get.getBytes, userMetadata.userName, checkEndDate = false, account)

          (encryptArrayWithRSA(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), userIdPublicKey, RSA_OAEP),
            s"/${EVENT_DELETE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath, true, userIdPublicKey)}")
        }
        else if (userMetadata.publicPQCKeyCertPEM.isDefined) { // PQC
          val (certIssuer, enforceCn) = pqcCertParams(account)
          val extractedPEMs = extractPEMsFromCertificate(
            userMetadata.publicPQCKeyCertPEM.get,
            certIssuer,
            CertSubject.forUser(account.ACCOUNT_CONTAINER_PREFIX, userMetadata.userName),
            enforceAltaStataIssuerCn = enforceCn,
          )
          val userIdPublicKey = getPQCPublicKeyFromEncoded("KYBER", AsymmetricKeysGenerator.pemToByteArray(extractedPEMs._1)).get

          (encryptArrayWithKyber(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), userIdPublicKey),
            s"/${EVENT_DELETE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath, true, userIdPublicKey)}")
        }
        else {
          throw new SecurityException(
            s"Cannot revoke share for '${userMetadata.userName}': no signed user certificate yet " +
              "(custodian must finish certificate signing first)")
        }
      }
      case Some(keyId) => {
        (encryptArrayWithHSM(storageObjectMetadata.asJson.noSpaces.getBytes("UTF-8"), keyId),
          s"/${EVENT_DELETE}/from=${account.MY_USER}/${encryptObjectPathIfNeeded(storageObjectMetadata.getObjectPath)}")
      }
    }

    account.cloudObjectHandler.storeObjectToCloud(serializedMetadata, account.CHANGES_BUCKET, userId,
      account.QUEUE_NAME + "/" + CHANGE_TIME_FORMAT.format(System.currentTimeMillis) + deleteMsg)

    account.cloudMsgsHandler.sendMsgToUser(account.CUSTODIAN_USER, deleteMsg)
  }

  /**
   * Creates a checkpoint placeholder file in the catalog checkpoints directory.
   *
   * @param objectPath the target file path
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) on success; Failure otherwise
   */
  def createCloudFileCheckpoint(objectPath: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    account.cloudObjectHandler.storeObjectToCloud(Array.emptyByteArray, account.CATALOG_BUCKET, account.MY_USER, account.PRIVATE_DATA_DIRECTORY + "checkpoints/" + encryptObjectPathIfNeeded(objectPath))
  }

  /**
   * Deletes the checkpoint placeholder file from the catalog checkpoints directory.
   *
   * @param objectPath the target file path
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) on success; Failure otherwise
   */
  def deleteCloudFileCheckpoint(objectPath: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    account.cloudObjectHandler.deleteObjectFromCloud(account.CATALOG_BUCKET, account.MY_USER, account.PRIVATE_DATA_DIRECTORY + "checkpoints/" + encryptObjectPathIfNeeded(objectPath))
  }

  /**
   * Directly retrieves a list of object keys from the underlying cloud object store.
   *
   * @param bucketName the target cloud bucket/container name
   * @param user user namespace/prefix context
   * @param prefix path prefix filter
   * @param useFlatBlobListing true for recursive listing; false for flat/nested hierarchies
   * @param ec the implicit execution context
   * @param account the account context
   * @return a Java Iterator of listed object names
   */
  def retrieveObjectsList(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean)(implicit ec: ExecutionContext, account: Account): java.util.Iterator[String] = {
    logger.trace(s"\tretrieveObjectsList:  $bucketName $user $prefix $useFlatBlobListing")
    account.cloudObjectHandler.listObjectsAtCloud(bucketName, user, prefix, useFlatBlobListing).get
  }

  /**
   * Dispatches an access control or policy change configuration notification message to an access manager user.
   *
   * @param accessManager the access manager username/destination
   * @param prefix the action/event prefix
   * @param objectPath the target file path
   * @param ec the implicit execution context
   * @param account the account context
   */
  def sendAccessConfigChange(accessManager: String, prefix: String, objectPath: String)(implicit ec: ExecutionContext, account: Account): Unit = {
    logger.trace(s"sendAccessConfigChange signString ${prefix}${objectPath} AuthorityAttributes User: ${account.MY_USER} Data: ${account.MY_USER}")

    val accessMsg = account.isEncryptNames match {
      case false => prefix + encryptObjectPathIfNeeded(objectPath)
      case true => {
        val (accessManagerPublicKey, _) =
          decodeRSAPublicKeyFromCertForUser(
            account.fileSystemModel.retrieveUserdata(accessManager).publicKeyCert.get.getBytes,
            accessManager,
            checkEndDate = false,
            account
          )

        prefix + encryptObjectPathIfNeeded(objectPath, true, accessManagerPublicKey)
      }
    }

    val objectKey = account.QUEUE_NAME + "/" + CHANGE_TIME_FORMAT.format(System.currentTimeMillis) + accessMsg

    // put MY_USER signature to the authorityAttrs
    val authorityAttrs = AuthorityAttributes(account.MY_USER, signString(objectKey))
    // Must match checkIfAuthorityValid (new String(..., "UTF-8")).
    val fileData = authorityAttrs.asJson.noSpaces.getBytes("UTF-8")

    account.cloudObjectHandler.storeObjectToCloud(fileData, account.CHANGES_BUCKET, accessManager, objectKey).get

    account.cloudMsgsHandler.sendMsgToUser(accessManager, accessMsg)
  }

  /**
   * Retrieves, decrypts, and verifies a custom data attribute associated with a cloud file (e.g. "size", "readers").
   *
   * @param storageObjectMetadata the metadata of the cloud file
   * @param attributeName the target attribute name to fetch
   * @param trustCachedSize true to leverage cached "size" metadata directly, skipping cloud call
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(plainTextValue) on successful retrieval and decryption; Failure with exception otherwise
   */
  def retrieveCloudFileDataAttribute(storageObjectMetadata: StorageObjectMetadata, attributeName: String, trustCachedSize: Boolean = false)(implicit ec: ExecutionContext, account: Account): Try[String] = Try {
    val cachePath = storageObjectMetadata.getObjectPath

    // `size` and `readers` are mutable in place under the same version key: an append rewrites `size`,
    // a share/revoke rewrites `readers` — both with no cross-process cache-invalidation signal (the cache
    // has no TTL). Serving them from cache therefore returns stale content (old preview length, or a
    // freshly shared reader missing). Always re-fetch them from cloud so they are authoritative; the cost
    // is one small GET on the deliberate operations that read them. We still write `size` into the cache
    // below because the single-chunk fast path (cachedChunkZeroPlainIfBundleMatchesStorageSize) uses the
    // cached `size` blob purely as a validation token for chunk 0 — not to serve the value directly.
    //
    // `trustCachedSize` is an opt-in, per-read override (e.g. ML dataset epochs over write-once files):
    // the caller declares THIS file's content is immutable, so the cached `size` is trusted and the
    // per-open cloud GET is skipped. It relaxes ONLY `size`; `readers` stays always-fresh because the ACL
    // can change (share/revoke) independently of content. On a cold cache it still falls through to a
    // fresh fetch below, so correctness is preserved on the first read.
    val forceFresh = (attributeName == "size" && !trustCachedSize) || attributeName == "readers"
    // Only `size` gates the chunk-0 cache, so only a `size` change evicts chunk 0.
    val evictChunkOnChange = attributeName == "size"

    // Bind once: a second getAttr() call can return null if Caffeine evicts the entry between
    // the check and the read (byte-pressure or concurrent invalidate), then the AES decrypt below NPEs.
    val cachedAttr = account.caches.getAttr(cachePath, attributeName)
    val cipherText =
      if (cachedAttr != null && !forceFresh) {
        logger.debug(s"attrCache hit: $cachePath/$attributeName")
        cachedAttr
      } else {
        val fetched = account.cloudObjectHandler.retrieveObjectFromCloud(account.DATA_PROPERTIES_BUCKET, storageObjectMetadata.storageAttrs.dataOwner, storageObjectMetadata.storageAttrs.dataAttributesLocator + "/" + attributeName).get
        // A `size` ciphertext that differs from what we previously cached means the version's
        // content changed (append) under the same cache key: the cached chunk 0 is now stale.
        // GCM attributes use a fresh nonce per write, so any rewrite also invalidates
        // the cached chunk even when the logical size happens to remain unchanged.
        if (evictChunkOnChange && cachedAttr != null && !java.util.Arrays.equals(cachedAttr, fetched)) {
          logger.info(s"retrieveCloudFileDataAttribute: 'size' changed on cloud for $cachePath; evicting stale cached chunk 0")
          account.caches.clearChunk(cachePath)
        }
        account.caches.putAttr(cachePath, attributeName, fetched)
        fetched
      }

    val plainText = decryptGcmObject(
      cipherText,
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey))

    new String(plainText, StandardCharsets.UTF_8)
  }

  /**
   * Encrypts and stores a custom data attribute associated with a cloud file on the storage provider.
   *
   * @param storageObjectMetadata the metadata of the cloud file
   * @param dataAttribute the plaintext value to encrypt and store
   * @param attributeName the target attribute name
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) on successful storage; Failure otherwise
   */
  def storeCloudFileDataAttribute(storageObjectMetadata: StorageObjectMetadata, dataAttribute: String, attributeName: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    val cipherText = encryptGcmObject(
      dataAttribute.getBytes("UTF-8"),
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey))

    account.cloudObjectHandler.storeObjectToCloud(cipherText, account.DATA_PROPERTIES_BUCKET, storageObjectMetadata.storageAttrs.dataOwner, storageObjectMetadata.storageAttrs.dataAttributesLocator + "/" + attributeName).get

    val path = storageObjectMetadata.getObjectPath
    if (attributeName == "size") {
      account.caches.clearChunk(path)
    }

    // Attribute may be re-written (e.g. "size" during append). Refresh the cache rather than
    // serving the previous ciphertext.
    account.caches.putAttr(path, attributeName, cipherText)
  }

  /**
   * Deletes a custom data attribute file from the cloud storage and invalidates its memory cache.
   *
   * @param storageObjectMetadata the metadata of the cloud file
   * @param attributeName the target attribute name to delete
   * @param ec the implicit execution context
   * @param account the account context
   * @return Success(()) on successful deletion; Failure otherwise
   */
  def deleteCloudFileDataAttribute(storageObjectMetadata: StorageObjectMetadata, attributeName: String)(implicit ec: ExecutionContext, account: Account): Try[Unit] = Try {
    val path = storageObjectMetadata.getObjectPath
    if (attributeName == "size") {
      account.caches.clearChunk(path)
    }
    account.caches.invalidateAttr(path, attributeName)

    account.cloudObjectHandler.deleteObjectFromCloud(account.DATA_PROPERTIES_BUCKET,
        storageObjectMetadata.storageAttrs.dataOwner,
        storageObjectMetadata.storageAttrs.dataAttributesLocator + "/" + attributeName)
  }

  val KNOWN_DATA_ATTRIBUTES: Seq[String] = Seq("size", "readers", "eTag", "s3metadata")

  /**
   * Copies known data attributes onto another record's locator (used by rename).
   * {@code size} is required; other attributes are best-effort.
   */
  def copyKnownCloudFileDataAttributes(from: StorageObjectMetadata, to: StorageObjectMetadata)(implicit ec: ExecutionContext, account: Account): Unit = {
    KNOWN_DATA_ATTRIBUTES.foreach { attribute =>
      catching(classOf[Throwable]) either {
        val value = retrieveCloudFileDataAttribute(from, attribute).get
        storeCloudFileDataAttribute(to, value, attribute).get
      } match {
        case Left(ex) if attribute == "size" => throw ex
        case Left(ex) =>
          logger.debug(s"copyKnownCloudFileDataAttributes skip ${attribute} for ${from.getObjectPath} -> ${to.getObjectPath}", ex)
        case _ =>
      }
    }
  }

  /**
   * Deletes all known standard data attributes associated with a cloud file in parallel/sequence.
   *
   * @param storageObjectMetadata the metadata of the target cloud file
   * @param ec the implicit execution context
   * @param account the account context
   */
  def deleteKnownCloudFileDataAttributes(storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext, account: Account): Unit = {
    KNOWN_DATA_ATTRIBUTES.foreach { attribute =>
      catching(classOf[Throwable]) either {
        deleteCloudFileDataAttribute(storageObjectMetadata, attribute).get
      } match {
        case Left(ex) => logger.debug(s"deleteKnownCloudFileDataAttributes skip ${attribute} for ${storageObjectMetadata.getObjectPath}", ex)
        case _ =>
      }
    }
  }

  /**
   * Verifies that the storage object metadata was signed by our own user identity.
   *
   * @param storageObjectMetadata the metadata containing the signature to verify
   * @param account the account context
   * @throws SecurityException if signature validation fails or is missing
   */
  def checkIfMetadataIsSignedByMyself(storageObjectMetadata: StorageObjectMetadata)(implicit account: Account): Unit = {
    if (storageObjectMetadata.metadataSignature == None) {
      throw new SecurityException("No metadata signature found")
    } else {
      val userdata = account.fileSystemModel.retrieveUserdata(account.MY_USER)
      val signature = storageObjectMetadata.metadataSignature.get
      val expanded = metadataAuthorityString(account.MY_USER, storageObjectMetadata)

      if (!verifySignature(userdata, signature, expanded)) {
        throw new SecurityException(s"checkIfMetadataIsSignedByMyself: Metadata signature is not equal to ${expanded}")
      }
    }
  }

  /**
   * Calculates the total number of chunks needed to store a file of the given size.
   *
   * @param fileSize the total logical size of the file
   * @param chunkMaxSize the maximum allowed size of a single plaintext chunk (defaults to 10MB)
   * @return the total number of chunks (at least 1 if size > 0; 0 if size == 0)
   */
  def totalChunks(fileSize: Long, chunkMaxSize: Int = PLAIN_CHUNK_MAX_SIZE): Long =
    fileSize / chunkMaxSize + (if (fileSize % chunkMaxSize > 0) 1 else 0)

  /**
   * Computes the specific plaintext size of a given chunk ID inside a file.
   *
   * @param fileSize the total logical size of the file
   * @param chunkId the 0-indexed ID of the target chunk
   * @param chunkMaxSize the maximum allowed size of a single plaintext chunk
   * @return the size in bytes of the designated chunk
   */
  def chunkSize(fileSize: Long, chunkId: Long, chunkMaxSize: Int): Int = {
    if (chunkId < totalChunks(fileSize, chunkMaxSize) - 1)
      chunkMaxSize
    else
      (fileSize - chunkId * chunkMaxSize).toInt
  }

  //  /**
  //   * Converts "data.00000000000000000123.chunk" to 123
  //   */
  //  private def extractChunkNumber(fileName: String): Long = {
  //    val Pattern = """data\.([0-9]+)\.chunk""".r
  //
  //    	fileName match {
  //    		case Pattern(c) => c.toLong
  //    		case _ => 0
  //    	}
  //  }

  private def formatChunkId(chunkId: Long): String = {
    CHUNK_FILE_NAME_FORMAT.format(chunkId / 10000, chunkId % 10000)
    //"data.%025d.chunk".format(chunkId)
  }
}

