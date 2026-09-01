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

import scala.collection._
import org.slf4j.LoggerFactory

import scala.concurrent._
import com.altastata.utils.Constants._

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import java.util.{Base64, Calendar, Date, UUID}
import com.altastata.filesystem.common.CloudFile

import java.io.File
import java.io.StringReader
import scala.Left
import scala.Right
import com.altastata.filesystem.{AuthorityAttributes, DeleteFileException, FileSystemModel, OperationCanceledCloudObjectException, RenameFileException, RetrieveFileException, ShareFileException, StoreFileAndMetadataException, UserMetadata}

import scala.collection.JavaConverters._
import com.altastata.filesystem.common.FileSystemHandler
import com.altastata.utils.Account

import java.nio.ByteBuffer
import java.io.InputStream
import com.altastata.filesystem.common.VersionAttributes
import com.altastata.filesystem.utils.LocalFileAsynchronousChannelHandler
import com.altastata.utils.DataChannel
import com.altastata.utils.ChannelType._
import com.altastata.filesystem.utils.ByteBufferChannelHandler

import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.io.FilenameUtils

import scala.collection.mutable.ArrayBuffer
import scala.util.control.Exception._
import org.json.JSONObject
import com.altastata.network.CurlEmulator
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.api.CloudFileOperationStatus
import com.altastata.crypto.X509.extractPEMsFromCertificate
import com.altastata.crypto.CertSubject

import java.io.FileNotFoundException
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Encoder, Json}

// Global configuration for circe to exclude None values automatically
import io.circe.generic.semiauto._
import io.circe.{Encoder, Printer}

// Create a companion object for the class to hold the printer
object SecureCloudFileSystemModel {
  // Configure circe to exclude None values globally and use pretty printing
  implicit val noneDropper: Printer = Printer.spaces2.copy(
    dropNullValues = true,
    indent = "  ", // 2 spaces for indentation
    lbraceRight = "\n", // Line break after opening brace
    rbraceLeft = "\n", // Line break before closing brace
    lbracketRight = "\n", // Line break after opening bracket
    rbracketLeft = "\n", // Line break before closing bracket
    colonLeft = "", 
    colonRight = " " // Space after colon
  )
}

/**
 * Core implementation of the AltaStata secure file system model.
 * 
 * This class serves as the primary integration point for managing cloud files, 
 * handling secure file metadata, user identity verification (RSA/PQC), and 
 * coordinating cryptographic operations across supported cloud providers.
 * 
 * It extends [[FileSystemModel]] for standard file operations and mixes in 
 * [[SecureCloudOperations]] to handle chunked encryption, compression, and data transfer.
 *
 * @param account The authenticated user's account context, containing credentials,
 *                cached attributes, and licensing information.
 */
class SecureCloudFileSystemModel(implicit account: Account) extends FileSystemModel with SecureCloudOperations {
  import SecureCloudFileSystemModel._

  private val logger = LoggerFactory.getLogger(getClass)

  val usersMetadata = new ConcurrentHashMap[String, UserMetadata]()
  val userAttributesForCustodian = new ConcurrentHashMap[String, UserAttributesForCustodian]()

  val LOCATOR_FRAGMENT_FORMAT = "%05d"

  /**
   * Verify user identity certificate.
   * Org entitlement (`license.jwt`) is enforced earlier in [[com.altastata.licensing.AccountLicensing.refresh]].
   *
   * - HSM / HPCS: JWT only (no user cert required).
   * - PQC: signed PQC cert required (license already required at load).
   * - RSA Community (no JWT): AltaStata-signed RSA cert required.
   * - RSA licensed: user cert required, verified against `org-ca.pem`.
   */
  def verifyUserIdentity(): Unit = {
    val metadataEncryption = account.userProps.getProperty("metadata-encryption")
    val keyProtection = account.userProps.getProperty("key-protection")

    if (metadataEncryption == "HSM" || keyProtection == "HPCS") {
      logger.info(s"HSM/HPCS user ${account.MY_USER}: identity gate is org license only")
      return
    }

    val calendar = Calendar.getInstance
    calendar.add(Calendar.MONTH, 2)
    val inTwoMonths = calendar.getTime

    val userMetadata = retrieveUserdata(account.MY_USER)
    val certIssuer = account.getCertTrustPublicKey
    val community = account.getOrgLicense.isEmpty
    // Community RSA/PQC: must verify CN=AltaStata against embedded issuer key.
    val enforceAltaStataCn = account.enforceAltaStataIssuerCn

    if (metadataEncryption == "PQC") {
      val pqcCert = userMetadata.publicPQCKeyCertPEM.getOrElse(
        throw new SecurityException("PQC account requires a signed PQC user certificate in userdata")
      )
      val extractedPEMs = extractPEMsFromCertificate(
        pqcCert,
        certIssuer,
        CertSubject.forUser(account.ACCOUNT_CONTAINER_PREFIX, account.MY_USER),
        checkEndDate = true,
        enforceAltaStataIssuerCn = enforceAltaStataCn
      )
      if (extractedPEMs._3.before(inTwoMonths)) {
        account.userMsgs.add(s"time: ${CloudFile.DATEFORMAT.format(System.currentTimeMillis)}\nevent: The license certificate will be expired soon. Please, reach out to your custodian.")
      }
    } else {
      // RSA (software keys)
      val rsaCert = userMetadata.publicKeyCert.getOrElse(
        throw new SecurityException(
          if (community)
            "Community RSA requires an AltaStata-signed user certificate in userdata"
          else
            "Licensed RSA requires a user certificate signed by org-ca.pem"
        )
      )
      val (_, endDate) =
        decodeRSAPublicKeyFromCertForUser(
          rsaCert.getBytes,
          account.MY_USER,
          checkEndDate = true,
          account
        )
      if (endDate.before(inTwoMonths)) {
        account.userMsgs.add(s"time: ${CloudFile.DATEFORMAT.format(System.currentTimeMillis)}\nevent: The license certificate will be expired soon. Please, reach out to your custodian.")
      }
    }

    logger.info(s"User identity verified for ${account.MY_USER}")
  }

  /**
   * Discovers and resolves unfinalized file uploads (checkpoints) in the background.
   *
   * If a file upload was interrupted (e.g., app crash or network failure), a checkpoint remains in the
   * user's private data directory. This method asynchronously scans for such checkpoints and automatically
   * deletes the partially uploaded chunks and corrupted metadata to prevent cloud storage leaks.
   *
   * Admin users are excluded from this operation.
   *
   * @return This `SecureCloudFileSystemModel` instance (chainable).
   */
  def startResolvingCheckpoints(): SecureCloudFileSystemModel = {
    if (account.MY_USER == "admin") {
      this
    }
    else {
      Future {
        val checkPointsIt = listCheckpoints(account.MY_USER)
        val toDelete = scala.collection.mutable.ListBuffer[CloudFile]()
        val timestamps = scala.collection.mutable.TreeSet[Long]()

        while (checkPointsIt.hasNext) {
          val detectedCloudFile = checkPointsIt.next()
          timestamps += detectedCloudFile.getVersions.last.getCreateTime

          val realCloudFile = account.getFileSystemHandler().findCloudFile(detectedCloudFile)
          if (realCloudFile == null) toDelete += detectedCloudFile
          else toDelete += realCloudFile
        }

        if (toDelete.nonEmpty) {
          logger.info(s"Resolving checkpoints for user: ${account.MY_USER}")

          val deleteResults = deleteCloudFiles(toDelete.toArray, timestamps.toList.map(Long.box).asJava)
          deleteResults.foreach { result =>
            if (result.getOperationState == OperationState.DONE) {
              logger.info(s"delete: ${result.getCloudFileVersionPath}")
            } else {
              logger.error(
                s"delete: ${result.getCloudFileVersionPath} error: ${result.getError}\n${result.getErrorTrace}"
              )
              account.getUserMsgs.add(s"Delete error: \n${result.getError}")
            }
          }

          // check if some checkpoints cannot be handled
          if (toDelete.size > deleteResults.size) {
            val unexistingCheckPointsIt = listCheckpoints(account.MY_USER)
            while (unexistingCheckPointsIt.hasNext) {
              val detectedCheckPoint = unexistingCheckPointsIt.next()

              logger.warn(s"Checkpoint ${detectedCheckPoint} cannot be handled as the correspondent file does not exist. It will be deleted.")

              account.getUserMsgs.add(s"Warning:\nCheckpoint ${detectedCheckPoint} cannot be handled as the correspondent file does not exist. It will be deleted.")

              val absolutePath = detectedCheckPoint.getLastCloudObjectPathIncludingVersion()

              deleteCloudFileCheckpoint(absolutePath)(OpsExecutors.ecFastFileOps, account)
            }
          }

        }
      }(OpsExecutors.ecFastFileOps)
    }

    this
  }

