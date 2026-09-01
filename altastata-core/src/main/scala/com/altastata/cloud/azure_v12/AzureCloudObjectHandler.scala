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

package com.altastata.cloud.azure_v12

import com.altastata.filesystem.securecloud.CloudObjectHandler
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

class AzureCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  val azureManager = new AzureManager()

  // reinit it as we can switch the accounts
  azureManager.init()

  /**
   * Stores a byte array payload as an object on Azure Blob Storage.
   *
   * @param buffer the byte array payload data
   * @param bucketName the target container name prefix
   * @param userInBucket the container sub-path or user namespace
   * @param objectKey the target storage blob key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the completion of the write operation
   */
  def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {
    logger.trace(s"\tstoreObjectToCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")

    Await.result(azureManager.storeInAzure(buffer, bucketName + "-" + userInBucket, objectKey), Duration.Inf)
  }
  
  override def retrieveObjectSizeFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Int] = Try {
    // Unused by SecureCloud; blob bytes come from retrieveObjectFromCloud.
    0
  }

  // TODO: make sure about FILE_MARK_SIGN
  /**
   * Lists objects at the given cloud bucket namespace matching the prefix.
   *
   * @param bucketName the container name
   * @param user the user namespace
   * @param prefix the object prefix
   * @param useFlatBlobListing true for recursive listing, false for hierarchical
   * @param startAfter optional key to start after
   * @param endBefore optional key to end before
   * @param ec the execution context
   * @return a Try containing an iterator of object keys
   */
  def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]] = Try {
    azureManager.getAzureListWithoutDetails(bucketName + "-" + user, prefix, useFlatBlobListing)
  }
  
  /**
   * Retrieves a stored byte array payload from Azure Blob Storage.
   *
   * @param bucketName the target container name prefix
   * @param userInBucket the container sub-path or user namespace
   * @param objectKey the target storage blob key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the retrieved byte array data
   */
  def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]] = Try {
    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(azureManager.retrieveFromAzure(bucketName + "-" + userInBucket, objectKey), Duration.Inf)
  } 
  
  /**
   * Deletes an object from Azure Blob Storage.
   *
   * @param bucketName the target container name prefix
   * @param userInBucket the container sub-path or user namespace
   * @param objectKey the target storage blob key to delete
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the deletion
   */
  def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {

    logger.trace(s"\tdeleteObjectFromCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")

    azureManager.deleteObjectFromAzure(bucketName + "-" + userInBucket, objectKey)

    logger.trace(s"\tdeleteObjectFromCloud END: ${bucketName} -- ${userInBucket} -- ${objectKey}")
  }

}

