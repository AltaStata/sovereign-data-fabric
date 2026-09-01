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

import com.altastata.api.AltaStataFileSystem
import com.altastata.filesystem.{RetrieveCloudObjectException, StoreCloudObjectException}
import com.altastata.filesystem.securecloud.OpsExecutors
import com.altastata.utils.{Account, Constants}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.regions.Region
import org.slf4j.LoggerFactory
import org.apache.commons.lang3.concurrent.BasicThreadFactory
import software.amazon.awssdk.core.client.config.{ClientOverrideConfiguration, SdkAdvancedClientOption}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.services.s3.model.{AccelerateConfiguration, BucketAccelerateStatus, Delete, DeleteObjectsRequest, DeleteObjectsResponse, GetBucketAccelerateConfigurationRequest, GetObjectAttributesRequest, GetObjectRequest, HeadObjectRequest, ListObjectsRequest, ListObjectsV2Request, NoSuchKeyException, ObjectIdentifier, PutBucketAccelerateConfigurationRequest, PutObjectRequest}
import software.amazon.awssdk.http.apache.ApacheHttpClient

import java.io.{ByteArrayInputStream, Closeable, File}
import java.util.Date
import java.time.Duration
import java.util.concurrent.Executors
import scala.collection.JavaConverters._
import scala.collection._
import scala.collection.immutable.HashMap
import scala.collection.mutable.{ArrayBuffer, Buffer}
import scala.sys.process.BasicIO
import scala.util.Try
import scala.util.control.Exception.catching
import scala.concurrent._