  /**
   * Retrieves a paginated and optionally filtered iterator of cloud files for the current user.
   *
   * By default, it skips files stored in the internal private data directory (e.g., checkpoints).
   * It handles transparent decryption of obfuscated file paths and automatically merges 
   * multiple versions of the same file into a single `CloudFile` domain object.
   *
   * @param prefix The logical path prefix to filter the listing (e.g., "my-folder/").
   * @param useFlatBlobListing If true, treats the cloud storage as flat without virtual directories.
   * @param startAfter The cloud object key to start listing after (for pagination).
   * @param endBefore The cloud object key to end listing before.
   * @param mergeVersions If true, combines all historical versions of a file into a single `CloudFile` entity.
   * @return A Java Iterator over the resolved `CloudFile` objects.
   * @todo Implement robust error handling instead of returning `null` on failure (e.g., return Try).
   */
  override def listCloudFiles(prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null, mergeVersions: Boolean = true): java.util.Iterator[CloudFile] = {
    implicit val ec = OpsExecutors.ecFastFileOps

    try {
      val it = account.cloudObjectHandler.listObjectsAtCloud(account.CATALOG_BUCKET, account.MY_USER, encryptObjectPathIfNeeded(prefix, false), useFlatBlobListing, startAfter, endBefore).get
        .asScala.filter { _.contains(account.PRIVATE_DATA_DIRECTORY) == false }
        .map(str => account.getFileSystemHandler().parseObjectPathIncludingVersion(decryptObjectPathIfNeeded(str)))
        .filter { _ != null }

      if (mergeVersions == false) {
        it.asJava
      } else {
        // lazy merge the versions of the same CloudFile
        var prevCloudFile: CloudFile = null
        val mergedVersionsForCloudFileIt = it.flatMap(currentCloudFile => {
          // special treatment for the last element
          if (it.hasNext == false) {
            if (prevCloudFile == null) {
              List(currentCloudFile)
            } else if (prevCloudFile.getPath == currentCloudFile.getPath) {
              for (versionAttributes <- currentCloudFile.getVersions.asScala) {
                prevCloudFile.addVersion(versionAttributes)
              }

              List(prevCloudFile)
            } else {
              List(prevCloudFile, currentCloudFile)
            }
          } else { // if its not a last element
            if (prevCloudFile == null) {
              prevCloudFile = currentCloudFile
              List()
            } else if (prevCloudFile.getPath == currentCloudFile.getPath) {
              for (versionAttributes <- currentCloudFile.getVersions.asScala) {
                prevCloudFile.addVersion(versionAttributes)
              }

              List()
            } else {
              val toReturn = List(prevCloudFile)

              prevCloudFile = currentCloudFile

              toReturn
            }
          }
        })

        mergedVersionsForCloudFileIt.asJava
      }
    } catch {
      // TODO: make this and other functions to return Try
      case e: Throwable =>
        logger.error("listCloudFiles", e) // TODO: handle error
        null
    }
  }

  override def listUsers(): java.util.Iterator[String] = {
    implicit val ec = OpsExecutors.ecFastFileOps
    account.cloudObjectHandler.listObjectsAtCloud(account.USERS_BUCKET, "all", "", true).get
  }

  override def listUsers(userName: String = account.MY_USER): java.util.Iterator[String] = {
    implicit val ec = OpsExecutors.ecFastFileOps
    account.cloudObjectHandler.listObjectsAtCloud(account.CATALOG_BUCKET, userName, account.PRIVATE_DATA_DIRECTORY + "users", true).get.
      asScala.map(_.replace(account.PRIVATE_DATA_DIRECTORY + "users/", "")).asJava
  }

  override def listCheckpoints(userName: String = account.MY_USER): java.util.Iterator[CloudFile] = {
    implicit val ec = OpsExecutors.ecFastFileOps

    // the file might be retrieved only using own private RSA or Kyber key
    account.cloudObjectHandler.listObjectsAtCloud(account.CATALOG_BUCKET, userName, account.PRIVATE_DATA_DIRECTORY + "checkpoints", true).get.
      asScala.map(_.replace(account.PRIVATE_DATA_DIRECTORY + "checkpoints/", "")).
      map(decryptObjectPathIfNeeded(_)).
      map(account.getFileSystemHandler().parseObjectPathIncludingVersion(_)).asJava
  }

  override def getDataAttributeForCloudFile(cloudFile: CloudFile, timestamp: java.lang.Long, name: String): String = {
    // Use the new parallel method as a special case
    val names = List(name).asJava
    val attributesMap = getDataAttributesForCloudFile(cloudFile, timestamp, names)
    attributesMap.get(name)
  }

  override def getDataAttributesForCloudFile(cloudFile: CloudFile, timestamp: java.lang.Long, names: java.util.List[String]): java.util.Map[String, String] = {
    implicit val ec = OpsExecutors.ecFastFileOps

    /**
     * Returns a stubbed value for the specified attribute name.
     *
     * @param name the attribute name
     * @return the stub value for the attribute
     */
    def getStubAttribute(name: String): String = name match {
      case "size" => "-1"
      case "readers" => ""
      case "eTag" => "d41d8cd98f00b204e9800998ecf8427e" // MD5 of empty string
      case _ => null
    }

    val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
    if (bestMatchingVersion == null) {
      return names.asScala.map(name => (name, getStubAttribute(name))).toMap.asJava
    }

    val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)

