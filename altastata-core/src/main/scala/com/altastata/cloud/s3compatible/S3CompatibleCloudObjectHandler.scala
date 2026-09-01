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

package com.altastata.cloud.s3compatible

import com.altastata.utils.Account
import com.altastata.filesystem.securecloud.CloudObjectHandler
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/**
 * Base Cloud Object Handler for S3-compatible storage providers (IBM COS, Fusion, MinIO).
 * 
 * All handlers follow the same pattern:
 * - Bucket naming: {bucketName}-{userInBucket}
 * - Same four operations: store, retrieve, delete, list
 * 
 * Subclasses only need to provide a storage manager instance.
 */
abstract class S3CompatibleCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Abstract - each implementation provides its storage manager.
   * The manager should already be initialized.
   */
  protected def storageManager: S3CompatibleStorageManager

  /**
   * Construct the actual bucket name with user suffix if provided
   */
  protected def actualBucketName(bucketName: String, userInBucket: String): String = {
    if (userInBucket.nonEmpty) s"${bucketName}-${userInBucket}" else bucketName
  }

  /**
   * Store object to cloud bucket
   */
  override def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): scala.util.Try[Unit] = {
    scala.util.Try {
      val bucket = actualBucketName(bucketName, userInBucket)
      scala.concurrent.Await.result(
        storageManager.storeObject(buffer, bucket, objectKey, buffer.length),
        Duration.Inf
      )
    }
  }

  /**
   * Retrieve object from cloud bucket
   */
  override def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): scala.util.Try[Array[Byte]] = {
    scala.util.Try {
      val bucket = actualBucketName(bucketName, userInBucket)
      scala.concurrent.Await.result(
        storageManager.retrieveObject(bucket, objectKey),
        Duration.Inf
      )
    }
  }

  /**
   * Delete object from cloud bucket
   */
  override def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): scala.util.Try[Unit] = {
    scala.util.Try {
      val bucket = actualBucketName(bucketName, userInBucket)
      storageManager.deleteObject(bucket, objectKey)
    }
  }

  /**
   * List objects in cloud bucket
   */
  override def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): scala.util.Try[java.util.Iterator[String]] = {
    scala.util.Try {
      val bucket = actualBucketName(bucketName, user)
      storageManager.listObjects(bucket, prefix, useFlatBlobListing)
    }
  }
}

