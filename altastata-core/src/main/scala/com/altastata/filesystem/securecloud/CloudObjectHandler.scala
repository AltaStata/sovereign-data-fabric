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

import scala.concurrent._
import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer
import scala.util.Try

/**
 * The standard abstraction layer for bridging the AltaStata secure file system 
 * with various cloud object storage providers (AWS S3, Azure Blob Storage, GCP, IBM COS, MinIO).
 *
 * Implementations of this trait translate secure, chunk-based operations into the 
 * specific REST/SDK calls required by the underlying cloud provider. All data passed 
 * to and returned from these methods is assumed to be fully encrypted (ciphertext).
 */
trait CloudObjectHandler {
      
  /**
   * Stores an encrypted chunk/payload byte array directly to the cloud storage provider destination.
   *
   * @param buffer the raw data byte array to store
   * @param bucketName the target cloud bucket/container name
   * @param userInBucket the directory sub-path / owner prefix context within the bucket
   * @param objectKey the target file/object key name
   * @param ec the implicit execution context
   * @return Success(()) on success; Failure with exception on failure
   */
  def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit]
  
  /**
   * Retrieves the size of an encrypted object from the cloud storage provider.
   *
   * @param bucketName the target cloud bucket/container name
   * @param userInBucket the directory sub-path / owner prefix context
   * @param objectKey the target object key name
   * @param ec the implicit execution context
   * @return Success containing the object size; Failure with exception otherwise
   */
  def retrieveObjectSizeFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Int] = ???

  /**
   * Lists object keys under a given prefix inside the specified cloud storage bucket.
   *
   * @param bucketName the target cloud bucket/container name
   * @param user user namespace context
   * @param prefix the folder prefix path to query
   * @param useFlatBlobListing true for recursive listing; false for flat/nested hierarchies
   * @param startAfter key bound pagination start limit
   * @param endBefore key bound pagination cutoff limit
   * @param ec the implicit execution context
   * @return Success containing a Java Iterator of listed object names; Failure otherwise
   */
  def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]]
  
  /**
   * Downloads/Retrieves an encrypted object from the cloud storage provider.
   *
   * @param bucketName the target cloud bucket/container name
   * @param userInBucket the directory sub-path / owner prefix context
   * @param objectKey the target object key name
   * @param ec the implicit execution context
   * @return Success containing the downloaded raw byte array; Failure with exception otherwise
   */
  def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]]
  
  /**
   * Deletes an encrypted object from the cloud storage provider.
   *
   * @param bucketName the target cloud bucket/container name
   * @param userInBucket the directory sub-path / owner prefix context
   * @param objectKey the target object key name to delete
   * @param ec the implicit execution context
   * @return Success(()) on successful deletion; Failure otherwise
   */
  def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit]
}