    try {
      // Use encrypted attribute retrieval for other attributes
      val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get

      val results = Await.result(Future.traverse(names.asScala) { name =>
        Future {
      try {
        val result = retrieveCloudFileDataAttribute(storageObjectMetadata, name).get
        (name, result)
      } catch {
        case e: Throwable =>
          logger.error(s"getDataAttributesForCloudFile: failed to retrieve $name, falling back to stub", e)
          (name, getStubAttribute(name))
      }
        }
      }, Duration.Inf)
      
      results.toMap.asJava
      
    } catch {
      case e: Throwable =>
        logger.error(s"getDataAttributesForCloudFile for ${cloudFile}", e)
        names.asScala.map(name => (name, getStubAttribute(name))).toMap.asJava
    }
  }

  override def setDataAttributeForCloudFile(cloudFile: CloudFile, timestamp: java.lang.Long, name: String, value: String): Unit = {
    // Validate that only allowed attribute names are used
    if (name == "size" || name == "readers") {
      throw new IllegalArgumentException(s"Attribute name '${name}' is not allowed.")
    }
    
    implicit val ec = OpsExecutors.ecFastFileOps

    val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
    if (bestMatchingVersion != null) {
      val storageCloudObjectPathIncludingVersion: String = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)
      try {
        val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
        storeCloudFileDataAttribute(storageObjectMetadata, value, name).get
      } catch {
        case e: Throwable =>
          logger.error(s"setDataAttributeForCloudFile for ${cloudFile} name=${name}", e)
          throw e
      }
    } else {
      throw new RuntimeException(s"No matching version found for ${cloudFile} at timestamp ${timestamp}")
    }
  }

  override def deleteDataAttributeForCloudFile(cloudFile: CloudFile, timestamp: java.lang.Long, name: String): Unit = {
    // Validate that only allowed attribute names are used
    if (name == "size" || name == "readers") {
      throw new IllegalArgumentException(s"Attribute name '${name}' is not allowed.")
    }
    
    implicit val ec = OpsExecutors.ecFastFileOps

    val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
    if (bestMatchingVersion != null) {
      val storageCloudObjectPathIncludingVersion: String = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)
      try {
        val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
        deleteCloudFileDataAttribute(storageObjectMetadata, name).get
      } catch {
        case e: Throwable =>
          logger.error(s"deleteDataAttributeForCloudFile for ${cloudFile} name=${name}", e)
          throw e
      }
    } else {
      throw new RuntimeException(s"No matching version found for ${cloudFile} at timestamp ${timestamp}")
    }
  }

  override def uploadLocalFilesToCloud(files: java.util.List[(File, CloudFile)], waitUntilDone: Boolean = true): Array[CloudFileOperationStatus] = {
    // Convert files to optional channels
    val fileToChannelMap = files.asScala
      .map { case (file, cloudFile) =>
        val channel = if (cloudFile.isDirectory) null
                      else LocalFileAsynchronousChannelHandler(file.getPath, READ).asInstanceOf[DataChannel]

        // Return the tuple with optional channel and cloud file
        (channel, cloudFile)
      }.toList.asJava

    uploadDataChannelsToCloud(fileToChannelMap, waitUntilDone)
  }

  override def uploadDataChannelsToCloud(fileToChannelMap: java.util.List[(DataChannel, CloudFile)], waitUntilDone: Boolean): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    try {
      // Process cloud files
      val processedFiles: mutable.Buffer[(DataChannel, CloudFile)] = fileToChannelMap.asScala
        .map(ff => (ff._1, account.getFileSystemHandler().addCloudFileInUploadingProcess(ff._2)))
        .map(ff => (ff._1, ff._2.setOperationStateValue(OperationState.UPLOADING)))

      // Filter to keep only files, not directories
      val fileChannels: mutable.Buffer[(DataChannel, CloudFile)] = processedFiles
        .filter(ff => ff._2.isDirectory == false)
        .map { case (channel, cloudFile) =>
          // Safe to get() because we filtered out all directories (None values)
          (channel, cloudFile)
        }

      val storeFuture = Future.traverse(fileChannels)(storeCloudFileFuture(waitUntilDone))
      val result = Await.result(storeFuture, Duration.Inf)

      account.cloudMsgsHandler.sendMsgToUser(account.CUSTODIAN_USER, s"${EVENT_SHARE}/${account.MY_USER}")

      for (tuple <- fileToChannelMap.asScala) {
        account.getFileSystemHandler().removeCloudFileInUploadingProcess(tuple._2);
      }

      result.toArray
  } catch {
    case t: Throwable => logger.error("uploadLocalFilesToCloud", t); null
  }
}

  override def retrieveCloudFilesToLocalDirectory(toRetrieve: Array[CloudFile], outputDir: String, timestampsFilter: java.util.List[java.lang.Long], isStreaming: Boolean = false, waitUntilDone: Boolean = true, isPreview: Boolean = false): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    /**
     * Creates LocalFileAsynchronousChannelHandler for cloudFile inside outputDir
     */
    def retrieveCloudFileToLocalDirectoryFuture(baseDir: String, outputDir: String, timestampsFilter: java.util.List[java.lang.Long])(cloudFile: CloudFile): Future[Stream[CloudFileOperationStatus]] = {
      val relativePath = cloudFile.getPath.replace(baseDir, "").stripPrefix("/").stripPrefix("\\")
      val outputFilePath = FileSystemHandler.resolveDownloadPathInsideOutputDir(outputDir, relativePath)

      // Use Long.MaxValue as we want to retrieve the whole file
      retrieveCloudFileVersionFuture(LocalFileAsynchronousChannelHandler(outputFilePath, WRITE), timestampsFilter, 0L, Long.MaxValue, isStreaming, waitUntilDone, isPreview)(cloudFile)
    }

    val baseDir: String = toRetrieve(0).getParent
    val toRetrieveSubtree = account.getFileSystemHandler().getWithSubtrees(toRetrieve, OperationState.DOWNLOADING, timestampsFilter)

    val retrieveFuture = Future.traverse(toRetrieveSubtree.toStream)(retrieveCloudFileToLocalDirectoryFuture(baseDir, outputDir, timestampsFilter))
    val result = Await.result(retrieveFuture, Duration.Inf).flatten

    result.toArray
  }

  override def storeByteBufferToCloudFile(buffer: ByteBuffer, cloudFile: CloudFile, waitUntilDone: Boolean = true): CloudFileOperationStatus = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    val storeFuture = storeCloudFileFuture(waitUntilDone)((ByteBufferChannelHandler(buffer), cloudFile))
    val result = Await.result(storeFuture, Duration.Inf)

    account.cloudMsgsHandler.sendMsgToUser(account.CUSTODIAN_USER, s"${EVENT_SHARE}/${account.MY_USER}")

    result
  }

  override def retrieveCloudFileToByteBuffer(buffer: ByteBuffer, cloudFile: CloudFile, timestampsFilter: java.util.List[java.lang.Long], startChunk: java.lang.Long, inputStreamCache: java.util.Map[java.lang.Long, ByteBuffer], waitUntilDone: Boolean = true, trustCachedSize: Boolean = false): CloudFileOperationStatus = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    val finishChunk = startChunk + totalChunks(buffer.capacity, PLAIN_CHUNK_MAX_SIZE) - 1

    logger.info(s"retrieveCloudFileToByteBuffer START from ${startChunk} to ${finishChunk}")

    val retrieveFuture = retrieveCloudFileVersionFuture(ByteBufferChannelHandler(buffer, inputStreamCache), timestampsFilter, startChunk, finishChunk, false, waitUntilDone, false, trustCachedSize)(cloudFile)
    val cloudFileOperationStatus = Await.result(retrieveFuture, Duration.Inf)(0)

    if (cloudFileOperationStatus.getOperationState == OperationState.ERROR) {
      logger.error(s"retrieveCloudFileToByteBuffer from ${startChunk} to ${finishChunk}: ${cloudFileOperationStatus.getError}")
    }
    else {
      logger.info(s"retrieveCloudFileToByteBuffer END from ${startChunk} to ${finishChunk}")
    }

    cloudFileOperationStatus
  }

  override def shareCloudFiles(toShare: Array[CloudFile], userIds: Array[String], timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    val toShareSubtree = account.getFileSystemHandler().getWithSubtrees(toShare, OperationState.SHARING, null)

    val allPairs: List[(String, CloudFile)] =
      for (toUserId <- userIds.toList; cloudFile <- toShareSubtree.toStream) yield (toUserId, cloudFile)

    val shareFuture = Future.traverse(allPairs)(shareCloudFileVersionsFuture(timestampsFilter))
    val result = Await.result(shareFuture, Duration.Inf).flatten

    // TODO: do not send message if the result does not contain any Right results, but Left only
    for (toUserId <- userIds) {
      try {
        account.cloudMsgsHandler.sendMsgToUser(toUserId, s"${EVENT_SHARE}/${account.MY_USER}/${toShare.size}")
      } catch {
        case e: Throwable => logger.error(s"shareObjects to ${toUserId}", e)
      }
    }

    result.toArray
  }

  /**
   * delete(prefix, includingSubdirectories=true) already listed every file version under the
   * prefix. getWithSubtrees would LIST the catalog again once per file — catastrophic for
   * folders with thousands of entries. Use the pre-listed files directly in that case.
   */
  private def resolveDeleteTargets(
      toDelete: Array[CloudFile],
      timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFile] = {
    if (toDelete.nonEmpty && !toDelete.exists(_.isDirectory)) {
      val stampSet =
        if (timestampsFilter == null) null
        else timestampsFilter.asScala.map(_.longValue()).toSet
      toDelete.flatMap { cf =>
        val matches =
          if (stampSet == null) true
          else cf.getVersions.asScala.exists(v => stampSet.contains(v.getCreateTime))
        if (matches) Some(cf.setOperationStateValue(OperationState.DELETING))
        else None
      }
    } else {
      account.getFileSystemHandler().getWithSubtrees(toDelete, OperationState.DELETING, timestampsFilter)
    }
  }

  override def deleteCloudFiles(toDelete: Array[CloudFile], timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.ecBackgroundOps

    val toDeleteSubtree = resolveDeleteTargets(toDelete, timestampsFilter)

    // if no timestamps are detected it means that toDeleteSubtree contains only empty directories
    if (toDeleteSubtree.size == 0) {
      for (cloudFile <- toDelete; if cloudFile.isDirectory) {
        logger.debug(s"deleteCloudFiles try to delete directory: ${cloudFile}")

        account.getFileSystemHandler().tryToDeleteFile(cloudFile)
      }

      return Array[CloudFileOperationStatus]()
    } else {
      val deleteFuture = Future.traverse(toDeleteSubtree.toStream)(deleteCloudFileVersionsFuture(timestampsFilter))
      val result = Await.result(deleteFuture, Duration.Inf).flatten

      result.toArray
    }
  }

  override def revokeReaderAccess(toRevoke: Array[CloudFile], readersToRevoke: Array[String], timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    val toRevokeSubtree = account.getFileSystemHandler().getWithSubtrees(toRevoke, OperationState.SHARING, timestampsFilter)

    if (toRevokeSubtree.isEmpty) {
      return Array[CloudFileOperationStatus]()
    }

    val revokeFuture = Future.traverse(toRevokeSubtree.toStream)(revokeCloudFileVersionsFuture(timestampsFilter, readersToRevoke))
    val result = Await.result(revokeFuture, Duration.Inf).flatten

    account.getFileSystemHandler().getWithSubtrees(toRevoke, OperationState.NONE, null)
    result.toArray
  }

  override def renameCloudFiles(toRename: Array[CloudFile], oldPrefix: String, newPrefix: String, timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

    val toRenameSubtree = account.getFileSystemHandler().getWithSubtrees(toRename, OperationState.RENAMING, timestampsFilter)

    val wrongCloudFileOwners = new ArrayBuffer[CloudFileOperationStatus]()

    // check if all the versions of all cloudFiles can be renamed
    for (
      cloudFile <- toRenameSubtree.toStream;
      version <- cloudFile.getVersions.asScala
    ) {

      if (account.MY_USER != version.getTag) {
        val cloudOperationStatus =
          new CloudFileOperationStatus(s"${version}", OperationState.RENAMING)
            .setError(new RenameFileException(s"${cloudFile.getPath} ${version.getTag}_${version.getCreateTime}", new Exception(s"${account.MY_USER} is not an access manager")))

        wrongCloudFileOwners.append(cloudOperationStatus)
      }
    }

    if (wrongCloudFileOwners.size > 0) {
      wrongCloudFileOwners.toArray
    } else {
      val renameFuture = Future.traverse(toRenameSubtree.toStream)(renameCloudFileVersionsFuture(oldPrefix, newPrefix, timestampsFilter))
      val result = Await.result(renameFuture, Duration.Inf).flatten

      result.toArray
    }
  }

  /**
   * Asynchronously stores a local file or stream into the secure cloud file system.
   *
   * This handles the entire lifecycle of a file upload:
   * 1. Optional deletion of previous file versions (if configured).
   * 2. Generation of AES encryption keys and secure initialization vectors (IVs).
   * 3. Concurrent encryption, compression, and chunked uploading of the file content.
   * 4. Creation, signing, and uploading of the object's `StorageObjectMetadata`.
   * 5. Graceful rollback and cleanup if the upload fails or is canceled midway.
   *
   * @param waitUntilDone If true, blocks thread execution (if running via blocking contexts) until the upload is fully complete.
   * @param input A tuple containing the source `DataChannel` (local file/stream) and the target `CloudFile` model.
   * @return A `Future` tracking the overarching status of the cloud file upload operation.
   */
  def storeCloudFileFuture(waitUntilDone: Boolean)(input: (DataChannel, CloudFile)): Future[CloudFileOperationStatus] = {
    val (localSourceDataChannel, cloudFile) = input

    implicit val ec = OpsExecutors.ecFastFileOps

    // delete all previous versions of that file from user's catalog and data if needed
    if (account.isDeletePreviousVersionOnUpload && cloudFile.getVersions().size() > 1) {
      val timestampsToDelete = account.getFileSystemHandler().detectTimestamps(Array(cloudFile), false).asScala.dropRight(1)

      // delete asynchronously
      val deleteFuture = deleteCloudFileVersionsFuture(timestampsToDelete.asJava)(cloudFile)

      deleteFuture onComplete {
        case Success(seq) => {
          logger.info(s"Sucessfully deleted all previous versions of ${cloudFile}")
        }
        case Failure(t) => {
          logger.error(s"Delete all previous versions of ${cloudFile}", t)
        }
      }
    }

    /**
     * Handles errors that occur during the file storage process.
     *
     * @param e the throwable cause
     */
    def handleStoreError(e: Throwable) = {
      logger.error(s"storeFileFuture ${cloudFile}", e)

      // convert scala.List[scala.Long] to java.util.List<java.util.Long>
      // http://stackoverflow.com/questions/29955265/convert-scala-listscala-long-to-listjava-util-long
      val deleteFuture = deleteCloudFileVersionsFuture(List(cloudFile.getVersions.last.getCreateTime).map(java.lang.Long.valueOf).asJava)(cloudFile)

      deleteFuture onComplete {
        case Success(seq) => {
          logger.info(s"Sucessfully deleted ${cloudFile}")
        }
        case Failure(t) => {
          logger.error(s"Delete ${cloudFile}", t)
        }
      }
    }

    val createdFuture: Future[CloudFileOperationStatus] = Future {

      val aesKey = getSecureRandomBytes(256 / 8) // 256 bits

      val r = scala.util.Random

      // check if to compress the file content
      val Pattern = account.compressPattern.r
      val toCompress = cloudFile.getPath match {
        case Pattern(c) => true
        case _ => false
      }

      try {

        val storageObjectMetadata = StorageObjectMetadata("v1.0")

        storageObjectMetadata.fileAttrs = FileAttrs(FilenameUtils.separatorsToUnix(cloudFile.getPath),
                                                    cloudFile.getVersions.last.getTag,
                                                    cloudFile.getVersions.last.getCreateTime.asInstanceOf[java.lang.Long],
                                                    cloudFile.getVersions.last.getCreateTime.toString)

        // encryptedAESIV unused for AES-GCM: each cloud object carries its own IV in the blob.
        storageObjectMetadata.encryptionAttrs = EncryptionAttrs("AES-256-GCM",
                                                                Base64.getEncoder().encodeToString(aesKey),
                                                                "")

        val dataLocatorUuid = if (account.generateUUIDForDataLocator) {
                                LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                                LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                                java.util.UUID.randomUUID.toString
                              }
                              else storageObjectMetadata.getObjectPath

        val attributesLocatorUuid = if (account.generateUUIDForDataAttributesPath) {
                                      LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                                        LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                                        java.util.UUID.randomUUID.toString
                                    }
                                    else storageObjectMetadata.getObjectPath

        storageObjectMetadata.storageAttrs = StorageAttrs(account.MY_USER,
                                                          dataLocatorUuid,
                                                          toCompress.asInstanceOf[java.lang.Boolean],
                                                          if (account.isCustodianMode) account.CUSTODIAN_USER else account.MY_USER,
                                                          attributesLocatorUuid)

        storageObjectMetadata.metadataSignature = Some(signString(metadataAuthorityString(account.MY_USER, storageObjectMetadata)))

        logger.info(s"storeFileFuture: ${storageObjectMetadata.getObjectPath}")
        // Insecured log trace(s"\tstoreFileFuture: ${storageObjectMetadata}")

        val cloudFileOperationStatus = new CloudFileOperationStatus(storageObjectMetadata.getObjectPath, OperationState.UPLOADING)

        createCloudFileCheckpoint(storageObjectMetadata.getObjectPath)
        storeCloudFileMetadata(storageObjectMetadata).get

        val sourceSize = localSourceDataChannel.size()
        
        (storageObjectMetadata, sourceSize, cloudFileOperationStatus)
      } catch {
        case e: Throwable =>
          handleStoreError(e)
          localSourceDataChannel.close()
          throw new StoreFileAndMetadataException(s"Store file: ${cloudFile}", e)
      }
    }(OpsExecutors.ecFastFileOps).flatMap { case (storageObjectMetadata, sourceSize, cloudFileOperationStatus) =>
      implicit val innerEc: ExecutionContext = OpsExecutors.ecFileStoreInnerOps

      // size/readers/content are independent and may run in parallel. SHARE to the custodian
      // must not start until size (and readers) are durable — otherwise shareEvent can 404 on
      // size and drop the change as "unexisting".
      val sizeAttributeFuture = Future {
        storeCloudFileDataAttribute(storageObjectMetadata, java.lang.Long.valueOf(sourceSize).toString, "size").get
      }
      val readersAttributeFuture = Future {
        storeCloudFileDataAttribute(storageObjectMetadata, account.MY_USER, "readers").get
      }
      val contentFuture = Future {
        storeCloudFileContent(localSourceDataChannel, storageObjectMetadata)(cloudFile, cloudFileOperationStatus)
      }

      val finishFuture = Future.sequence(List(sizeAttributeFuture, readersAttributeFuture, contentFuture)).flatMap { _ =>
        Future {
          shareCloudFileMetadataRequest(account.CUSTODIAN_USER, storageObjectMetadata).get
        }
      }.map { _ =>
        deleteCloudFileCheckpoint(storageObjectMetadata.getObjectPath)
        localSourceDataChannel.close()

        cloudFile.setProgressValue(0)

        if (cloudFile.getOperationState.equals(OperationState.SHARING)) {
          cloudFile.setOperationStateValue(OperationState.UPLOADED)
        } else {
          cloudFile.setOperationStateValue(OperationState.NONE)
          account.getFileSystemHandler().attemptToSetAncestorsOperationStateAsNONE(cloudFile)
        }

        cloudFileOperationStatus.setProgressValue(1.0)
        cloudFileOperationStatus.setOperationState(OperationState.DONE)
        cloudFileOperationStatus
      }.recover {
        case e: Throwable =>
          handleStoreError(e)
          localSourceDataChannel.close()
          cloudFileOperationStatus.setError(new StoreFileAndMetadataException(s"Store file: ${cloudFile}", e))
          cloudFileOperationStatus
      }

      if (waitUntilDone) {
        finishFuture
      } else {
        Future.successful(cloudFileOperationStatus)
      }
    }(OpsExecutors.ecFastFileOps).recover {
      case e: Throwable =>
        val status = new CloudFileOperationStatus(s"${cloudFile}", OperationState.UPLOADING)
        status.setError(e match {
          case se: StoreFileAndMetadataException => se
          case _ => new StoreFileAndMetadataException(s"Store file: ${cloudFile}", e)
        })
        status
    }(OpsExecutors.ecFastFileOps)

    createdFuture
  }

  /**
   * Asynchronously retrieves (downloads) a specific version of a cloud file into a local destination channel.
   *
   * The method identifies the most appropriate file version based on the provided `timestampsFilter`.
   * It handles fetching the associated encrypted metadata, verifying cryptographic signatures, 
   * retrieving file chunks (with optional range filters for `startChunk` and `finishChunk`),
   * and decrypting/decompressing the stream sequentially or concurrently.
   *
   * @param localDstDataChannel The destination stream or file channel to write decrypted bytes.
   * @param timestampsFilter Used to select the correct historical version (e.g., closest version before timestamp).
   * @param startChunk The initial chunk index to retrieve (useful for resuming or range reads).
   * @param finishChunk The final chunk index to retrieve.
   * @param isStreaming If true, optimizes chunk fetching for sequential read access (like video streaming).
   * @param waitUntilDone If true, blocks execution until the download fully completes.
   * @param isPreview If true, optimizes retrieval for fetching only metadata or thumbnail data.
   * @param trustCachedSize If true, relies on locally cached file size rather than parsing metadata dynamically.
   * @param cloudFile The domain model of the target cloud file.
   * @return A `Future` wrapping a `Stream` of status tracking objects for the retrieval operation.
   */
  def retrieveCloudFileVersionFuture(localDstDataChannel: DataChannel, timestampsFilter: java.util.List[java.lang.Long], startChunk: Long, finishChunk: Long, isStreaming: Boolean = false, waitUntilDone: Boolean = true, isPreview: Boolean = false, trustCachedSize: Boolean = false)(cloudFile: CloudFile): Future[Stream[CloudFileOperationStatus]] = {
    implicit val ec = OpsExecutors.ecFastFileOps

    /**
     * Asynchronously retrieves a specific version of a cloud file.
     *
     * @param version the specific version to retrieve
     * @return a Future tracking the stream of cloud file operation statuses
     */
    def retrieveVersionFuture(version: VersionAttributes): Future[Stream[CloudFileOperationStatus]] = {

      val createdFuture: Future[Stream[CloudFileOperationStatus]] = Future {

        val storageCloudObjectPathIncludingVersion: String = cloudFile.getCloudObjectPathIncludingVersion(version)

        val cloudFileOperationStatus = new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.DOWNLOADING)

        try {
          logger.info(s"retrieveVersionFuture: ${storageCloudObjectPathIncludingVersion}")

          val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
          checkIfMetadataIsSignedByMyself(storageObjectMetadata)
          val dataSizeAttribute = retrieveCloudFileDataAttribute(storageObjectMetadata, "size", trustCachedSize).get.toLong

          cloudFile.setOperationStateValue(OperationState.DOWNLOADING)

          /**
           * Executes the actual content retrieval and finalizes the operation.
           */
          def retrieveContentAndFinishOperations = {
            tryServeSingleChunkFromCacheIfSizeMatches(
              storageObjectMetadata,
              localDstDataChannel,
              startChunk,
              finishChunk,
              dataSizeAttribute,
              waitUntilDone)(cloudFile, cloudFileOperationStatus)
              .getOrElse(
                retrieveCloudFileContent(storageObjectMetadata,
                  localDstDataChannel,
                  startChunk,
                  finishChunk,
                  isStreaming,
                  dataSizeAttribute,
                  waitUntilDone,
                  isPreview)(cloudFile, cloudFileOperationStatus))

            if (waitUntilDone) {
              localDstDataChannel.close()

              cloudFile.setProgressValue(0)

              if (cloudFile.getOperationState.equals(OperationState.SHARING)) {
                cloudFile.setOperationStateValue(OperationState.DOWNLOADED)
              }
              else {
                cloudFile.setOperationStateValue(OperationState.NONE)
                account.getFileSystemHandler().attemptToSetAncestorsOperationStateAsNONE(cloudFile)
              }
            }
          }

          if (waitUntilDone) {
            retrieveContentAndFinishOperations

            cloudFileOperationStatus.setProgressValue(1.0)
            cloudFileOperationStatus.setOperationState(OperationState.DONE) #:: Stream.empty
          } else {
            Future {
              try {
                retrieveContentAndFinishOperations

                cloudFileOperationStatus.setProgressValue(1.0)
                cloudFileOperationStatus.setOperationState(OperationState.DONE)
              } catch {
                case e: Throwable =>
                  logger.error(s"retrieveVersionFuture.content ${cloudFile}", e);

                  localDstDataChannel.closeOnError()

                  account.userMsgs.add(s"retrieveVersionFuture.content: ${storageCloudObjectPathIncludingVersion} ${e.getMessage}")

                  cloudFile.setOperationStateValue(OperationState.ERROR)
                  cloudFileOperationStatus.setError(new RetrieveFileException(s"Retrieve file: ${storageCloudObjectPathIncludingVersion}", e)) #:: Stream.empty
              }
            }

            cloudFileOperationStatus #:: Stream.empty
          }
        } catch {
          case e: Throwable =>
            e match {
              case oc: OperationCanceledCloudObjectException =>
                logger.error(s"retrieveVersionFuture ${cloudFile}", oc.getMessage);
                localDstDataChannel.close()
              case other =>
                logger.error(s"retrieveVersionFuture ${cloudFile}", other);
                localDstDataChannel.closeOnError()
            }

            account.userMsgs.add(s"retrieveVersionFuture: ${storageCloudObjectPathIncludingVersion} ${e.getMessage}")

            cloudFile.setOperationStateValue(OperationState.ERROR)
            cloudFileOperationStatus.setError(new RetrieveFileException(s"Retrieve file: ${storageCloudObjectPathIncludingVersion}", e)) #:: Stream.empty
        }
      }

      createdFuture
    }

    val retrieveVersionsFutures = cloudFile.getVersions().size() match {
      case 1 => List(retrieveVersionFuture(cloudFile.getVersions.last))
      case _ =>
        val matchingVersionsBeforeTimestamp = for (
          stamp <- timestampsFilter.asScala;
          version <- cloudFile.getVersions.asScala;
          if version.getCreateTime <= stamp
        ) yield (version)

        matchingVersionsBeforeTimestamp.toList match {
          case Nil => List()
          case _ => List(retrieveVersionFuture(matchingVersionsBeforeTimestamp.toList.maxBy { _.getCreateTime }))
        }
    }

    // no version were matched
    if (retrieveVersionsFutures.size == 0) {
      cloudFile.setOperationStateValue(OperationState.NONE)
    }

    Future.reduce(retrieveVersionsFutures)((version1, version2) => version1 #::: version2)
  }

  /**
   * Asynchronously deletes specific versions (or all versions) of a cloud file.
   *
   * This is a multi-step destructive operation:
   * 1. Fetches metadata for the targeted versions.
   * 2. Deletes all underlying encrypted data chunks from the chunk storage layer.
   * 3. Deletes associated data attributes (access controls, sizes).
   * 4. Deletes the core metadata object from the catalog bucket.
   *
   * It carefully uses `ecFileDeleteOps` (a bounded thread pool) to prevent massive burst 
   * requests from rate-limiting the cloud provider APIs (e.g., Azure or AWS S3).
   *
   * @param timestampsFilter Specifies which historical versions to target for deletion.
   * @param cloudFile The domain model of the target cloud file.
   * @return A `Future` tracking the progress of the deletion process.
   */
  def deleteCloudFileVersionsFuture(timestampsFilter: java.util.List[java.lang.Long])(cloudFile: CloudFile): Future[Stream[CloudFileOperationStatus]] = {
    // Delete path must stay bounded; backgroundOps can be very wide and overload Azure LIST/DELETE.
    implicit val ec = OpsExecutors.ecFileDeleteOps

    /**
     * Asynchronously deletes a specific version of a cloud file.
     *
     * @param version the specific version to delete
     * @return a Future tracking the stream of cloud file operation statuses
     */
    def deleteVersionFuture(version: VersionAttributes): Future[Stream[CloudFileOperationStatus]] = {

      val createdFuture: Future[Stream[CloudFileOperationStatus]] = Future {
        val storageCloudObjectPathIncludingVersion: String = cloudFile.getCloudObjectPathIncludingVersion(version)
        var result: CloudFileOperationStatus =
          new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.DELETING).setOperationState(OperationState.DONE)

        val cloudFileOperationStatus = new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.DELETING)

        try {
          logger.info(s"deleteVersionFuture: ${storageCloudObjectPathIncludingVersion}")

          // First retrieve metadata (should always succeed for existing files)
          val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
          checkIfMetadataIsSignedByMyself(storageObjectMetadata)
          
          // Try to retrieve size attribute separately - may fail for orphaned checkpoints
          val cloudFileSizeAttributeTry = retrieveCloudFileDataAttribute(storageObjectMetadata, "size")
          val dataSizeAttribute = if (cloudFileSizeAttributeTry.isSuccess) {
            cloudFileSizeAttributeTry.get.toLong
          } else {
            logger.warn(s"Size attribute not found for ${storageCloudObjectPathIncludingVersion}, continuing with deletion")
            -1L  // Use -1 to indicate missing size
          }

          createCloudFileCheckpoint(storageObjectMetadata.getObjectPath)

          cloudFile.setOperationStateValue(OperationState.DELETING)

          // if its the data owner
          if (storageObjectMetadata.storageAttrs.dataOwner == account.MY_USER) {

            // delete file content (only if we have valid size information)
            if (dataSizeAttribute != -1L) {
              catching(classOf[Throwable]) either deleteCloudFileContent(storageObjectMetadata, dataSizeAttribute) match {
                case Left(s) => result = cloudFileOperationStatus.setError(s)
                case _ =>
              }
            } else {
              logger.info(s"Skipping chunk deletion for ${storageCloudObjectPathIncludingVersion} - size attribute missing (likely orphaned checkpoint)")
            }

            // send DELETE message to readers and delete data attributes
            catching(classOf[Throwable]) either {

              val cloudFileDataAttributeTry = retrieveCloudFileDataAttribute(storageObjectMetadata, "readers")

              if (cloudFileDataAttributeTry.isSuccess) {
                val dataReadersAttribute = cloudFileDataAttributeTry.get
                val readers = if (dataReadersAttribute.isEmpty) List.empty[String] else dataReadersAttribute.split("\n").toList

                // do not share the metadataSignature with readers
                storageObjectMetadata.metadataSignature = None

                for (reader <- readers if reader != account.MY_USER) {
                  // Route by the file's signed accessManager (stamped at upload), not the live
                  // isCustodianMode flag: custodian-managed files always revoke via the custodian
                  // regardless of the owner's current flag; ordinary files use direct peer DELETE.
                  if (storageObjectMetadata.storageAttrs.accessManager == account.CUSTODIAN_USER) {
                    sendAccessConfigChange(
                      storageObjectMetadata.storageAttrs.accessManager,
                      s"/${EVENT_REMOVE_READER}/from=${account.MY_USER}&reader=${reader}/",
                      storageCloudObjectPathIncludingVersion)
                  }
                  else {
                    deleteSharedCloudFileMetadataRequest(reader, storageObjectMetadata).get

                    // TODO: check if we need to encrypt the path
                    account.cloudMsgsHandler.sendMsgToUser(reader, s"${EVENT_DELETE}/${account.MY_USER}/${storageObjectMetadata.getObjectPath}")
                  }
                }
              }

              // send the request to delete metadata in custodian catalog
              deleteSharedCloudFileMetadataRequest(account.CUSTODIAN_USER, storageObjectMetadata).get

            } match {
              case Left(s) => {
                logger.error(s"deleteVersionFuture send DELETE message to readers and delete data attributes for ${cloudFile}", s)
                result = cloudFileOperationStatus.setError(s)
              }
              case _ =>
            }

            // For custodian-managed files the custodian deletes data attributes when it processes the DELETE event sent to it (not when a reader processes a DELETE sent to the reader).
            if (storageObjectMetadata.storageAttrs.accessManager != account.CUSTODIAN_USER) {
              catching(classOf[Throwable]) either {
                // Avoid per-file LIST round-trip in delete path: delete known attrs directly.
                deleteKnownCloudFileDataAttributes(storageObjectMetadata)
              } match {
                case Left(s) => result = cloudFileOperationStatus.setError(s)
                case _ =>
              }
            }

            // delete metadata
            catching(classOf[Throwable]) either deleteCloudFileMetadata(storageObjectMetadata.getObjectPath).get match {
              case Left(s) => result = cloudFileOperationStatus.setError(s)
              case _ =>
            }
          } else {
            sendAccessConfigChange(
              storageObjectMetadata.storageAttrs.accessManager,
              s"/${EVENT_REMOVE_READER}/from=${account.MY_USER}&reader=${account.MY_USER}/",
              storageCloudObjectPathIncludingVersion)

            catching(classOf[Throwable]) either deleteCloudFileMetadata(storageCloudObjectPathIncludingVersion).get match {
              case Left(s) => result = cloudFileOperationStatus.setError(s)
              case _ =>
            }
          }

          deleteCloudFileCheckpoint(storageObjectMetadata.getObjectPath)

          cloudFile.removeVersion(version)

          account.getFileSystemHandler().tryToDeleteFile(cloudFile)

          result #:: Stream.empty
        } catch {
          case e: Throwable =>
            logger.error(s"deleteVersionFuture ${cloudFile}", e);
            catching(classOf[Throwable]) either deleteCloudFileCheckpoint(storageCloudObjectPathIncludingVersion);
            cloudFile.setOperationStateValue(OperationState.ERROR)
            cloudFileOperationStatus.setError(new DeleteFileException(s"Delete file: ${storageCloudObjectPathIncludingVersion}", e)) #:: Stream.empty
        }
      }

      createdFuture
    }

    val deleteVersionsFutures = cloudFile.getVersions().size() match {
      case 1 => List(deleteVersionFuture(cloudFile.getVersions.last))
      case _ =>
        if (timestampsFilter == null) {
          // Bulk path: all versions of each listed cloud file.
          cloudFile.getVersions.asScala.toList.map(deleteVersionFuture)
        } else { // find the relevant versions
          for (
            stamp <- timestampsFilter.asScala;
            version <- cloudFile.getVersions.asScala;
            if version.getCreateTime == stamp
          ) yield (deleteVersionFuture(version))
        }
    }

    // no version were matched
    if (deleteVersionsFutures.size == 0) {
      cloudFile.setOperationStateValue(OperationState.NONE)
    }

    Future.reduce(deleteVersionsFutures)((version1, version2) => version1 #::: version2)
  }

  /**
   * Revokes access to specific file versions for designated reader users asynchronously.
   *
   * @param timestampsFilter a list of specific version timestamps to target for revocation
   * @param readersToRevoke an array of reader user IDs whose access should be revoked
   * @param cloudFile the target secure cloud file
   * @return a Future completing with a Stream of operation statuses
   */
  def revokeCloudFileVersionsFuture(timestampsFilter: java.util.List[java.lang.Long], readersToRevoke: Array[String])(cloudFile: CloudFile): Future[Stream[CloudFileOperationStatus]] = {
    implicit val ec = OpsExecutors.ecBackgroundOps

    /**
     * Asynchronously revokes access to a specific version of a cloud file.
     *
     * @param version the specific version to revoke access for
     * @return a Future tracking the stream of cloud file operation statuses
     */
    def revokeVersionFuture(version: VersionAttributes): Future[Stream[CloudFileOperationStatus]] = Future {
      val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(version)
      val cloudFileOperationStatus = new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.SHARING)

      try {
        val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get
        checkIfMetadataIsSignedByMyself(storageObjectMetadata)

        val isOwner = storageObjectMetadata.storageAttrs.dataOwner == account.MY_USER
        val isCustodian = account.MY_USER == account.CUSTODIAN_USER
        if (!isOwner && !isCustodian) {
          cloudFileOperationStatus.setError(new ShareFileException(storageCloudObjectPathIncludingVersion, new Exception("Only the data owner or the custodian can revoke reader access"))) #:: Stream.empty
        } else {
          val readersToRevokeSet = readersToRevoke.toSet
          // Revocation is an explicit owner request, not a conditional update based on a
          // potentially stale `readers` snapshot. A bulk share can still be creating that
          // attribute when revoke starts; relying on it would silently omit the recipient
          // DELETE and falsely report a successful revoke.
          storageObjectMetadata.metadataSignature = None
          for (reader <- readersToRevokeSet if reader != account.MY_USER) {
            // Ordinary owner-managed files: make the recipient-side removal durable and wake
            // that recipient. Custodian-managed files skip peer DELETE; the custodian drops
            // the reader's catalog when it processes REMOVE_READER below.
            if (storageObjectMetadata.storageAttrs.accessManager != account.CUSTODIAN_USER) {
              deleteSharedCloudFileMetadataRequest(reader, storageObjectMetadata).get
              account.cloudMsgsHandler.sendMsgToUser(
                reader,
                s"${EVENT_DELETE}/${account.MY_USER}/${storageObjectMetadata.getObjectPath}"
              )
            }

            // Route ACL mutation through the access-manager queue (owner or custodian).
            // For ordinary owner mode this serializes ADD_READER and REMOVE_READER for the
            // same object, rather than racing a direct `readers` attribute write with ADD_READER.
            sendAccessConfigChange(
              storageObjectMetadata.storageAttrs.accessManager,
              s"/${EVENT_REMOVE_READER}/from=${account.MY_USER}&reader=${reader}/",
              storageCloudObjectPathIncludingVersion)
          }
          cloudFileOperationStatus.setOperationState(OperationState.DONE) #:: Stream.empty
        }
      } catch {
        case e: Throwable =>
          logger.error(s"revokeVersionFuture ${storageCloudObjectPathIncludingVersion}", e)
          cloudFileOperationStatus.setError(new ShareFileException(storageCloudObjectPathIncludingVersion, e)) #:: Stream.empty
      }
    }

    val revokeVersionsFutures = if (cloudFile.getVersions().size() == 1) {
      List(revokeVersionFuture(cloudFile.getVersions.last))
    } else {
      if (timestampsFilter == null) {
        cloudFile.getVersions.asScala.toList.map(revokeVersionFuture)
      } else {
        (for (
          stamp <- timestampsFilter.asScala;
          version <- cloudFile.getVersions.asScala;
          if version.getCreateTime == stamp
        ) yield revokeVersionFuture(version)).toList
      }
    }

    if (revokeVersionsFutures.isEmpty) {
      cloudFile.setOperationStateValue(OperationState.NONE)
      Future.successful(Stream.empty[CloudFileOperationStatus])
    } else {
      Future.reduce(revokeVersionsFutures)((s1, s2) => s1 #::: s2)
    }
  }

  /**
   * Output dir can be unified as its per all the files
   *
   * // TODO: do it for many readers and not just for one
   *
   */
  def shareCloudFileVersionsFuture(timestampsFilter: java.util.List[java.lang.Long])(pair: (String, CloudFile)): Future[Stream[CloudFileOperationStatus]] = {
    val (toUserId, cloudFile) = pair

    implicit val ec = OpsExecutors.ecFastFileOps

    /**
     * Asynchronously shares a specific version of a cloud file.
     *
     * @param version the specific version to share
     * @return a Future tracking the stream of cloud file operation statuses
     */
    def shareVersionFuture(version: VersionAttributes): Future[Stream[CloudFileOperationStatus]] = {

      val createdFuture: Future[Stream[CloudFileOperationStatus]] = Future {

        val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(version)

        if (toUserId == null) {
          val cloudFileOperationStatus =
            new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.SHARING)
              .setError(new ShareFileException(storageCloudObjectPathIncludingVersion, new Exception(s"The user id is not provided")))

          cloudFileOperationStatus #:: Stream.empty
        }

        val previousOperationState = cloudFile.getOperationState

        cloudFile.setOperationStateValue(OperationState.SHARING)

        val cloudFileOperationStatus = new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.SHARING)

        try {
          val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get

          logger.info(s"shareVersionFuture: ${storageCloudObjectPathIncludingVersion} to ${toUserId}")

          checkIfMetadataIsSignedByMyself(storageObjectMetadata)

          if (storageObjectMetadata.storageAttrs.accessManager != account.CUSTODIAN_USER) { // send direct to the user
            shareCloudFileMetadataRequest(toUserId, storageObjectMetadata).get

            // notify access manager
            // do not share metadataSignature
            storageObjectMetadata.metadataSignature = None
          }

          // Send ADDREADER event to the file's access manager (custodian for custodian-managed files), even if its myself
          sendAccessConfigChange(
            storageObjectMetadata.storageAttrs.accessManager,
            s"/${EVENT_ADD_READER}/from=${account.MY_USER}&reader=${toUserId}/",
            storageObjectMetadata.getObjectPath)

          if (cloudFile.getOperationState.equals(OperationState.SHARING) &&
            (previousOperationState.equals(OperationState.DOWNLOADING) || previousOperationState.equals(OperationState.UPLOADING))  ) {

            cloudFile.setOperationStateValue(previousOperationState)
          }
          else {
            cloudFile.setOperationStateValue(OperationState.NONE)
            account.getFileSystemHandler().attemptToSetAncestorsOperationStateAsNONE(cloudFile)
          }

          cloudFileOperationStatus.setOperationState(OperationState.DONE) #:: Stream.empty
        } catch {
          case e: Throwable =>
            logger.error(s"shareVersionFuture ${cloudFile}", e);
            cloudFile.setOperationStateValue(OperationState.ERROR)
            cloudFileOperationStatus.setError(new ShareFileException(s"Share file: ${storageCloudObjectPathIncludingVersion}", e)) #:: Stream.empty
        }
      }

      createdFuture
    }

    val shareVersionsFutures = cloudFile.getVersions().size() match {
      case 1 => List(shareVersionFuture(cloudFile.getVersions.last))
      case _ =>
        if (timestampsFilter == null) {
          cloudFile.getVersions.asScala.toList.map(shareVersionFuture)
        } else { // find the relevant versions
          for (
            stamp <- timestampsFilter.asScala;
            version <- cloudFile.getVersions.asScala;
            if version.getCreateTime == stamp
          ) yield (shareVersionFuture(version))
        }
    }

    // no version were matched
    if (shareVersionsFutures.size == 0) {
      cloudFile.setOperationStateValue(OperationState.NONE)
    }

    Future.reduce(shareVersionsFutures)((version1, version2) => version1 #::: version2)
  }

  /**
   * Rename prefix for the CloudFile
   */
  def renameCloudFileVersionsFuture(oldPrefix: String, newPrefix: String, timestampsFilter: java.util.List[java.lang.Long])(cloudFile: CloudFile): Future[Stream[CloudFileOperationStatus]] = {
    implicit val ec = OpsExecutors.ecFastFileOps

    /**
     * Asynchronously renames a specific version of a cloud file.
     *
     * @param version the specific version to rename
     * @return a Future tracking the stream of cloud file operation statuses
     */
    def renameVersionFuture(version: VersionAttributes): Future[Stream[CloudFileOperationStatus]] = {

      val createdFuture: Future[Stream[CloudFileOperationStatus]] = Future {

        val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(version)

        if (account.MY_USER != version.getTag) {
          val cloudFileOperationStatus =
            new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.RENAMING)
              .setError(new RenameFileException(storageCloudObjectPathIncludingVersion, new Exception(s"${account.MY_USER} is not an access manager")))

          cloudFileOperationStatus #:: Stream.empty
        }

        cloudFile.setOperationStateValue(OperationState.RENAMING)

        val cloudFileOperationStatus =
          new CloudFileOperationStatus(storageCloudObjectPathIncludingVersion, OperationState.RENAMING)

        // nothing to rename
        if (oldPrefix == newPrefix) {
          cloudFileOperationStatus #:: Stream.empty
        }

        try {
          val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get

          logger.info(s"renameVersionFuture: ${storageCloudObjectPathIncludingVersion} rename ${oldPrefix} to ${newPrefix}")

          checkIfMetadataIsSignedByMyself(storageObjectMetadata)

          val newPath =
            if (oldPrefix == "") {
              FilenameUtils.separatorsToUnix(cloudFile.getPath())
                .replaceFirst(FilenameUtils.separatorsToUnix(oldPrefix), FilenameUtils.separatorsToUnix(newPrefix) + "/")
            } else if (newPrefix == "") {
              FilenameUtils.separatorsToUnix(cloudFile.getPath())
                .replaceFirst(FilenameUtils.separatorsToUnix(oldPrefix) + "/", FilenameUtils.separatorsToUnix(newPrefix))
            } else {
              FilenameUtils.separatorsToUnix(cloudFile.getPath())
                .replaceFirst(FilenameUtils.separatorsToUnix(oldPrefix), FilenameUtils.separatorsToUnix(newPrefix))
            }

          // insert new cloudFile to the multimap
          var newCloudFile = new CloudFile(newPath, cloudFile.isDirectory)

          logger.info(s"renameVersionFuture: rename ${cloudFile.getPath()} to ${newCloudFile}")

          version.setCloudFile(newCloudFile)
          newCloudFile.addVersion(version)

          account.getFileSystemHandler().addCloudFileInUploadingProcess(newCloudFile)

          val newStorageObjectMetadata = StorageObjectMetadata(storageObjectMetadata.metadataVersion, storageObjectMetadata.metadataSignature)

          newStorageObjectMetadata.fileAttrs = FileAttrs(newPath,
                                                      storageObjectMetadata.fileAttrs.tag,
                                                      storageObjectMetadata.fileAttrs.createdTime.asInstanceOf[java.lang.Long],
                                                      storageObjectMetadata.fileAttrs.version)

          newStorageObjectMetadata.encryptionAttrs = storageObjectMetadata.encryptionAttrs
          // Own locator so the custodian DELETE of the old catalog record does not
          // prefix-delete this object's size/readers. Chunks stay on dataLocator.
          val newLocator =
            if (account.generateUUIDForDataAttributesPath) {
              val r = scala.util.Random
              LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                LOCATOR_FRAGMENT_FORMAT.format(r.nextInt(9999)) + "/" +
                java.util.UUID.randomUUID.toString
            } else newStorageObjectMetadata.getObjectPath
          newStorageObjectMetadata.storageAttrs =
            storageObjectMetadata.storageAttrs.copy(dataAttributesLocator = newLocator)
          newStorageObjectMetadata.metadataSignature =
            Some(signString(metadataAuthorityString(account.MY_USER, newStorageObjectMetadata)))

          copyKnownCloudFileDataAttributes(storageObjectMetadata, newStorageObjectMetadata)

          // store new object metadata
          storeCloudFileMetadata(newStorageObjectMetadata).get
          // Rename metadata is durable — drop uploading marker (keeps cloudFilesInUse).
          account.getFileSystemHandler().removeCloudFileInUploadingProcess(newCloudFile)

          // send SHARE messages to readers

          // do not share the metadataSignature with readers
          newStorageObjectMetadata.metadataSignature = None
          storageObjectMetadata.metadataSignature = None

          val dataReadersAttribute = retrieveCloudFileDataAttribute(storageObjectMetadata, "readers").get
          val readers = if (dataReadersAttribute.isEmpty) List.empty[String] else dataReadersAttribute.split("\n").toList

          for (reader <- readers filter (_ != account.MY_USER)) {
            if (storageObjectMetadata.storageAttrs.accessManager == account.CUSTODIAN_USER) {

              sendAccessConfigChange(
                storageObjectMetadata.storageAttrs.accessManager,
                s"/${EVENT_ADD_READER}/from=${account.MY_USER}&reader=${reader}/",
                newStorageObjectMetadata.getObjectPath)

              sendAccessConfigChange(
                storageObjectMetadata.storageAttrs.accessManager,
                s"/${EVENT_REMOVE_READER}/from=${account.MY_USER}&reader=${reader}/",
                storageCloudObjectPathIncludingVersion)
            }
            else {
              shareCloudFileMetadataRequest(reader, newStorageObjectMetadata).get
              deleteSharedCloudFileMetadataRequest(reader, storageObjectMetadata).get

              // TODO: check if we need to encrypt the path
              account.cloudMsgsHandler.sendMsgToUser(reader, s"${EVENT_DELETE}/${account.MY_USER}/${storageObjectMetadata.getObjectPath}")
            }
          }

          // change the name at the custodian catalog
          shareCloudFileMetadataRequest(account.CUSTODIAN_USER, newStorageObjectMetadata).get
          deleteSharedCloudFileMetadataRequest(account.CUSTODIAN_USER, storageObjectMetadata).get

          // delete old object metadata
          deleteCloudFileMetadata(storageObjectMetadata.getObjectPath).get

          // try to delete version and possibly CloudFile
          cloudFile.removeVersion(version)
          account.getFileSystemHandler().tryToDeleteFile(cloudFile)

          cloudFileOperationStatus
            .setOperationState(OperationState.DONE)
            .setDetails(s"${storageCloudObjectPathIncludingVersion.replaceFirst(oldPrefix, newPrefix)}") #:: Stream.empty
        } catch {
          case e: Throwable =>
            logger.error(s"renameVersionFuture ${cloudFile}", e);
            cloudFile.setOperationStateValue(OperationState.ERROR)
            cloudFileOperationStatus.setError(new RenameFileException(s"Rename file: ${storageCloudObjectPathIncludingVersion}", e)) #:: Stream.empty
        }
      }

      createdFuture
    }

    val renameVersionsFutures = cloudFile.getVersions().size() match {
      case 1 => List(renameVersionFuture(cloudFile.getVersions.last))
      case _ => // find the relevant versions
        for (
          stamp <- timestampsFilter.asScala;
          version <- cloudFile.getVersions.asScala;
          if version.getCreateTime == stamp
        ) yield (renameVersionFuture(version))
    }

    // no version were matched
    if (renameVersionsFutures.size == 0) {
      cloudFile.setOperationStateValue(OperationState.NONE)
    }

    Future.reduce(renameVersionsFutures)((version1, version2) => version1 #::: version2)
  }

  override def storeUserdata(userName: String, metadata: UserMetadata): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps

    logger.info("storeUserdata: " + account.USERS_BUCKET + " all " + userName)
    // Use the configured printer to drop null values
    // Must match loadUser (new String(array, "UTF-8")).
    account.cloudObjectHandler.storeObjectToCloud(metadata.asJson.printWith(noneDropper).getBytes("UTF-8"), account.USERS_BUCKET, "all", userName)
  }

  override def retrieveUserdata(userName: String): UserMetadata = {
    var foundMetadata = usersMetadata.get(userName)

    if (foundMetadata == null) {
      // wait a bit (maybe it's still preloading), otherwise start retrieving the object from cloud
      Thread.sleep(300)

      loadUser(userName)

      // try again
      foundMetadata = usersMetadata.get(userName)
    }

    if (foundMetadata == null) {
      throw new RuntimeException(s"Cannot find metadata for user: {userName}")
    }

    foundMetadata
  }

  /**
   * Pre-fetches and loads metadata for all registered users in parallel, verifying current user identity.
   */
  def preloadUsers(): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps

    val allUsers = listUsers().asScala.toStream

    // Use a parallel stream for all users except MY_USER
    allUsers.par.foreach { userName =>
      if (userName == account.MY_USER) {
        loadUser(userName) // Synchronous for MY_USER
        verifyUserIdentity()
      } else {
        Future(loadUser(userName)) // Asynchronous for others
      }
    }

    if (account.MY_USER == account.CUSTODIAN_USER) {
      preloadUserAttributesForCustodian()
    }
  }

  private val USER_ATTRIBUTES_FOR_CUSTODIAN_PREFIX = account.PRIVATE_DATA_DIRECTORY + "user-attributes-for-custodian/"

  /**
   * Pre-fetches and caches delegation/custodian attributes for all users in the catalog (custodian mode only).
   */
  def preloadUserAttributesForCustodian(): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps
    if (account.MY_USER != account.CUSTODIAN_USER) return
    val it = account.cloudObjectHandler.listObjectsAtCloud(account.CATALOG_BUCKET, account.MY_USER, USER_ATTRIBUTES_FOR_CUSTODIAN_PREFIX, true).get
    it.asScala.toStream.par.foreach { objectKey =>
      val userName = objectKey.replace(USER_ATTRIBUTES_FOR_CUSTODIAN_PREFIX, "")
      Future(loadUserAttributesForCustodian(userName))
    }
  }

  private def loadUserAttributesForCustodian(userName: String): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps
    val objectKey = USER_ATTRIBUTES_FOR_CUSTODIAN_PREFIX + userName
    val array = account.cloudObjectHandler.retrieveObjectFromCloud(account.CATALOG_BUCKET, account.MY_USER, objectKey).get
    val decoded = decode[UserAttributesForCustodian](new String(array, "UTF-8")).toOption.get
    val decrypted = if (account.userProps.getProperty("metadata-encryption") != null && account.userProps.getProperty("metadata-encryption") != "HSM") {
      UserAttributesForCustodian(
        decoded.userName,
        if (decoded.catalogSas.isDefined) Some(account.decryptPropertyValue(decoded.catalogSas.get)) else None,
        if (decoded.dataattributesSas.isDefined) Some(account.decryptPropertyValue(decoded.dataattributesSas.get)) else None
      )
    } else decoded
    userAttributesForCustodian.put(userName, decrypted)
    logger.info(s"preloaded UserAttributesForCustodian for user: $userName")
  }

  private def loadUser(userName: String): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps

    // retrieve non-encrypted data and add it to the map
    val userMetadata = {
      val array = account.cloudObjectHandler.retrieveObjectFromCloud(account.USERS_BUCKET, "all", userName).get
      decode[UserMetadata](new String(array, "UTF-8")).toOption.get
    }

    // If multicloud and data provider is azure-secure, fetch missing SAS tokens from data cloud
    if (account.ACCOUNT_TYPE == "multicloud-secure" && account.getProperty("multicloud.data.provider") == "azure-secure") {
      account.cloudObjectHandler match {
        case mc: com.altastata.cloud.multicloud.MultiCloudObjectHandler =>
          try {
            val dataArrayTry = mc.getDataHandler.retrieveObjectFromCloud(account.USERS_BUCKET, "all", userName)
            if (dataArrayTry.isSuccess) {
              val dataMetadata = decode[UserMetadata](new String(dataArrayTry.get, "UTF-8")).toOption.get
              userMetadata.readOnlyChunksSAS = dataMetadata.readOnlyChunksSAS
              userMetadata.readOnlyDataAttributesSAS = dataMetadata.readOnlyDataAttributesSAS
              logger.info(s"Loaded supplementary SAS tokens from azure-secure data cloud for ${userName}")
            }
          } catch {
            case e: Exception => logger.warn(s"Failed to load supplementary user data from azure-secure data cloud for $userName", e)
          }
        case _ => 
      }
    }

    usersMetadata.put(userName, userMetadata)

    logger.info(s"preloaded userinfo: ${userName} for ${account.MY_USER}")
  }

  override def shareUserdataWithCustodian(userMetadata: UserMetadata): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps

    // Self-enrollment: user signs with own key (custodian verifies against payload key).
    // Admin is not a datalake user: sign with the org CA key (org-ca-private.key); the
    // custodian verifies against org-ca.pem, which every account already trusts.
    val authorityString = userdataAuthorityString(account.MY_USER, userMetadata)
    val signature =
      if (account.MY_USER == "admin") signStringWithOrgCa(authorityString)
      else signString(authorityString)
    userMetadata.authorityAttrs = Some(AuthorityAttributes(account.MY_USER, signature))
    val userMetadataSerialized = userMetadata.asJson.printWith(noneDropper).getBytes("UTF-8")

    account.cloudObjectHandler.storeObjectToCloud(userMetadataSerialized, account.CHANGES_BUCKET, account.CUSTODIAN_USER,
      account.QUEUE_NAME + "/" + CHANGE_TIME_FORMAT.format(System.currentTimeMillis) + "/ADD_USERDATA/from=" + account.MY_USER + "/user=" + userMetadata.userName)

    account.cloudMsgsHandler.sendMsgToUser(account.CUSTODIAN_USER, new String(userMetadataSerialized, "UTF-8"))

    logger.info(s"Shared new user: ${userMetadata.redactedSummary}")
  }

  /**
   * Sign a string with the org CA private key (org-ca-private.key), SHA256withRSA, Base64.
   * Used by admin for ADD_USERDATA: the custodian verifies against org-ca.pem (getCertTrustPublicKey).
   */
  private def signStringWithOrgCa(str: String): String = {
    val privateKey = getKeyPairFromRSAPrivateKey(new StringReader(loadOrgCaPrivateKeyPem()), null).getPrivate
    val signature = java.security.Signature.getInstance("SHA256withRSA")
    signature.initSign(privateKey)
    signature.update(str.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(signature.sign())
  }

  /** Serialize UserAttributesForCustodian to JSON (same format as used when storing in catalog). Callable from admin (e.g. Azure) for direct upload. */
  def serializeUserAttributesForCustodian(attrs: UserAttributesForCustodian): String =
    attrs.asJson.printWith(noneDropper)

  override def handleUserAttributesForCustodian(userAttributesSerialized: String): UserAttributesForCustodian = {
    decode[UserAttributesForCustodian](userAttributesSerialized).toOption.get
  }

  override def storeUserAttributesForCustodianInCatalog(userName: String, attrsSerialized: String): Unit = {
    implicit val ec = OpsExecutors.ecFastFileOps
    val decoded = decode[UserAttributesForCustodian](attrsSerialized).toOption.get
    val decrypted = if (account.userProps.getProperty("metadata-encryption") != null && account.userProps.getProperty("metadata-encryption") != "HSM") {
      UserAttributesForCustodian(
        decoded.userName,
        if (decoded.catalogSas.isDefined) Some(account.decryptPropertyValue(decoded.catalogSas.get)) else None,
        if (decoded.dataattributesSas.isDefined) Some(account.decryptPropertyValue(decoded.dataattributesSas.get)) else None
      )
    } else decoded
    val objectKey = USER_ATTRIBUTES_FOR_CUSTODIAN_PREFIX + userName
    account.cloudObjectHandler.storeObjectToCloud(attrsSerialized.getBytes("UTF-8"), account.CATALOG_BUCKET, account.MY_USER, objectKey)
    userAttributesForCustodian.put(userName, decrypted)
    logger.info(s"Stored UserAttributesForCustodian for user: $userName in custodian catalog")
  }

  override def putUserAttributesForCustodianInCache(userName: String, attrs: UserAttributesForCustodian): Unit = {
    userAttributesForCustodian.put(userName, attrs)
  }

  override def retrieveUserAttributesForCustodian(userName: String): Option[UserAttributesForCustodian] = {
    var found = userAttributesForCustodian.get(userName)
    if (found == null && account.MY_USER == account.CUSTODIAN_USER) {
      Thread.sleep(300)
      try {
        loadUserAttributesForCustodian(userName)
        found = userAttributesForCustodian.get(userName)
      } catch { case _: Throwable => }
    }
    Option(found)
  }

  override def handleUserMetadata(userMetadataSerialized: String, certificateSignUrl: String): UserMetadata = {
    val userMetadata = decode[UserMetadata](userMetadataSerialized).toOption.get
    // Licensed (license.jwt): AsymmetricKeysGenerator + org-ca-private.key (custodian/admin).
    // Community: AltaStata cloud POST /sign.
    val useLocalIssuer = account.getOrgLicense.isDefined

    if (userMetadata.publicKey != None) {
      logger.info(s"obtainCertificate RSA before (local=$useLocalIssuer): ${userMetadata.redactedSummary}")

      val certificate =
        if (useLocalIssuer) {
          signUserCertRsaLocal(
            userMetadata.organization,
            userMetadata.userName,
            userMetadata.publicKey.getOrElse("")
          )
        } else {
          requireCloudSignUrl(certificateSignUrl)
          val jsonRequest = new JSONObject
          jsonRequest.put("organization", userMetadata.organization)
          jsonRequest.put("userName", userMetadata.userName)
          jsonRequest.put("email", userMetadata.emailAddress.getOrElse(""))
          jsonRequest.put("publicKeyPEM", userMetadata.publicKey.getOrElse(""))
          CurlEmulator.getCertificate(certificateSignUrl, jsonRequest).get("certificate").toString
        }

      userMetadata.publicKeyCert = Some(certificate)
      userMetadata.publicKey = None

      logger.info(s"obtainCertificate RSA after: ${userMetadata.redactedSummary}")
    }

    if (userMetadata.publicKyberKeyPEM != None && userMetadata.publicDilithiumKeyPEM != None) {
      logger.info(s"obtainCertificate PQC before (local=$useLocalIssuer): ${userMetadata.redactedSummary}")

      val certificate =
        if (useLocalIssuer) {
          signUserCertPqcLocal(
            userMetadata.organization,
            userMetadata.userName,
            userMetadata.publicKyberKeyPEM.getOrElse(""),
            userMetadata.publicDilithiumKeyPEM.getOrElse("")
          )
        } else {
          requireCloudSignUrl(certificateSignUrl)
          val jsonRequest = new JSONObject
          jsonRequest.put("organization", userMetadata.organization)
          jsonRequest.put("userName", userMetadata.userName)
          jsonRequest.put("email", userMetadata.emailAddress.getOrElse(""))
          jsonRequest.put("publicKeyKyberPEM", userMetadata.publicKyberKeyPEM.getOrElse(""))
          jsonRequest.put("publicKeyDilithiumPEM", userMetadata.publicDilithiumKeyPEM.getOrElse(""))
          CurlEmulator.getCertificate(certificateSignUrl, jsonRequest).get("certificate").toString
        }

      userMetadata.publicPQCKeyCertPEM = Some(certificate)
      userMetadata.publicKyberKeyPEM = None
      userMetadata.publicDilithiumKeyPEM = None

      logger.info(s"obtainCertificate PQC after: ${userMetadata.redactedSummary}")
    }

    if (userMetadata.metadataEncryption.get == "HSM") {
      val (hsmKeyId, hsmSignKeyId) = account.myCloudHSMHandler.createHSMKeysForUser(userMetadata.userName, userMetadata.userType)

      userMetadata.hsmKeyId = Some(hsmKeyId)
      userMetadata.hsmSignKeyId = Some(hsmSignKeyId)
    }

    userMetadata
  }

  private def signUserCertRsaLocal(organization: String, userName: String, publicKeyPem: String): String = {
    val prefix = CertSubject.accountContainerPrefix(organization)
    val subject = CertSubject.forUser(prefix, userName)
    val pemMap = createPEMCertificateForRSAPublicKeyReader(
      new StringReader(publicKeyPem),
      new StringReader(loadOrgCaPrivateKeyPem()),
      subject,
      localCertDurationYears,
      CertSubject.issuerForDatalake(organization)
    )
    requireCertificate(pemMap, userName)
  }

  private def signUserCertPqcLocal(
      organization: String,
      userName: String,
      publicKeyKyberPem: String,
      publicKeyDilithiumPem: String
  ): String = {
    val prefix = CertSubject.accountContainerPrefix(organization)
    val subject = CertSubject.forUser(prefix, userName)
    val pemMap = createPEMCertificateForPQCPEMS(
      publicKeyKyberPem,
      publicKeyDilithiumPem,
      new StringReader(loadOrgCaPrivateKeyPem()),
      subject,
      localCertDurationYears,
      CertSubject.issuerForDatalake(organization)
    )
    requireCertificate(pemMap, userName)
  }

  private def requireCertificate(pemMap: java.util.Map[String, String], userName: String): String = {
    val certificate = if (pemMap != null) pemMap.get("certificate") else null
    if (certificate == null || certificate.trim.isEmpty) {
      throw new SecurityException(s"Local org-CA signing returned no certificate for $userName")
    }
    certificate
  }

  private def loadOrgCaPrivateKeyPem(): String = {
    val configured = Option(account.getProperty("org-ca-private-key-path")).map(_.trim).filter(_.nonEmpty)
    val path = configured.getOrElse {
      val dir = Option(account.getAccountDir).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw new SecurityException("Licensed local cert signing requires accountDir or org-ca-private-key-path")
      }
      new java.io.File(dir, "org-ca-private.key").getAbsolutePath
    }
    val file = new java.io.File(path)
    if (!file.isFile) {
      throw new SecurityException(
        s"Licensed org requires local CA private key at $path " +
          "(AsymmetricKeysGenerator.createPEMCertificate* with org-ca-private.key; not cloud /sign)"
      )
    }
    new String(java.nio.file.Files.readAllBytes(file.toPath), java.nio.charset.StandardCharsets.UTF_8).trim
  }

  private def localCertDurationYears: Int =
    Option(account.getProperty("cert-duration-years"))
      .flatMap(s => scala.util.Try(s.trim.toInt).toOption)
      .filter(_ > 0)
      .getOrElse(2)

  private def requireCloudSignUrl(certificateSignUrl: String): Unit = {
    if (certificateSignUrl == null || certificateSignUrl.trim.isEmpty) {
      throw new SecurityException(
        "Community accounts require sign-cert-url for AltaStata cloud /sign " +
          "(licensed accounts use org-ca-private.key via AsymmetricKeysGenerator)"
      )
    }
  }
}

