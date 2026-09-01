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

import com.altastata.utils.Account
import com.altastata.utils.Constants._

import scala.concurrent.ExecutionContext
import java.util.Collections
import java.util.HashMap
import scala.collection.JavaConverters._
import com.altastata.filesystem.common.FileSystemHandler
import com.altastata.api.AltaStataEvent
import com.altastata.filesystem.{AuthorityAttributes, RetrieveCloudObjectException, UserMetadata}

import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import com.altastata.filesystem.common.CloudFile

import scala.concurrent.duration.Duration
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.slf4j.LoggerFactory

import scala.language.postfixOps
import scala.util.Try

/**
 * Signals that a change could not be completed because of a transient cloud error
 * (e.g. eventual-consistency 404, read timeout) rather than a permanent, "poison" one.
 * Such a change must be kept in the queue and retried on a later poll instead of being
 * dropped without updating the catalog.
 */
class RetryableChangeException(message: String, cause: Throwable) extends RuntimeException(message, cause)

class SecureCloudEventProcessor(implicit account: Account) extends SecureCloudOperations {

  private val logger = LoggerFactory.getLogger(getClass)

  val CONCURRENT_EVENTS_THREADS_POOL_SIZE = 400

  // `readers` (the ACL) is read straight from the data owner's cloud on every share/revoke.
  // Google/S3 can return a transient 404 or time out even though the attribute exists, which
  // used to make an ADD_READER/REMOVE_READER silently no-op. Retry a few times with linear
  // backoff before treating the read as failed.
  val READERS_READ_MAX_ATTEMPTS = 4
  val READERS_READ_BACKOFF_MS = 500L

  val filesAccessManagersChangesInProcess = Collections.synchronizedMap(new HashMap[String, Object])

  /**
   * Retrieves or initializes a synchronization/lock object for a specific cloud storage object path.
   *
   * @param storageObjectMetadata the metadata of the target cloud storage object
   * @return the associated synchronization lock object
   */
  def findLockForObjectPath(storageObjectMetadata: StorageObjectMetadata): Object = {
    filesAccessManagersChangesInProcess.asScala.getOrElseUpdate(storageObjectMetadata.getObjectPath, new Object)
  }

  /**
   * Reads the "readers" ACL attribute, retrying transient cloud failures with linear backoff.
   *
   * The attribute lives in the data owner's data-properties bucket and is always fetched fresh
   * (no cache). Eventually-consistent stores can briefly 404 a freshly written object or time
   * out; a single failure here must not be mistaken for "no such file", otherwise a share is
   * dropped or, worse, a revoke leaves stale access behind.
   *
   * @param storageObjectMetadata the metadata of the target cloud file
   * @param ec the implicit execution context
   * @return Success(readers) once the read succeeds, or the last Failure after all attempts
   */
  def retrieveReadersWithRetry(storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext): Try[String] = {
    var attempt = 1
    var result = retrieveCloudFileDataAttribute(storageObjectMetadata, "readers")
    while (result.isFailure && attempt < READERS_READ_MAX_ATTEMPTS) {
      logger.warn(s"retrieveReadersWithRetry: attempt ${attempt}/${READERS_READ_MAX_ATTEMPTS} failed for " +
        s"${storageObjectMetadata.getObjectPath}; retrying", result.failed.get)
      Thread.sleep(READERS_READ_BACKOFF_MS * attempt)
      attempt += 1
      result = retrieveCloudFileDataAttribute(storageObjectMetadata, "readers")
    }
    result
  }

  /**
   * Downloads and validates the digital signature of authorization attributes for a given file event path.
   *
   * @param filePath the path of the target authority attributes file
   * @param from the expected author/originator of the change
   * @param ec the implicit execution context
   * @throws SecurityException if verification fails
   */
  def checkIfAuthorityValid(filePath: String, from: String)(implicit ec: ExecutionContext) = {
    val serializedAuthorityAttributes = account.cloudObjectHandler.retrieveObjectFromCloud(account.CHANGES_BUCKET, account.MY_USER, filePath).get
    val authorityAttributes = decode[AuthorityAttributes](new String(serializedAuthorityAttributes, "UTF-8")).toOption.get

    if (authorityAttributes.signature == null) {
      throw new SecurityException(s"No authorityAttributes signature found for ${filePath}")
    } else {
      val verified = verifySignature(account.fileSystemModel.retrieveUserdata(authorityAttributes.user), authorityAttributes.signature, filePath)
      if (!(verified && authorityAttributes.user == from)) {
        throw new SecurityException(s"checkIfAuthorityValid: AuthorityAttributes ${filePath} signature is not verified or ${authorityAttributes.user} != ${from}. " +
          s"The proposed change and is going to be deleted without affecting the catalog.")
      }
    }
  }

