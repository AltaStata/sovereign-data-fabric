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

package com.altastata.cloud.amazon_java2

import com.altastata.filesystem.securecloud.CloudObjectHandler
import com.altastata.utils.{Account, Constants}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

class AmazonCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {

  private val logger = LoggerFactory.getLogger(getClass)
  val amazonS3Manager = new AmazonS3Manager()
  
  // reinit it as we can switch the accounts
  amazonS3Manager.init()
  amazonS3Manager.enableAccelerationModeForChunksBucket

  /**
   * Resolves the customized sub-bucket directory or identity name for a given user.
   * Leverages Cognito identity IDs if configured, or falls back to user metadata lookup.
   *
   * @param user the username to resolve
   * @return the resolved sub-bucket folder name
   */
  def getSubbucketName(user: String) = {
    if (user == "admin") {
      user
    }
    else if (user == "all") {
      account.ORGANIZATION + "_" + user
    }
    else if (user == account.MY_USER) {
      if (account.userProps.getProperty("cognito-identity-id") != null) {
        account.userProps.getProperty("cognito-identity-id")
      }
      else
        user
    }
    else {
      try {
        val userMetadata = account.fileSystemModel.retrieveUserdata(user)
        if (userMetadata != null) {
          userMetadata.cognitoIdentityId match {
            case Some(identityId) => {
              identityId
            }
            case None => {
              user
            }
          }
        }
        else {
          if (user == account.CUSTODIAN_USER) user else null
        }
      }
      catch {
        case e: Throwable => if (user == account.CUSTODIAN_USER) user else null
      }
    }
  }

  /**
   * Stores a byte array payload as an object on Amazon S3.
   *
   * @param buffer the byte array payload data
   * @param bucketName the target S3 bucket name
   * @param user the user context associated with the object
   * @param objectKey the target storage object key (path)
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the write operation
   */
  def storeObjectToCloud(buffer: Array[Byte], bucketName: String, user: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {    
    val userInBucket = getSubbucketName(user)

    logger.trace(s"\tstoreObjectToCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")
    
    Await.result(amazonS3Manager.storeInAmazon(buffer, bucketName, userInBucket + "/" + objectKey, buffer.length), Duration.Inf)

    logger.trace(s"\tstoreObjectToCloud END: ${bucketName} -- ${userInBucket} -- ${objectKey}")
  } 
  
  /**
   * Retrieves the logical size of an object stored on Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param user the user context associated with the object
   * @param objectKey the storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the object size integer
   */
  override def retrieveObjectSizeFromCloud(bucketName: String, user: String, objectKey: String)(implicit ec: ExecutionContext): Try[Int] = Try {
    val userInBucket = getSubbucketName(user)

    val amazonMetadata = amazonS3Manager.getAmazonMetadata(bucketName, userInBucket + "/" + objectKey)
        
    amazonMetadata.get(Constants.METADATAHEADER_OBJECTSIZE).get.toInt
  }
  
  /**
   * Lists the cloud object paths matching a prefix in a specific Amazon S3 bucket.
   *
   * @param bucketName the S3 bucket name
   * @param user the user context associated with the operation
   * @param prefix the object prefix to filter listings
   * @param useFlatBlobListing true to use recursive listing; false for directory hierarchies
   * @param startAfter optional key to start listing after for pagination
   * @param endBefore optional cutoff key to end listing before
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping a Java Iterator of listed object paths
   */
  def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]] = Try {
    val userInBucket = getSubbucketName(user)

    amazonS3Manager.getAmazonListWithoutDetails(bucketName, userInBucket, prefix, useFlatBlobListing, startAfter, endBefore).asJava
  }
  
  /**
   * Retrieves a stored byte array payload from Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param user the user context associated with the object
   * @param objectKey the storage object key
   * @param ec execution context for asynchronous operations
   * @return a Try wrapping the retrieved byte array data
   */
  def retrieveObjectFromCloud(bucketName: String, user: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]] = Try {
    val userInBucket = getSubbucketName(user)

    // TODO: check if we need it. I think the size is a part of the  name in that case
    // val size = await(retrieveObjectSizeFromCloud(bucketName, userInBucket, objectKey))

    // that is how to use it, if we do not create vals, they won't run in parallel
    Await.result(amazonS3Manager.retrieveFromAmazon(bucketName, userInBucket + "/" + objectKey), Duration.Inf)
  }
  
  /**
   * Deletes an object from Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param user the user context associated with the object
   * @param objectKey the key of the object to delete
   * @param ec execution context for asynchronous operations
   * @return a Try indicating success or failure of the deletion
   */
  def deleteObjectFromCloud(bucketName: String, user: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = Try {
    val userInBucket = getSubbucketName(user)

    logger.trace(s"\tdeleteObjectFromCloud START: ${bucketName} -- ${userInBucket} -- ${objectKey}")

    amazonS3Manager.deleteObjectFromAmazon(bucketName, userInBucket + "/" + objectKey)

    logger.trace(s"\tdeleteObjectFromCloud END: ${bucketName} -- ${userInBucket} -- ${objectKey}")
  }
}

