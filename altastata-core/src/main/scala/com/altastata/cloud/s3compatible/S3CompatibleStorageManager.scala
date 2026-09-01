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

import com.altastata.filesystem.{RetrieveCloudObjectException, StoreCloudObjectException}
import com.altastata.filesystem.securecloud.OpsExecutors
import com.altastata.utils.{Account, Constants}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.regions.Region
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.S3Configuration

import java.io.{ByteArrayInputStream, Closeable}
import java.time.Duration
import java.net.URI
import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, Buffer}
import scala.sys.process.BasicIO
import scala.util.control.Exception.catching
import scala.concurrent._
import scala.util.control.Breaks._

case class S3CompatibleContainerNotFoundException(provider: String, intent: String)
  extends RuntimeException(s"$provider: $intent")

/**
 * Base class for S3-compatible storage managers (IBM COS, Fusion, MinIO).
 * 
 * All three providers use the same S3 API with path-style access.
 * Subclasses only need to provide configuration properties.
 * 
 * Note: AmazonS3Manager is NOT based on this class because it has
 * additional complexity (Cognito, acceleration mode, etc.).
 */
abstract class S3CompatibleStorageManager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)

  // Abstract - each implementation provides its own config
  protected def providerName: String
  protected def endpointProperty: String
  protected def accessKeyProperty: String
  protected def secretKeyProperty: String

  // Overridable configuration with sensible defaults
  protected def connectionTimeout: Duration = Duration.ofSeconds(60)
  protected def socketTimeout: Duration = Duration.ofSeconds(60)
  protected def maxChunkConnections: Int = OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_CHUNKS
  protected def maxSystemConnections: Int = OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_SYSTEM
  protected def apiCallTimeout: Duration = Duration.ofSeconds(600)
  protected def apiCallAttemptTimeout: Duration = Duration.ofSeconds(180)
  protected def numRetries: Int = 5

  // S3 client
  protected var chunksS3Client: S3Client = null
  protected var systemS3Client: S3Client = null
  protected def s3Client(bucketName: String): S3Client = if (bucketName != null && bucketName.contains("-chunks")) chunksS3Client else systemS3Client
  protected def s3Client(): S3Client = systemS3Client

  /**
   * Get credentials - can be overridden for special handling (e.g., IBM admin vs user)
   */
  protected def getAccessKey: String = getCredential(accessKeyProperty)
  protected def getSecretKey: String = getCredential(secretKeyProperty)
  
  protected def getCredential(key: String): String = {
    try {
      account.getAndDecryptProperty(key)
    } catch {
      case _: Exception =>
        // Decryption failed - use as plain text
        logger.debug(s"$key: using as plain text (decryption failed)")
        account.getProperty(key)
    }
  }

  /**
   * Initializes the S3-compatible cloud storage client, setting up custom
   * HTTP clients, timeouts, override configurations, and endpoint connections.
   */
  def init(): Unit = {
    try {
      val endpoint = account.getProperty(endpointProperty)
      val accessKey = getAccessKey
      val secretKey = getSecretKey
      
      logger.info(s"Initializing $providerName S3 Client...")
      logger.info(s"Endpoint: $endpoint")
      logger.debug(s"Access key configured: ${accessKey != null && accessKey.nonEmpty}")

      val credentials = AwsBasicCredentials.create(accessKey, secretKey)

      val chunksApacheHttpClient = ApacheHttpClient.builder()
        .connectionTimeout(connectionTimeout)
        .socketTimeout(socketTimeout)
        .maxConnections(maxChunkConnections)
        .build()
        
      val systemApacheHttpClient = ApacheHttpClient.builder()
        .connectionTimeout(connectionTimeout)
        .socketTimeout(socketTimeout)
        .maxConnections(maxSystemConnections)
        .expectContinueEnabled(java.lang.Boolean.FALSE)
        .build()

      val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
        .apiCallTimeout(apiCallTimeout)
        .apiCallAttemptTimeout(apiCallAttemptTimeout)
        .retryPolicy(RetryPolicy.builder()
          .numRetries(numRetries)
          .build())
        .build()

      val s3Configuration = S3Configuration.builder()
        .pathStyleAccessEnabled(true)  // Required for all S3-compatible providers
        .build()

      chunksS3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)  // S3-compatible providers ignore this but SDK requires it
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .serviceConfiguration(s3Configuration)
        .httpClient(chunksApacheHttpClient)
        .overrideConfiguration(clientOverrideConfiguration)
        .build()

        systemS3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)  // S3-compatible providers ignore this but SDK requires it
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .serviceConfiguration(s3Configuration)
        .httpClient(systemApacheHttpClient)
        .overrideConfiguration(clientOverrideConfiguration)
        .build()

      logger.info(s"$providerName S3 Client initialized successfully")

    } catch {
      case t: Throwable =>
        logger.error(s"Failed to initialize $providerName client: ${t.getMessage}", t)
        throw new RuntimeException(s"Failed to initialize $providerName client: ${t.getMessage}", t)
    }
  }

  /**
   * Store object in bucket
   */
  def storeObject(buffer: Array[Byte], bucketName: String, objectKey: String, size: Long): Future[Unit] = {
    Future {
      val cs: Buffer[Closeable] = new ArrayBuffer()
      /**
       * Registers a closeable resource to be closed after the operation completes.
       *
       * @param c the closeable resource
       * @return the closeable resource
       */
      def addClose[C <: Closeable](c: C) = { cs += c; c }

      val myCatch = catching(classOf[Throwable])
        .withApply(t => throw new StoreCloudObjectException(s"$providerName $bucketName $objectKey", t))
        .andFinally(cs.foreach(c => c.close()))

      myCatch {
        logger.debug(s"$providerName STORE START: $bucketName -- $objectKey (${buffer.length} bytes)")

        val putObjectRequest = PutObjectRequest.builder()
          .bucket(bucketName)
          .key(objectKey)
          .contentLength(size)
          .build()

        s3Client(bucketName).putObject(
          putObjectRequest,
          RequestBody.fromInputStream(new ByteArrayInputStream(buffer), buffer.length))
        logger.debug(s"$providerName STORE SUCCESS: $bucketName -- $objectKey")
      }
    }(ecObjectUploadOps)
  }

  /**
   * Retrieve object from bucket
   */
  def retrieveObject(bucketName: String, objectKey: String): Future[Array[Byte]] = {
    Future {
      val cs: Buffer[Closeable] = new ArrayBuffer()
      /**
       * Registers a closeable resource to be closed after the operation completes.
       *
       * @param c the closeable resource
       * @return the closeable resource
       */
      def addClose[C <: Closeable](c: C) = { cs += c; c }

      val myCatch = catching(classOf[Throwable])
        .withApply(t => {
          logger.debug(s"$providerName retrieve error: $bucketName/$objectKey", t)
          throw new RetrieveCloudObjectException(s"$providerName $bucketName $objectKey", t)
        })
        .andFinally(cs.foreach(c => c.close()))

      myCatch {
        logger.debug(s"$providerName RETRIEVE START: $bucketName -- $objectKey")

        val getObjectRequest = GetObjectRequest.builder()
          .bucket(bucketName)
          .key(objectKey)
          .build()

        val ins = addClose(s3Client(bucketName).getObject(getObjectRequest))
        // Content-Length is on the GET response — no extra round trip.
        val outs = addClose(Constants.byteArrayOutputStreamForCloudRetrieve(bucketName, ins.response().contentLength()))

        try {
          BasicIO.transferFully(ins, outs)
        } catch {
          case ex: Exception =>
            logger.warn(s"$providerName retrieve error", ex)
            throw ex
        }

        val res = outs.toByteArray

        logger.trace(s"$providerName RETRIEVE END: $bucketName -- $objectKey: ${res.length}")

        ins.close()
        outs.close()

        res
      }
    }(ecObjectDownloadOps)
  }

  /**
   * Delete object from bucket (synchronous)
   */
  def deleteObject(bucketName: String, objectKey: String): Unit = {
    logger.debug(s"$providerName DELETE START: $bucketName -- $objectKey")

    val deleteObjectRequest = DeleteObjectRequest.builder()
      .bucket(bucketName)
      .key(objectKey)
      .build()

    try {
      s3Client(bucketName).deleteObject(deleteObjectRequest)
      logger.trace(s"$providerName DELETE END: $bucketName -- $objectKey")
    } catch {
      case _: NoSuchKeyException =>
        logger.warn(s"DELETE $bucketName -- $objectKey: key not found")
      case ex: Exception =>
        logger.warn(s"$providerName delete first attempt failed: ${ex.getMessage}")
        logger.warn(s"$providerName delete retry: $bucketName -- $objectKey")
        Thread.sleep(5000)
        try {
          s3Client(bucketName).deleteObject(deleteObjectRequest)
          logger.debug(s"$providerName DELETE SUCCESS (retry): $bucketName -- $objectKey")
        } catch {
          case _: NoSuchKeyException =>
            logger.warn(s"DELETE $bucketName -- $objectKey: key not found on retry")
          case retryEx: Exception =>
            logger.error(s"DELETE $bucketName -- $objectKey", retryEx)
            throw retryEx
        }
    }
  }

  /**
   * List objects in bucket with prefix support and pagination
   */
  def listObjects(bucketName: String, prefix: String, useFlatListing: Boolean): java.util.Iterator[String] = {
    try {
      logger.debug(s"$providerName LIST START: $bucketName -- prefix: $prefix -- flat: $useFlatListing")

      val listObjectsRequestBuilder = ListObjectsV2Request.builder()
        .bucket(bucketName)
        .prefix(if (prefix != null && prefix.nonEmpty) prefix else "")
        .maxKeys(1000)

      val listObjectsRequest = if (useFlatListing) {
        listObjectsRequestBuilder.build()
      } else {
        listObjectsRequestBuilder.delimiter("/").build()
      }

      var response = s3Client(bucketName).listObjectsV2(listObjectsRequest)
      val keys = new ArrayBuffer[String]()

      // Process all pages
      breakable {
        while (true) {
          // Collect common prefixes (directories) when not using flat listing
          if (!useFlatListing && response.hasCommonPrefixes) {
            response.commonPrefixes().asScala.foreach { commonPrefix =>
              val prefixStr = commonPrefix.prefix()
              if (prefixStr != null && prefixStr.nonEmpty) {
                keys += prefixStr
                logger.trace(s"   - found common prefix: '$prefixStr'")
              }
            }
          }

          // Add object keys from current page
          response.contents().asScala.foreach { obj =>
            val key = obj.key()
            if (key != null && key.nonEmpty) {
              keys += key
              logger.trace(s"   - found object key: '$key'")
            }
          }

          // Get next page if truncated
          if (response.isTruncated()) {
            val nextRequestBuilder = ListObjectsV2Request.builder()
              .bucket(bucketName)
              .prefix(if (prefix != null && prefix.nonEmpty) prefix else "")
              .continuationToken(response.nextContinuationToken())
              .maxKeys(1000)

            val nextRequest = if (useFlatListing) {
              nextRequestBuilder.build()
            } else {
              nextRequestBuilder.delimiter("/").build()
            }

            response = s3Client(bucketName).listObjectsV2(nextRequest)
          } else {
            break
          }
        }
      }

      logger.trace(s"$providerName LIST END: $bucketName -- found ${keys.size} objects")
      keys.iterator.asJava

    } catch {
      case ex: NoSuchBucketException =>
        logger.error(s"Bucket not found: $bucketName")
        throw S3CompatibleContainerNotFoundException(providerName, s"Bucket $bucketName not found")
      case ex: SdkException =>
        logger.error(s"Error listing objects in bucket '$bucketName' with prefix '$prefix': ${ex.getMessage}", ex)
        throw new RuntimeException(s"Failed to list objects in $providerName bucket '$bucketName' with prefix '$prefix': ${ex.getMessage}", ex)
    }
  }

  /**
   * Check if bucket exists
   */
  def bucketExists(bucketName: String): Boolean = {
    try {
      val headBucketRequest = HeadBucketRequest.builder()
        .bucket(bucketName)
        .build()

      s3Client(bucketName).headBucket(headBucketRequest)
      true
    } catch {
      case _: NoSuchBucketException => false
      case ex: SdkException =>
        logger.warn(s"Error checking bucket existence: $bucketName", ex)
        false
    }
  }

  // Use global cloud object thread pools
  private val ecObjectUploadOps = OpsExecutors.ecCloudObjectUploadOps
  private val ecObjectDownloadOps = OpsExecutors.ecCloudObjectDownloadOps
}

