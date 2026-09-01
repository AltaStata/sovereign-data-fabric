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

package com.altastata.cloud.google

import com.altastata.filesystem.securecloud.CloudObjectHandler
import com.altastata.utils.{Account, Constants}
import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

/**
 * Based on https://cloud.google.com/storage/docs/uploading-objects
 */
class GoogleCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {

  println("GoogleCloudObjectHandler: " + account)

  private val logger = LoggerFactory.getLogger(getClass)

  val googleManager = new GoogleManager()

  // re-init it as we can switch the accounts
  googleManager.init()

  /**
   * Stores a byte array payload as an object on Google Cloud Storage.
   *
   * @param buffer the byte array payload data
   * @param bucketName the target GCS bucket name prefix
   * @param userInBucket the GCS folder or user namespace
   * @param objectKey the target storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the write operation
   */
  def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {
    logger.trace(s"\tstoreObjectToCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")

    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(googleManager.storeInGoogleStorage(buffer, bucketName + "-" + userInBucket, objectKey, buffer.array.length), Duration.Inf)
  }
  
  /** Stub: multicloud path does not use object size for Google; returns 0 without a GCS HEAD/get. */
  override def retrieveObjectSizeFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Int] = Try {
    0
  }

  /**
   * Lists the cloud object paths matching a prefix in a specific GCS bucket.
   *
   * @param bucketName the target GCS bucket name prefix
   * @param user the user namespace folder
   * @param prefix the object prefix under the user namespace
   * @param useFlatBlobListing true to use flat recursive listing; false for directory hierarchies
   * @param startAfter optional key bound for pagination
   * @param endBefore optional cutoff bound
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping a Java Iterator of listed object paths
   */
  def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]] = Try {
    googleManager.getGoogleStorageListWithoutDetails(bucketName + "-" + user, prefix, useFlatBlobListing)
  }
  
  /**
   * Retrieves a stored byte array payload from Google Cloud Storage.
   *
   * @param bucketName the target GCS bucket name prefix
   * @param userInBucket the GCS folder or user namespace
   * @param objectKey the target storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the retrieved byte array data
   */
  def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]] = Try {
    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(googleManager.retrieveFromGoogleStorage(bucketName + "-" + userInBucket, objectKey), Duration.Inf)
  } 
  
  /**
   * Deletes an object from Google Cloud Storage.
   *
   * @param bucketName the target GCS bucket name prefix
   * @param userInBucket the GCS folder or user namespace
   * @param objectKey the key of the object to delete
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the deletion
   */
  def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {

    logger.trace(s"\tdeleteObjectFromCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")

    googleManager.deleteObjectFromGoogle(bucketName + "-" + userInBucket, objectKey)

    logger.trace(s"\tdeleteObjectFromCloud END: ${bucketName} -- ${userInBucket} -- ${objectKey}")
  }

}

