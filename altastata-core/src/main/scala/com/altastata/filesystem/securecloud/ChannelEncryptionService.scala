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

import org.slf4j.LoggerFactory
import scala.concurrent._
import scala.collection.JavaConverters._
import com.altastata.utils.Account
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Using the cloudFile's AES GCM Key this service can encrypt and decrypt any buffer
 */
case class ChannelEncryptionService(filePath: String, timestamp: java.lang.Long = System.currentTimeMillis)(implicit account: Account) extends SecureCloudOperations {

  private val logger = LoggerFactory.getLogger(getClass)

  // Ensure the type is explicitly stated
  implicit val ec: ExecutionContext = OpsExecutors.secureCloudOps

  var storageObjectMetadata: StorageObjectMetadata = null

  // Use Future to handle asynchronous tasks, making sure the implicit ExecutionContext is in scope
  val foundList = account.fileSystemModel.listCloudFiles(filePath, true).asScala.toList

  val cloudFile =
    if (foundList.isEmpty) {
      // create it
      val cf = account.getFileSystemHandler().createCloudFileVersion(filePath, false, timestamp)
      account.fileSystemModel.storeByteBufferToCloudFile(ByteBuffer.allocate(0), cf)

      cf
    } else foundList.last

  val bestMatchingVersion = cloudFile.getBestMatchingVersionAttributes(timestamp)
  if (bestMatchingVersion != null) {
    val storageCloudObjectPathIncludingVersion = cloudFile.getCloudObjectPathIncludingVersion(bestMatchingVersion)

    logger.info(s"SecureKeyService: ${storageCloudObjectPathIncludingVersion}")

    // Ensure you're calling an asynchronous method that uses the implicit ExecutionContext
    storageObjectMetadata = retrieveCloudFileMetadata(account.MY_USER, storageCloudObjectPathIncludingVersion).get

    checkIfMetadataIsSignedByMyself(storageObjectMetadata)
  }

  /**
   * Encrypts a byte array payload using the cloud file's AES-GCM key.
   *
   * @param buffer the raw plaintext byte array to encrypt
   * @return the encrypted record (`iv || ciphertext`)
   */
  def encryptByteArray(buffer: Array[Byte]): Array[Byte] = {
    encryptGcmObject(
      buffer,
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey)
    )
  }

  /**
   * Decrypts a ciphertext byte array back to plaintext using the cloud file's AES-GCM key.
   *
   * @param ciphertext the encrypted record (`iv || ciphertext`)
   * @return the decrypted plaintext byte array
   */
  def decryptByteArray(ciphertext: Array[Byte]): Array[Byte] = {
    decryptGcmObject(
      ciphertext,
      Base64.getDecoder().decode(storageObjectMetadata.encryptionAttrs.encryptedAESKey)
    )
  }
}

object ChannelEncryptionServiceBuilder {

  /**
   * Factory method to build a new ChannelEncryptionService instance.
   *
   * @param filePath the path of the secure file containing the target key materials
   * @param timestamp the version creation timestamp
   * @param account the account context
   * @return the constructed ChannelEncryptionService instance
   */
  def getChannelEncryptionService(filePath: String, timestamp: java.lang.Long, account: Account) = ChannelEncryptionService(filePath, timestamp)(account)
}