  /**
   * Validates the authority attributes directly embedded within the storage object metadata, checking signatures.
   *
   * @param storageObjectMetadata the metadata containing authority attributes
   * @param from the expected author/originator of the change
   * @param ec the implicit execution context
   * @return the verified username of the authority
   * @throws SecurityException if verification fails
   */
  def checkIfAuthorityValid(storageObjectMetadata: StorageObjectMetadata, from: String)(implicit ec: ExecutionContext): String = {

    if (storageObjectMetadata.authorityAttrs == null) {
      throw new SecurityException(s"No authorityAttrs found for file ${storageObjectMetadata.getObjectPath}")
    } else {
      // check the sharedBy user signature to make sure that this change is authorized
      val sentByUserMetadata = account.fileSystemModel.retrieveUserdata(storageObjectMetadata.authorityAttrs.get.user)

      val toCompare = storageObjectMetadata.authorityAttrs.get.user + "/" + storageObjectMetadata.storageAttrs.dataLocator
      val verified = verifySignature(sentByUserMetadata, storageObjectMetadata.authorityAttrs.get.signature, toCompare)

      if (!(verified && storageObjectMetadata.authorityAttrs.get.user == from)) {
        throw new SecurityException(s"checkIfAuthorityValid AuthorityAttributes shared by ${from} signature is not like ${toCompare} or ${storageObjectMetadata.authorityAttrs.get.user} != ${from}. " +
          s"The proposed change and is going to be deleted without affecting the catalog.")
      }
    }

    return storageObjectMetadata.authorityAttrs.get.user
  }

  /**
   * Same check as sharing AuthorityAttributes, for ADD_USERDATA.
   * Signed string: user/userName/keyMaterial.
   * Self-enrollment verifies against the public key in this UserMetadata;
   * admin is not a datalake user, so its signature is verified against the org CA
   * trust anchor (org-ca.pem / getCertTrustPublicKey), which every account already holds.
   */
  def checkIfUserdataAuthorityValid(userMetadata: UserMetadata, from: String): Unit = {
    if (userMetadata.authorityAttrs.isEmpty) {
      throw new SecurityException(s"No authorityAttrs found for ADD_USERDATA user ${userMetadata.userName}")
    } else {
      val authorityAttributes = userMetadata.authorityAttrs.get
      val toCompare = userdataAuthorityString(authorityAttributes.user, userMetadata)

      val verified =
        if (authorityAttributes.user == userMetadata.userName) {
          // Self-enrollment: verify against the public key carried in this UserMetadata.
          verifySignature(userMetadata, authorityAttributes.signature, toCompare)
        } else {
          // Admin: signed with org-ca-private.key, verified against org-ca.pem trust anchor.
          verifySignatureWithRSA(
            account.getCertTrustPublicKey,
            toCompare.getBytes("UTF-8"),
            java.util.Base64.getDecoder.decode(authorityAttributes.signature)
          )
        }

      if (!(verified && authorityAttributes.user == from)) {
        throw new SecurityException(s"checkIfUserdataAuthorityValid AuthorityAttributes shared by ${from} signature is not like ${toCompare} or ${authorityAttributes.user} != ${from}. " +
          s"The proposed change and is going to be deleted without affecting the catalog.")
      }
    }
  }

  /**
   * Adds a new reader user to the Access Control List (readers data attribute) of a secure cloud file.
   *
   * @param changeObjectPath the path representing the change event
   * @param from the originator of the change event
   * @param reader the username of the reader to add
   * @param storageFilePathIncludingVersion the target cloud file storage path
   * @param ec the implicit execution context
   */
  def addReader(changeObjectPath: String, from: String, reader: String, storageFilePathIncludingVersion: String)(implicit ec: ExecutionContext): Unit = {
    val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, decryptObjectPathIfNeeded(storageFilePathIncludingVersion)).get

