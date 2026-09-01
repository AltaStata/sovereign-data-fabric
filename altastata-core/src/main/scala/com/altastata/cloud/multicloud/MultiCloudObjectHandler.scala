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

package com.altastata.cloud.multicloud

import com.altastata.filesystem.securecloud.CloudObjectHandler
import com.altastata.utils.Account
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext
import scala.util.Try

/**
 * Composite Cloud Object Handler for Multi-Cloud Data Sovereignty.
 * Routes operations to different cloud providers based on the bucket purpose.
 */
class MultiCloudObjectHandler(implicit account: Account) extends CloudObjectHandler {
  
  private val logger = LoggerFactory.getLogger(getClass)

  private val metadataProvider = account.getProperty("multicloud.metadata.provider")
  private val dataProvider = account.getProperty("multicloud.data.provider")

          if (metadataProvider == null || dataProvider == null) {
            throw new IllegalArgumentException("MultiCloud mode requires 'multicloud.metadata.provider' and 'multicloud.data.provider' properties")
          }

          logger.info(s"Initializing MultiCloudObjectHandler. Metadata: $metadataProvider, Data: $dataProvider")

  // Instantiate handlers dynamically
  private val metadataHandler = createHandler(metadataProvider)
  private val dataHandler = createHandler(dataProvider)
  
  /**
   * Retrieves the handler configured for metadata storage (e.g., catalog, users, changes).
   */
  def getMetadataHandler: CloudObjectHandler = metadataHandler

  /**
   * Retrieves the handler configured for data storage (e.g., ciphertext chunks, data attributes).
   */
  def getDataHandler: CloudObjectHandler = dataHandler

  /**
   * Instantiates the cloud object storage handler corresponding to the provider type string.
   */
  private def createHandler(providerType: String): CloudObjectHandler = {
    providerType match {
      case "localfs-secure" => new com.altastata.cloud.localfs.LocalFSCloudObjectHandler()(account)
      case "amazon-s3-secure" | "amazon-s3-cof-secure" => new com.altastata.cloud.amazon_java2.AmazonCloudObjectHandler()(account)
      case "azure-secure" => new com.altastata.cloud.azure_v12.AzureCloudObjectHandler()(account)
      case "ibm-cos-secure" => new com.altastata.cloud.ibm.IBMCloudObjectHandler()(account)
      case "minio-secure" => new com.altastata.cloud.minio.MinIOCloudObjectHandler()(account)
      case "fusion-secure" => new com.altastata.cloud.fusion.FusionCloudObjectHandler()(account)
      case "google-secure" => {
        // Reflective load: see Account.cloudObjectHandler (google-secure) for -PnoGCP compile-time exclusion.
        val clazz = Class.forName("com.altastata.cloud.google.GoogleCloudObjectHandler")
        val constructor = clazz.getDeclaredConstructor(classOf[Account])
        constructor.newInstance(account).asInstanceOf[CloudObjectHandler]
      }
      case other => throw new IllegalArgumentException(s"Unsupported multicloud provider: $other")
    }
  }

  /**
   * Resolves the appropriate cloud object handler based on the bucket name.
   * Catalog, Changes, and Users buckets route to the metadata provider.
   * Chunks and Data Properties buckets route to the data provider.
   * 
   * @param bucketName The name of the storage bucket
   * @return The routed CloudObjectHandler
   */
  private def getHandlerForBucket(bucketName: String): CloudObjectHandler = {
    if (bucketName == account.CATALOG_BUCKET || 
        bucketName == account.CHANGES_BUCKET ||
        bucketName == account.USERS_BUCKET) {
      metadataHandler
    } else {
      // account.CHUNKS_BUCKET, account.DATA_PROPERTIES_BUCKET
      dataHandler
    }
  }

  override def storeObjectToCloud(buffer: Array[Byte], bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = {
    getHandlerForBucket(bucketName).storeObjectToCloud(buffer, bucketName, userInBucket, objectKey)
  }
  
  override def retrieveObjectSizeFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Int] = {
    getHandlerForBucket(bucketName).retrieveObjectSizeFromCloud(bucketName, userInBucket, objectKey)
  }

  override def listObjectsAtCloud(bucketName: String, user: String, prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null)(implicit ec: ExecutionContext): Try[java.util.Iterator[String]] = {
    getHandlerForBucket(bucketName).listObjectsAtCloud(bucketName, user, prefix, useFlatBlobListing, startAfter, endBefore)
  }
  
  override def retrieveObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Array[Byte]] = {
    getHandlerForBucket(bucketName).retrieveObjectFromCloud(bucketName, userInBucket, objectKey)
  }
  
  override def deleteObjectFromCloud(bucketName: String, userInBucket: String, objectKey: String)(implicit ec: ExecutionContext): Try[Unit] = {
    getHandlerForBucket(bucketName).deleteObjectFromCloud(bucketName, userInBucket, objectKey)
  }
}
