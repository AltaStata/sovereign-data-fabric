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

package com.altastata.cloud.localfs

import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.concurrent.duration._
import scala.collection._
import scala.collection.JavaConverters._
import java.nio.ByteBuffer
import com.altastata.utils.ByteBufferUtils
import com.altastata.filesystem.securecloud.CloudObjectHandler
import scala.util.Try
import java.net.URI
import com.altastata.utils.Account
import com.altastata.utils.Constants

class LocalFSCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  val localFSManager = new LocalFSManager()

  // reinit it as we can switch the accounts
  localFSManager.init()

  /**
   * Stores a byte array payload as an object on local filesystem-backed mock storage.
   *
   * @param buffer the byte array payload data
   * @param bucketName the target mock bucket/directory name prefix
   * @param userInBucket the folder or user namespace
   * @param objectKey the target storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the write operation
   */
  def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {
    logger.trace(s"\tstoreObjectToCloud START: ${bucketName}-${userInBucket} -- ${objectKey}")

    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(localFSManager.storeInLocalFS(buffer, bucketName + "-" + userInBucket, objectKey, buffer.array.length), Duration.Inf)        
  }
  
  // TODO: make sure about FILE_MARK_SIGN
  /**
   * Lists object keys under a given prefix inside the specified mock local cloud storage bucket.
   *
   * @param bucketName the target mock bucket/container name
   * @param user user namespace context
   * @param prefix the folder prefix path to query
   * @param useFlatBlobListing true for recursive listing; false for flat/nested hierarchies
   * @param startAfter key bound pagination start limit
   * @param endBefore key bound pagination cutoff limit
   * @param ec the implicit execution context
   * @return Success containing a Java Iterator of listed object names; Failure otherwise
   */
  def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]] = Try {

    logger.trace(s"\tlistObjectsAtCloud START: ${bucketName}-${user} -- ${prefix} useFlatBlobListing ${useFlatBlobListing}")

    localFSManager.getLocalFSListWithoutDetails(bucketName + "-" + user, prefix, useFlatBlobListing)
  }
  
  /**
   * Retrieves a stored byte array payload from local filesystem-backed mock storage.
   *
   * @param bucketName the target mock bucket/directory name prefix
   * @param userInBucket the folder or user namespace
   * @param objectKey the target storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the retrieved byte array data
   */
  def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]] = Try {
    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(localFSManager.retrieveFromLocalFS(bucketName + "-" + userInBucket, objectKey), Duration.Inf)
  } 
  
  /**
   * Deletes an object from local filesystem-backed mock storage.
   *
   * @param bucketName the target mock bucket/directory name prefix
   * @param userInBucket the folder or user namespace
   * @param objectKey the key of the object to delete
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the deletion
   */
  def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {

    logger.trace(s"\tdeleteObjectFromCloud START: ${bucketName}-${userInBucket} -- ${objectKey}")

    localFSManager.deleteObjectFromLocalFS(bucketName + "-" + userInBucket, objectKey)

    logger.trace(s"\tdeleteObjectFromCloud END: ${bucketName}-${userInBucket} -- ${objectKey}")
  }

}