    findLockForObjectPath(storageObjectMetadata).synchronized {
      val cloudFileDataAttributeTry = retrieveReadersWithRetry(storageObjectMetadata)

      if (cloudFileDataAttributeTry.isSuccess) {
        val dataReadersAttribute = cloudFileDataAttributeTry.get
        val readers = if (dataReadersAttribute.isEmpty) List.empty[String] else dataReadersAttribute.split("\n").toList

        // If I am custodian and this is custodian mode, I just verify the graph and send metadata to the user
        if (account.isCustodianMode && account.MY_USER == account.CUSTODIAN_USER) {
          // TODO: check that the user can be added based on the graph, otherwise delete the change and exit

          logger.info(s"Custodian auto-accept ADDREADER: from=${from} reader=${reader} file=${storageObjectMetadata.getObjectPath}")
          checkIfMetadataIsSignedByMyself(storageObjectMetadata)

          shareCloudFileMetadataRequest(reader, storageObjectMetadata).get
        }

        // update the dataReadersAttribute
        if (!readers.contains(reader)) {
          val updatedReaders = (readers :+ reader).sorted.mkString("\n")

          storeCloudFileDataAttribute(storageObjectMetadata, updatedReaders, "readers")
        } else {
          logger.warn(s"Change ${EVENT_ADD_READER}: ${reader} already exist within the ACL for a file ${changeObjectPath}.")
          account.userMsgs.add(s"Change ${EVENT_ADD_READER}: ${reader} already exist within the ACL for a file ${changeObjectPath}.")
        }
      } else {
        // We already retrieved this file's metadata above, so a failing `readers` read is a
        // transient cloud error, not a missing file. Keep the change and retry it later rather
        // than silently dropping the share.
        val msg = s"Change ${EVENT_ADD_READER}: reading readers attribute failed for ${storageObjectMetadata.getObjectPath} " +
          s"after ${READERS_READ_MAX_ATTEMPTS} attempts; the change was kept and will be retried."
        logger.warn(msg, cloudFileDataAttributeTry.failed.get)
        account.userMsgs.add(msg)
        throw new RetryableChangeException(msg, cloudFileDataAttributeTry.failed.get)
      }
    }
  }

  /**
   * Removes a reader user from the Access Control List (readers data attribute) of a secure cloud file.
   *
   * @param changeObjectPath the path representing the change event
   * @param from the originator of the change event
   * @param reader the username of the reader to remove
   * @param storageFilePathIncludingVersion the target cloud file storage path
   * @param ec the implicit execution context
   */
  def removeReader(changeObjectPath: String, from: String, reader: String, storageFilePathIncludingVersion: String)(implicit ec: ExecutionContext): Unit = {
    val storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, decryptObjectPathIfNeeded(storageFilePathIncludingVersion)).get

    findLockForObjectPath(storageObjectMetadata).synchronized {
      // Revocation must never depend on being able to read the owner's `readers` attribute:
      // the reader's catalog copy is what actually grants visibility, so drop it first and
      // unconditionally whenever I am the custodian acting for the owner. A transient failure
      // reading `readers` below must not be able to leave a revoked reader with lingering access.
      // deleteCloudFileMetadataFromUserCatalog is idempotent, so replaying the change is safe.
      if (account.isCustodianMode && account.MY_USER == account.CUSTODIAN_USER &&
          (from == storageObjectMetadata.storageAttrs.dataOwner || from == account.CUSTODIAN_USER)) {
        val recipientMetadataDeletion =
          deleteCloudFileMetadataFromUserCatalog(reader, storageObjectMetadata.getObjectPath)
        if (recipientMetadataDeletion.isFailure) {
          val msg = s"Change ${EVENT_REMOVE_READER}: failed to delete ${reader}'s catalog copy of " +
            s"${storageObjectMetadata.getObjectPath}; the change was kept and will be retried."
          logger.warn(msg, recipientMetadataDeletion.failed.get)
          account.userMsgs.add(msg)
          throw new RetryableChangeException(msg, recipientMetadataDeletion.failed.get)
        }
      }

      val cloudFileDataAttributeTry = retrieveReadersWithRetry(storageObjectMetadata)

      if (cloudFileDataAttributeTry.isSuccess) {
        val dataReadersAttribute = cloudFileDataAttributeTry.get
        val readers = if (dataReadersAttribute.isEmpty) List.empty[String] else dataReadersAttribute.split("\n").toList

        if (readers.contains(reader)) {
          val updatedReaders = readers.filter(_ != reader).sorted.mkString("\n")

          storeCloudFileDataAttribute(storageObjectMetadata, updatedReaders, "readers")
        } else {
          logger.warn(s"Change ${EVENT_REMOVE_READER}: ${reader} does not exist within the ACL for a file ${changeObjectPath}.")
          account.userMsgs.add(s"Change ${EVENT_REMOVE_READER}: ${reader} does not exist within the ACL for a file ${changeObjectPath}.")
        }
      } else {
        // The reader's access has already been revoked above; only the owner-side ACL bookkeeping
        // is left. Keep the change so a later poll can finish it once the cloud read recovers,
        // and surface it as an error instead of reporting a success that did not fully happen.
        val msg = s"Change ${EVENT_REMOVE_READER}: reading readers attribute failed for ${storageObjectMetadata.getObjectPath} " +
          s"after ${READERS_READ_MAX_ATTEMPTS} attempts; ${reader}'s access was revoked but the change was kept and will be retried."
        logger.warn(msg, cloudFileDataAttributeTry.failed.get)
        account.userMsgs.add(msg)
        throw new RetryableChangeException(msg, cloudFileDataAttributeTry.failed.get)
      }
    }
  }

  /**
   * Processes a sharing/publishing event, saving signed metadata and notifying local event handlers.
   *
   * In enterprise-custodian mode: ordinary users accept SHARE only from the custodian;
   * the custodian accepts SHARE from the data owner (or itself). When the mode is off,
   * peer-to-peer SHARE is allowed as before.
   *
   * @param changeObjectPath the path representing the change event
   * @param from the originator of the change event
   * @param storageObjectMetadata the metadata of the file being shared
   * @param ec the implicit execution context
   */
  def shareEvent(changeObjectPath: String, from: String, storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext): Unit = {
    val cloudFile = account.getFileSystemHandler().parseObjectPathIncludingVersion(storageObjectMetadata.getObjectPath)

    val existingCloudFile = account.getFileSystemHandler().findCloudFile(cloudFile)

    val authorizedToShare =
      if (!account.isCustodianMode) {
        true
      } else if (account.MY_USER == account.CUSTODIAN_USER) {
        from == storageObjectMetadata.storageAttrs.dataOwner || from == account.CUSTODIAN_USER
      } else {
        from == account.CUSTODIAN_USER
      }

    if (!authorizedToShare) {
      val msg = s"Change ${EVENT_SHARE}: from=${from} is not allowed in enterprise-custodian mode " +
        s"(expected custodian=${account.CUSTODIAN_USER}" +
        (if (account.MY_USER == account.CUSTODIAN_USER)
          s" or dataOwner=${storageObjectMetadata.storageAttrs.dataOwner}"
         else "") +
        s") for ${storageObjectMetadata.getObjectPath}; " +
        s"the change is going to be deleted without changing the catalog."
      logger.warn(msg)
      account.userMsgs.add(msg)
    } else if (account.MY_USER != account.CUSTODIAN_USER &&
      existingCloudFile != null && !(existingCloudFile.getVersions.last.getTag == cloudFile.getVersions.last.getTag)) {
      // TODO: let to user know, we did not accept the change
      logger.warn(s"The change: ${changeObjectPath} that does not match the existing tag for a file '${existingCloudFile.getVersions.last.getTag}' is going to be deleted without changing the catalog.")
      account.userMsgs.add(s"The change: ${changeObjectPath} that does not match the existing tag for a file '${existingCloudFile.getVersions.last.getTag}' is going to be deleted without changing the catalog.")
    } else {

      if (retrieveCloudFileDataAttribute(storageObjectMetadata, "size").isFailure) {
        logger.warn(s"Change: trying to share unexisting file ${changeObjectPath} .")
        account.userMsgs.add(s"Change: trying to share unexisting file ${changeObjectPath} .")
      } else {
        // sign metadata by myself
        storageObjectMetadata.metadataSignature =
          Some(signString(metadataAuthorityString(account.MY_USER, storageObjectMetadata)))

        // TODO: do not store it, if I do not know the owner.
        // The situation might happen when somebody, who knows the owner shared the file with me

        storeCloudFileMetadata(storageObjectMetadata).get

        // Register in cloudFilesInUse for immediate UI visibility. SHARE is already durable
        // in the catalog — do not leave the path in cloudFilesInUploadingProcess, or
        // listDirectory/refresh will keep resurrecting it after a later custodian revoke.
        account.getFileSystemHandler().addCloudFileInUploadingProcess(cloudFile)
        account.getFileSystemHandler().removeCloudFileInUploadingProcess(cloudFile)

        account.getFileSystemHandler().fetchAltaStataEvent(new AltaStataEvent(EVENT_SHARE, storageObjectMetadata.getObjectPath))
      }
    }
  }

  /**
   * Processes a deletion event, removing metadata entries, version records, and cleaning up associated attributes.
   *
   * @param changeObjectPath the path representing the change event
   * @param from the originator of the change event
   * @param storageObjectMetadata the metadata of the file being deleted
   * @param ec the implicit execution context
   */
  def deleteEvent(changeObjectPath: String, from: String, storageObjectMetadata: StorageObjectMetadata)(implicit ec: ExecutionContext): Unit = {
    val cloudFile = account.getFileSystemHandler().parseObjectPathIncludingVersion(storageObjectMetadata.getObjectPath)

    val existingCloudFile = account.getFileSystemHandler().findCloudFile(cloudFile)

    // Non-custodian mode: owner or custodian may send DELETE (legacy peer delete).
    // Enterprise-custodian mode: readers accept DELETE only from the custodian; the
    // custodian still accepts DELETE from the data owner (attribute cleanup path).
    val authorizedToDelete =
      if (!account.isCustodianMode) {
        from == storageObjectMetadata.storageAttrs.dataOwner || from == account.CUSTODIAN_USER
      } else if (account.MY_USER == account.CUSTODIAN_USER) {
        from == storageObjectMetadata.storageAttrs.dataOwner || from == account.CUSTODIAN_USER
      } else {
        from == account.CUSTODIAN_USER
      }

    if (account.MY_USER != account.CUSTODIAN_USER &&
      existingCloudFile != null && !(existingCloudFile.getVersions.last.getTag == cloudFile.getVersions.last.getTag)) {
      // TODO: let to user know, we did not accept the change
      logger.warn(s"The change: ${changeObjectPath} that does not match the existing tag for a file '${existingCloudFile.getVersions.last.getTag}' is going to be deleted without changing the catalog.")
      account.userMsgs.add(s"The change: ${changeObjectPath} that does not match the existing tag for a file '${existingCloudFile.getVersions.last.getTag}' is going to be deleted without changing the catalog.")
    } else if (!authorizedToDelete) {
      val msg =
        if (account.isCustodianMode && account.MY_USER != account.CUSTODIAN_USER) {
          s"Change ${EVENT_DELETE}: from=${from} is not custodian=${account.CUSTODIAN_USER} " +
            s"for ${storageObjectMetadata.getObjectPath} (enterprise-custodian mode); " +
            s"the change is going to be deleted without changing the catalog."
        } else {
          s"Change ${EVENT_DELETE}: from=${from} is neither dataOwner=${storageObjectMetadata.storageAttrs.dataOwner} " +
            s"nor custodian=${account.CUSTODIAN_USER} for ${storageObjectMetadata.getObjectPath}; " +
            s"the change is going to be deleted without changing the catalog."
        }
      logger.warn(msg)
      account.userMsgs.add(msg)
    } else {
      // if it was sent by data owner and I am custodian, remove data attributes (same for all clouds, including Azure).
      if (storageObjectMetadata.storageAttrs.dataOwner == from && account.MY_USER == account.CUSTODIAN_USER) {
        findLockForObjectPath(storageObjectMetadata).synchronized {
          try {
            val attributes = account.cloudObjectHandler.listObjectsAtCloud(account.DATA_PROPERTIES_BUCKET,
              storageObjectMetadata.storageAttrs.dataOwner,
              storageObjectMetadata.storageAttrs.dataAttributesLocator,
              true).get.asScala

            attributes.foreach { attributePath =>
              val attribute = attributePath.split("/").last
              deleteCloudFileDataAttribute(storageObjectMetadata, attribute).get
            }
          } catch {
            case e: Exception =>
              logger.warn(s"deleteEvent: failed to clean up data attributes for ${storageObjectMetadata.getObjectPath}", e)
          }
        }
      }

      val metadataDeletion = deleteCloudFileMetadata(storageObjectMetadata.getObjectPath)
      if (metadataDeletion.isFailure) {
        val msg = s"Change ${EVENT_DELETE}: failed to delete catalog metadata for " +
          s"${storageObjectMetadata.getObjectPath}; the change was kept and will be retried."
        logger.warn(msg, metadataDeletion.failed.get)
        account.userMsgs.add(msg)
        throw new RetryableChangeException(msg, metadataDeletion.failed.get)
      }

      // Do not update the in-memory catalog or notify the UI until the durable catalog
      // deletion above has succeeded. Otherwise the file can appear deleted until a
      // refresh reloads the still-present catalog object.
      if (existingCloudFile != null) {
        existingCloudFile.removeVersion(cloudFile.getVersions.first)

        account.getFileSystemHandler().tryToDeleteFile(existingCloudFile)
      }

      account.getFileSystemHandler().fetchAltaStataEvent(new AltaStataEvent(EVENT_DELETE, storageObjectMetadata.getObjectPath))
    }
  }

  /**
   * Processes a cloud-notified change (add reader, remove reader, share, delete, or rename) asynchronously.
   *
   * @param changeObjectPath the path of the change event object inside the changes bucket
   * @param ec the implicit execution context
   * @return a Future completing with true if successfully processed; false otherwise
   */
  def processChange(changeObjectPath: String)(implicit ec: ExecutionContext): Future[Boolean] = Future {
    logger.debug("processChange: " + changeObjectPath)

    // When a change hits a transient cloud error we keep it in the queue and retry it on a
    // later poll instead of deleting it as a permanently failed ("strange") change.
    var keepChangeForRetry = false
    var timeOfEvent = "unknown"
    var eventName = ""

    try {
      val parts = changeObjectPath.split("/")
      val (_, parsedEventName, parametersStr, storageFilePathIncludingVersion) =
        (parts(1).toLong, parts(2), parts(3), parts.slice(4, parts.length).mkString("/"))
      eventName = parsedEventName
      timeOfEvent = CloudFile.DATEFORMAT.format(parts(1).toLong)

      val parameters = parametersStr.split("&").map( v => {
        val m =  v.split("=", 2)
        m(0) -> m(1)
      }).toMap

      val from = parameters.get("from").get

      if (eventName == EVENT_ADD_READER) {
          
        checkIfAuthorityValid(changeObjectPath, from)

        if (from != account.MY_USER) {
          account.userMsgs.add(s"time: ${timeOfEvent}\nevent: ${eventName}\nfrom: ${from}\nreader: ${parameters.get("reader").get}\nfile: ${storageFilePathIncludingVersion}")
        }

        addReader(changeObjectPath, from, parameters.get("reader").get, storageFilePathIncludingVersion)
        
      } else if (eventName == EVENT_REMOVE_READER) {

        checkIfAuthorityValid(changeObjectPath, from)

        account.userMsgs.add(s"time: ${timeOfEvent}\nevent: ${eventName}\nfrom: ${from}\nreader: ${parameters.get("reader").get}\nfile: ${storageFilePathIncludingVersion}")

        removeReader(changeObjectPath, from, parameters.get("reader").get, storageFilePathIncludingVersion)
        
      } else if (eventName == EVENT_ADD_USERDATA) {

        val userMetadataSerialized =
          account.cloudObjectHandler.retrieveObjectFromCloud(account.CHANGES_BUCKET, account.MY_USER, changeObjectPath).get

        val incomingUserMetadata = decode[UserMetadata](new String(userMetadataSerialized, "UTF-8")).toOption.get
        checkIfUserdataAuthorityValid(incomingUserMetadata, from)
        incomingUserMetadata.authorityAttrs = None

        val userMetadata = account.fileSystemModel.handleUserMetadata(
          incomingUserMetadata.asJson.noSpaces,
          account.getProperty("sign-cert-url")
        )

        account.userMsgs.add(s"time: ${timeOfEvent}\nevent: ${eventName}\nfrom: ${from}\nuser: ${userMetadata.userName}")

        if (from == userMetadata.userName || from == "admin") {
          account.fileSystemModel.storeUserdata(userMetadata.userName, userMetadata)
        }
        else {
          logger.warn(s"The userdata is sent from: ${from}, which is not admin or ${userMetadata.userName}. The change was ignored.")
        }
      }
      else {

        if (from != account.MY_USER) {
          account.userMsgs.add(s"time: ${timeOfEvent}\nevent: ${eventName}\nfrom: ${from}\nfile: ${storageFilePathIncludingVersion}")
        }
        
        // read storageObjectMetadata from CHANGES BUCKET
        val serializedMetadata = 
          account.cloudObjectHandler.retrieveObjectFromCloud(account.CHANGES_BUCKET, account.MY_USER, changeObjectPath).get

        val storageObjectMetadata = account.userProps.getProperty("metadata-encryption") match {
          case "RSA" => decode[StorageObjectMetadata](new String(decryptArrayWithRSA(serializedMetadata, RSA_OAEP), "UTF-8")).toOption.get
          case "PQC" => decode[StorageObjectMetadata](new String(decryptArrayWithKyber(serializedMetadata), "UTF-8")).toOption.get
          case "HSM" => // deserialize flattenArray to StorageObjectMetadata
            decode[StorageObjectMetadata](new String(decryptArrayWithHSM(serializedMetadata,
              account.fileSystemModel.retrieveUserdata(account.MY_USER).hsmKeyId.get), "UTF-8")).toOption.get
        }
        
        checkIfAuthorityValid(storageObjectMetadata, from)

        if (eventName == EVENT_SHARE) {
          shareEvent(changeObjectPath, from, storageObjectMetadata)
        } else if (eventName == EVENT_DELETE) {
          deleteEvent(changeObjectPath, from, storageObjectMetadata)
        }
      }
    } catch {
      case e: RetryableChangeException =>
        // Transient cloud failure. The handler already logged the cause and surfaced a UI
        // message; keep the change so it is retried on a later poll rather than dropped.
        keepChangeForRetry = true
      case e: RetrieveCloudObjectException
          if eventName == EVENT_REMOVE_READER && isAlreadyConsumedChangeObject(changeObjectPath, e) =>
        // LIST can still show a REMOVEREADER that a prior poll already deleted, or GET can
        // briefly lag the list. The queue object is gone — not a poison event.
        logger.debug(s"Change ${EVENT_REMOVE_READER} ${changeObjectPath} already consumed; nothing to apply")
      case e: Exception =>
        logger.warn(s"time: ${timeOfEvent} Strange change: ${changeObjectPath} is going to be deleted without updating the catalog.", e)
        account.userMsgs.add(s"time: ${timeOfEvent}\n Strange change: ${changeObjectPath} is going to be deleted without updating the catalog.")
    }

    // Only drop the change once it has been fully handled (or is a permanent failure). A change
    // kept for retry stays in the queue so a subsequent poll can complete it.
    if (!keepChangeForRetry) {
      account.cloudObjectHandler.deleteObjectFromCloud(account.CHANGES_BUCKET, account.MY_USER, changeObjectPath)
    }

    !keepChangeForRetry
  }

  /**
   * True when the missing cloud object is the change queue key itself (already deleted or
   * list/get race), not some other catalog/attr blob referenced while handling the event.
   */
  private def isAlreadyConsumedChangeObject(changeObjectPath: String, e: Throwable): Boolean = {
    def mentionsChangeAndNotFound(t: Throwable): Boolean = {
      val msg = Option(t.getMessage).getOrElse("")
      msg.contains(changeObjectPath) && (
        msg.toLowerCase.contains("blob not found") ||
        msg.contains("BlobNotFound") ||
        msg.contains("NoSuchKey") ||
        msg.contains("404")
      )
    }
    var cur: Throwable = e
    while (cur != null) {
      if (mentionsChangeAndNotFound(cur)) return true
      cur = cur.getCause
    }
    false
  }

  /**
   *  Infinite loop to receive the SQS messages
   */
  val runnableSQS = new Runnable {

    /**
     * Processes a batch of changes received from the queue.
     *
     * @param it the iterator containing change messages
     */
    def processAllChanges(it: java.util.Iterator[String]): Unit = {
      implicit val ec = OpsExecutors.ecFastFileOps

      // Collect all changes up front so we can order them by target object instead
      // of by event type. Listing returns only keys, so this is cheap; the expensive
      // per-object reads still happen inside processChange.
      // TODO: clean the SQS only after its finally done and not before
      val allChanges = it.asScala.toList
      val (validChanges, malformedChanges) = allChanges.partition(isValidChangeObjectPath)

      malformedChanges.foreach { changeObjectPath =>
        val msg = s"Malformed change object ${changeObjectPath} is going to be deleted without updating the catalog."
        logger.warn(msg)
        account.userMsgs.add(msg)
        account.cloudObjectHandler
          .deleteObjectFromCloud(account.CHANGES_BUCKET, account.MY_USER, changeObjectPath)
          .failed
          .foreach(e => logger.warn(s"Failed to delete malformed change object ${changeObjectPath}", e))
      }

      if (validChanges.nonEmpty) {
        logger.info(s"\tprocessAllChanges received ${validChanges.size} valid changes")

        val (userdataChanges, groups) = planChanges(validChanges)

        // Phase 1: ADD_USERDATA (provisioning) must complete before file events,
        // because ADD_READER may need the new reader's userdata to already exist.
        // These are independent across users, so run them in parallel, then barrier.
        if (userdataChanges.nonEmpty) {
          filesAccessManagersChangesInProcess.clear
          Await.result(Future.sequence(userdataChanges.map(processChange)), Duration.Inf)
        }

        // Phase 2: everything else. The only ordering that matters is per target
        // object (e.g. ADD_READER before REMOVE_READER of the same file), so each
        // group is processed sequentially in timestamp order while different groups
        // run in parallel. This replaces the global per-type barrier, which
        // over-serialized whenever event types interleaved in time.
        filesAccessManagersChangesInProcess.clear

        // Bound how many object-groups run concurrently.
        groups.grouped(CONCURRENT_EVENTS_THREADS_POOL_SIZE).foreach { batch =>
          val listOfFutures = batch.map { group =>
            group.foldLeft(Future.successful(true)) { (acc, change) =>
              // A retained change has not completed. Do not apply later operations for
              // this object (such as a DELETE after a failed SHARE) out of causal order.
              acc.flatMap { previousSucceeded =>
                if (previousSucceeded) processChange(change) else Future.successful(false)
              }
            }
          }
          Await.result(Future.sequence(listOfFutures), Duration.Inf)
        }
      }
    }

    def run(): Unit = {
      var onPreviousIterationPasswordWasNotSet = true
      var doFinishingShot = false
      val progressChars = Array("|", "/", "-", "\\")  // Rotating pipe characters
      var progressIndex = 0

      try {
        /**
         * Main event loop for polling the message queue and processing changes.
         *
         * @return a boolean indicating whether to continue looping
         */
        def loop(): Boolean = {
          // Decide under the account lock so a concurrent setPassword(login) cannot be
          // wiped by a stale "expired" read from before the password was set. Do not call
          // getPassword() here — it refreshes the expiry timer as a side effect.
          val shouldProcessChanges = account.synchronized {
            // Password expiry applies only to RSA/PQC software-key accounts
            // (see Account.requiresLocalPassword) — not to HSM/HPCS.
            val expired = account.requiresLocalPassword &&
              System.currentTimeMillis > account.accountPasswordNextExpiredTime

            if (expired) {
              if (account.isPasswordSet) {
                logger.info("The password expired for user: " + account.MY_USER)
                account.userMsgs.add(
                  s"time: ${CloudFile.DATEFORMAT.format(System.currentTimeMillis)}\nevent: password timeout")
                account.setPassword(null)
              }
              onPreviousIterationPasswordWasNotSet = true
              false
            } else {
              true
            }
          }

          if (shouldProcessChanges) {
            // read the messages queue
            val anyMsgs = account.cloudMsgsHandler.receiveMsgsForUser()

            if (onPreviousIterationPasswordWasNotSet) {
              logger.debug("Loop anyMsgs: " + anyMsgs + " onPreviousIterationPasswordWasNotSet: " + onPreviousIterationPasswordWasNotSet)
            }

            if (anyMsgs) {
              // Show rotating pipe animation - updates with main loop
              print(s"\r\u001B[K${progressChars(progressIndex)}")  // \r moves cursor to start, \u001B[K clears line
              System.out.flush()  // Ensure immediate display
              progressIndex = (progressIndex + 1) % progressChars.length
              
              account.cloudMsgsHandler.clearMsgs()
            }

            if (anyMsgs || onPreviousIterationPasswordWasNotSet || doFinishingShot) {

              implicit val ec = OpsExecutors.ecFastFileOps
              val it = retrieveObjectsList(account.CHANGES_BUCKET, account.MY_USER, account.QUEUE_NAME, true)

              if (it.hasNext) {
                logger.info("New changes for user: " + account.MY_USER)

                processAllChanges(it)

                logger.info("Schedule finishing shot ...")

                doFinishingShot = true

                onPreviousIterationPasswordWasNotSet = false
              }
              else {
                if (anyMsgs) {
                  logger.info(s"SQS notified user ${account.MY_USER} but changes queue ${account.QUEUE_NAME} is empty")
                }
                // if it was just finishing shot, but no changes, do not do it again
                if (doFinishingShot) {
                  logger.info("Finishing shot was processed")

                  doFinishingShot = false
                }
              }
            }
          }

          Thread.sleep(account.sqsInterval)

          loop()
        }

        loop()
      }
      catch {
        case e: Exception => logger.warn("runnableSQS was interrupted and stopped")
      }
    }
  }

}