class AmazonS3Manager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)
  
  // TODO: create the algorithm that can optimize it on the fly
  val OBJECT_UPLOAD_THREAD_POOL_SIZE = 100
  val OBJECT_DOWNLOAD_THREAD_POOL_SIZE = 100

  var chunksS3Client: S3Client = null
  var systemS3Client: S3Client = null
  /**
   * Returns the S3Client instance appropriate for the given bucket name.
   * If the bucket name contains "-chunks", returns the chunks-specific client; otherwise returns the system client.
   *
   * @param bucketName the target bucket name
   * @return the appropriate software.amazon.awssdk.services.s3.S3Client instance
   */
  def s3Client(bucketName: String): S3Client = if (bucketName != null && bucketName.contains("-chunks")) chunksS3Client else systemS3Client

  /**
   * Returns the default system S3Client instance.
   *
   * @return the default S3Client instance
   */
  def s3Client(): S3Client = systemS3Client

  /**
   * Initializes the AWS S3 client managers, setting up the custom HTTP clients, timeouts,
   * override configurations, and credential suppliers.
   */
  def init() = {

    val chunksApacheHttpClient = ApacheHttpClient.builder()
      .connectionTimeout(Duration.ofSeconds(15))
      .socketTimeout(Duration.ofSeconds(180))
      .maxConnections(OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_CHUNKS)
      .build()

    val systemApacheHttpClient = ApacheHttpClient.builder()
      .connectionTimeout(Duration.ofSeconds(15))
      .socketTimeout(Duration.ofSeconds(180))
      .maxConnections(OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_SYSTEM)
      .expectContinueEnabled(java.lang.Boolean.FALSE)
      .build()

    val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
      .apiCallTimeout(Duration.ofSeconds(600))      // 1-minute overall timeout
      .apiCallAttemptTimeout(Duration.ofSeconds(180)) // 30-second timeout for each attempt
      .retryPolicy(RetryPolicy.builder()
        .numRetries(5)
        .build())
      .build()

    if (account.MY_USER == "admin") {
      if (account.userProps.getProperty("AWSAccessKeyId") != null && account.userProps.getProperty("AWSSecretKey") != null) {

        val awsCreds =
          if (account.userProps.getProperty("metadata-encryption") == "HSM")
            AwsBasicCredentials.create(account.getProperty("AWSAccessKeyId"), account.getProperty("AWSSecretKey"))
          else
            AwsBasicCredentials.create(account.getAndDecryptProperty("AWSAccessKeyId"), account.getAndDecryptProperty("AWSSecretKey"))

        chunksS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(chunksApacheHttpClient)
          .build()

        systemS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(systemApacheHttpClient)
          .build()
      }
      else {

        chunksS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(DefaultCredentialsProvider.create())
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(chunksApacheHttpClient)
          .build()

        systemS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(DefaultCredentialsProvider.create())
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(systemApacheHttpClient)
          .build()
      }
    }
    else { // not admin user
      if (account.userProps.getProperty("cognito-identity-id") != null) {
        val altastataCognitoProvider =  account.cognitoClient.getCredentialsProvider(account.MY_USER, account.getCognitoPassword)

        chunksS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(altastataCognitoProvider)
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(chunksApacheHttpClient)
          .build()

        systemS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(altastataCognitoProvider)
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(systemApacheHttpClient)
          .build()
      }

      else {

        val credentialsProvider: software.amazon.awssdk.auth.credentials.AwsCredentialsProvider =
          if (account.userProps.getProperty("AWSAccessKeyId") != null && account.userProps.getProperty("AWSSecretKey") != null) {
            val awsCreds =
              if (account.userProps.getProperty("metadata-encryption") == "HSM")
                AwsBasicCredentials.create(account.getProperty("AWSAccessKeyId"), account.getProperty("AWSSecretKey"))
              else
                AwsBasicCredentials.create(account.getAndDecryptProperty("AWSAccessKeyId"), account.getAndDecryptProperty("AWSSecretKey"))
            StaticCredentialsProvider.create(awsCreds)
          }
          else
            DefaultCredentialsProvider.create()

        chunksS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(credentialsProvider)
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(chunksApacheHttpClient)
          .build()

        systemS3Client = S3Client.builder()
          .region(Region.of(account.getProperty("region")))
          .credentialsProvider(credentialsProvider)
          .overrideConfiguration(clientOverrideConfiguration)
          .httpClient(systemApacheHttpClient)
          .build()
      }
    }
  }

  /*
  private def getClientConfiguration : ClientConfiguration = {
    val clientConfiguration = new ClientConfiguration()

    clientConfiguration
        .withUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/43.0.2357.65 Safari/537.36")
    
    clientConfiguration.withConnectionTimeout(5 * 60000).withSocketTimeout(120000).withMaxConnections(300).withMaxErrorRetry(10)
    //TODO: android does not support it: .withTcpKeepAlive(true)

    if (account.userProps.getProperty("proxyHost") != null) {
      clientConfiguration.withProxyHost(account.userProps.getProperty("proxyHost"))
                         .withProxyPort(Integer.parseInt(account.userProps.getProperty("proxyPort")))
    }
    
    clientConfiguration
  }
   */
  
  /**
   * Enables transfer acceleration mode for the specified chunks bucket.
   */
  def enableAccelerationModeForChunksBucket = {
    try {

      val putBucketAccelerateConfigurationRequest =
        PutBucketAccelerateConfigurationRequest.builder
                                               .bucket(account.CHUNKS_BUCKET)
                                               .accelerateConfiguration(AccelerateConfiguration
                                                                          .builder()
                                                                          .status(BucketAccelerateStatus.ENABLED)
                                                                          .build())
                                               .build()

      // Enable Transfer Acceleration for the specified bucket.
      s3Client(account.CHUNKS_BUCKET).putBucketAccelerateConfiguration(putBucketAccelerateConfigurationRequest)

      // Verify that transfer acceleration is enabled for the bucket.
      val getBucketAccelerateConfigurationRequest = GetBucketAccelerateConfigurationRequest.builder()
                                                    .bucket(account.CHUNKS_BUCKET)
                                                    .build()

      val accelerateStatus = s3Client(account.CHUNKS_BUCKET).getBucketAccelerateConfiguration(getBucketAccelerateConfigurationRequest)
                                                      .status()

      logger.info(account.CHUNKS_BUCKET + " accelerate status: " + accelerateStatus)
    }
    catch {
      case t:
        SdkException => logger.warn(s"enableAccelerationModeForChunksBucket ${account.CHUNKS_BUCKET} ${t.getMessage}")
    }

  }
  
  // http://docs.aws.amazon.com/AmazonS3/latest/dev/DeletingOneObjectUsingJava.html
  /**
   * Deletes a single object from Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param objectKey the object key
   */
  def deleteObjectFromAmazon(bucketName: String, objectKey: String): Unit = {
    try {
      val objectId = ObjectIdentifier.builder()
        .key(objectKey)
        .build()

      val del = Delete.builder.objects(objectId).quiet(false).build

      val objectDeleteRequest = DeleteObjectsRequest.builder.bucket(bucketName).delete(del).build
      val response = s3Client(bucketName).deleteObjects(objectDeleteRequest)
      checkDeleteObjectsErrors(bucketName, response)
    }
    catch {
      case _: NoSuchKeyException =>
        logger.warn(s"DELETE ${bucketName} -- ${objectKey}: key not found")
      case t: SdkException =>
        logger.error(s"DELETE ${bucketName} -- ${objectKey}", t)
        throw t
    }
  }

  /**
   * Deletes multiple objects from Amazon S3.
   *
   * Missing keys are ignored; other provider errors are propagated.
   *
   * @param bucketName the S3 bucket name
   * @param objectKeys the list of object keys to delete
   */
  def deleteObjectsFromAmazon(bucketName: String, objectKeys: List[String]): Unit = {
    val objectIds: List[ObjectIdentifier] = objectKeys.map(ObjectIdentifier.builder().key(_).build())

    val del = Delete.builder.objects(objectIds.asJava).quiet(false).build

    val multipleObjectsDeleteRequest = DeleteObjectsRequest.builder.bucket(bucketName).delete(del).build
    val response = s3Client(bucketName).deleteObjects(multipleObjectsDeleteRequest)
    checkDeleteObjectsErrors(bucketName, response)
  }

  private def checkDeleteObjectsErrors(bucketName: String, response: DeleteObjectsResponse): Unit = {
    val errors = Option(response.errors()).map(_.asScala.toList).getOrElse(Nil)
      .filterNot(e => e.code() == "NoSuchKey" || e.code() == "NotFound")
    if (errors.nonEmpty) {
      val detail = errors.map(e => s"${e.key}:${e.code}:${e.message}").mkString("; ")
      throw new RuntimeException(s"DELETE $bucketName failed: $detail")
    }
  }

  /**
   * Retrieves user-defined custom metadata for an object stored in Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param objectKey the key of the target object
   * @return a mutable map of metadata keys to their values
   */
  def getAmazonMetadata(bucketName: String, objectKey: String): mutable.Map[String, String] = {

    val headObjectRequest = HeadObjectRequest.builder()
      .bucket(bucketName)
      .key(objectKey)
      .build()

    s3Client(bucketName).headObject(headObjectRequest).metadata().asScala
  }
  
  // TODO: we also will read it without reading subdirectories like in: 
  // http://codereview.stackexchange.com/questions/6847/list-objects-in-a-amazon-s3-folder-without-also-listing-objects-in-sub-folders
  /**
   * Retrieves a list of object keys and their last modified dates matching a prefix in an S3 bucket.
   *
   * @param bucketName the S3 bucket name
   * @param prefix the object prefix
   * @return an Iterable of tuples containing the object key and its last modified Date
   */
  def getAmazonListWithDetails(bucketName: String, prefix: String): java.lang.Iterable[(String, Date)] = {
    val listObjectsRequest = ListObjectsRequest
      .builder()
      .bucket(bucketName)
      .prefix(prefix)
      .build()

    val objectListing = s3Client(bucketName).listObjects(listObjectsRequest)
        
    val list = objectListing.contents().asScala.
      map { s3ObjectSummary => (s3ObjectSummary.key(), Date.from(s3ObjectSummary.lastModified())) }.asJava

    list
  }

  /**
   * Retrieves a list of object keys matching a prefix under a user's namespace in an S3 bucket,
   * without details. Supports paging, delimiter-based nested hierarchy, and range bounds.
   *
   * @param bucketName the S3 bucket name
   * @param user the user namespace prefix folder
   * @param prefix the object prefix under the namespace
   * @param useFlatBlobListing true for recursive flat listing; false to list immediate child levels with delimiter
   * @param startAfter optional key bound for pagination
   * @param endBefore optional cutoff key bound
   * @return an Iterator of relative object paths
   */
  def getAmazonListWithoutDetails(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null): Iterator[String] = {

    var listObjectsV2Request = ListObjectsV2Request
      .builder()
      .bucket(bucketName)
      .prefix(user + "/" + prefix)
      .delimiter(if (useFlatBlobListing == false) {"/"} else {null})
      .build()

// it cannot work as we have a tag besides of time
//    if (startAfter != null) {
//      req.setStartAfter(user + "/" + startAfter)
//    }

    if (useFlatBlobListing == false) {
      listObjectsV2Request = listObjectsV2Request.toBuilder().delimiter("/").build()
    }

    logger.debug(s"getAmazonListWithoutDetails START ${bucketName}/${user}/${prefix} startAfter: " + startAfter + " endBefore: " + endBefore)
    
      var result = s3Client(bucketName).listObjectsV2(listObjectsV2Request)
      var list = result.contents().asScala.map { s3ObjectSummary => s3ObjectSummary.key().substring(user.length + 1) }
      val commonPrefixes = result.commonPrefixes().asScala.map { dirName => dirName.prefix().substring(user.length + 1, dirName.prefix().length() - 1) }

      list = commonPrefixes ++ list
          
      // create Iterable
      val iterator = new Iterator[String] {
        var isInProcess = true
        
        /**
         * Checks if there is a next element in the iterator.
         *
         * @return true if there is a next element, false otherwise
         */
        def hasNext: Boolean = {
          if (list.isEmpty && result.isTruncated()) {
            listObjectsV2Request = listObjectsV2Request.toBuilder()
              .continuationToken(result.nextContinuationToken())
              .build()
              
            result = s3Client(bucketName).listObjectsV2(listObjectsV2Request)
            list = result.contents().asScala.map { s3ObjectSummary => s3ObjectSummary.key().substring(user.length + 1) }

            logger.debug(s"getAmazonListWithoutDetails ${bucketName}/${user}/${prefix} startAfter: ${startAfter} is truncated: " + result.isTruncated() + " list(0): " + list(0))
          }
          
          if (!list.isEmpty) {
            val nextElement = list(0)
                        
            // if File
            if (nextElement.contains(AltaStataFileSystem.FILE_MARK_SIGN)) {
              val createdTime = nextElement.split(AltaStataFileSystem.FILE_MARK_SIGN)(1).split("_")(1)
              
              if ((startAfter == null || createdTime.compareTo(startAfter) >= 0) && 
                (endBefore == null || createdTime.compareTo(endBefore) <= 0)) {
                  return true
                }
                else {
                  return false
                }
            } // if Directory
            else {
              return true
            }
          }
          else {
            logger.debug(s"getAmazonListWithoutDetails END ${bucketName}/${user}/${prefix} startAfter: ${startAfter}")
            return false
          }
        }
        
        /**
         * Returns the next element in the iterator.
         *
         * @return the next object key
         */
        def next(): String = {
          list.remove(0)
        }
      }

      iterator
  }
  
  /**
   * TODO: it also should be implicit owner, version and stored file size that we have to put into the file metadata
   * Lambda function should ignore all the shared files metadata (based on owner field) and not to put them to the big file list
   * Lambda function should calculate the total size of all the existing versions to charge the customer for them
   * Lambda function should know the stored file size
   */
  def storeInAmazon(array: Array[Byte], bucketName: String, objectKey: String, size: Long) = Future {
    logger.trace(s"\tAmazon STORE START: ${bucketName} -- ${objectKey} this: " + this)

    val myCatch = catching(classOf[Throwable]).withApply(t => throw new StoreCloudObjectException(s"Amazon ${bucketName} ${objectKey}", t))

    myCatch {
      // we need it for multipart buffer to know what was the original size when we merge it back
      val metadata = HashMap(Constants.METADATAHEADER_OBJECTSIZE -> size.toString)

      logger.trace(s"\tAmazon STORE START: ${bucketName} -- ${objectKey} ${array.length}")

      val putObjectRequest = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(objectKey)
        .metadata(metadata.asJava)
        .build();

      // ByteArrayInputStream is mark-supported so SDK retries re-read without
      // RequestBody.fromBytes copying the 8 MiB chunk. Manual sleep+retry used to
      // overlap a timed-out PUT with a second body still on the heap.
      s3Client(bucketName).putObject(
        putObjectRequest,
        RequestBody.fromInputStream(new ByteArrayInputStream(array), array.length))

      logger.trace(s"\tAmazon STORE END: ${bucketName} -- ${objectKey}")
    }
  } (ecAmazonObjectUploadOps)

  /**
   * Asynchronously retrieves a byte array payload of an object from Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param objectKey the target object key
   * @return a Future wrapping the retrieved object data bytes
   */
  def retrieveFromAmazon(bucketName: String, objectKey: String) = Future {
      val cs: Buffer[Closeable] = new ArrayBuffer();
      /**
       * Adds a closeable resource to a buffer and returns it.
       *
       * @param c the closeable resource
       * @tparam C the type of the closeable resource
       * @return the closeable resource
       */
      def addClose[C <: Closeable](c: C) = { cs += c; c; }

      val myCatch = catching(classOf[Throwable]).withApply(t => {logger.debug(s"retrieveFromAmazon: ${bucketName}/${objectKey}", t); throw new RetrieveCloudObjectException(s"Amazon ${bucketName} ${objectKey}", t)})
      .andFinally(cs.foreach(c => c.close()))
  
      myCatch {
        logger.trace(s"\tAmazon RETRIEVE START: ${bucketName} -- ${objectKey}")

        val objectRequest = GetObjectRequest
          .builder()
          .key(objectKey)
          .bucket(bucketName)
          .build()

        var ins = addClose(s3Client(bucketName).getObject(objectRequest))
        // Content-Length is on the GET response — no extra round trip.
        var outs = addClose(Constants.byteArrayOutputStreamForCloudRetrieve(bucketName, ins.response().contentLength()))
        try {
          BasicIO.transferFully(ins, outs)
        } catch {
          case ex: Exception =>
            logger.warn("futureRetrieveAmazon", ex)
            throw ex
        }

        val res = outs.toByteArray()

        logger.trace(s"\tAmazon RETRIEVE END: ${bucketName} -- ${objectKey}: " + res.length)

        ins.close
        outs.close

        res
      }
    } (ecAmazonObjectDownloadOps)

  /**
   * Helper mock or placeholder method to simulate storing a local file to Amazon S3.
   *
   * @param bucketName the S3 bucket name
   * @param file the local file to store
   * @param objectKey the target object key
   * @return a Try wrapping the object key on success
   */
  def storeInAmazon(bucketName: String, file: File, objectKey: String): Try[String] = Try {
    objectKey
  }

  /**
   * Helper mock or placeholder method to simulate retrieving an S3 object to a local file.
   *
   * @param bucketName the S3 bucket name
   * @param file the local target destination file
   * @param objectKey the S3 object key
   * @return a Try wrapping the object key on success
   */
  def retrieveFromAmazon(bucketName: String, file: File, objectKey: String): Try[String] = Try {
    objectKey
  }

  // Use global cloud object thread pools with correct priority
  private val ecAmazonObjectUploadOps = OpsExecutors.ecCloudObjectUploadOps
  private val ecAmazonObjectDownloadOps = OpsExecutors.ecCloudObjectDownloadOps

}
