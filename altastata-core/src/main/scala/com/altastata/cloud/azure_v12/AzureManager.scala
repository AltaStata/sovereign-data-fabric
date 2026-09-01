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

import com.altastata.filesystem.{RetrieveCloudObjectException, StoreCloudObjectException}
import com.altastata.filesystem.securecloud.OpsExecutors
import com.altastata.utils.{Account, Constants}
import com.azure.core.util.BinaryData
import com.azure.storage.blob.models.{BlobItem, BlobListDetails, BlobProperties, BlobStorageException, ListBlobsOptions}
import com.azure.storage.blob.sas.{BlobContainerSasPermission, BlobServiceSasSignatureValues}
import com.azure.storage.blob._
import com.azure.storage.common.Utility.{urlDecode, urlEncode}
import com.azure.storage.common.policy.{RequestRetryOptions, RetryPolicyType}
import org.slf4j.LoggerFactory
import org.apache.commons.lang3.concurrent.BasicThreadFactory

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, Closeable, File}
import java.net.{URI, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.{Duration, OffsetDateTime}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.{Date, EnumSet}
import scala.collection._
import scala.collection.mutable.{ArrayBuffer, Buffer, HashMap}
import scala.concurrent._
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.Exception.catching

case class AzureManagerContainerNotFoundException (intend: String)
  extends RuntimeException(s"${intend}")

class AzureManager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val deleteNotFoundCounter = new AtomicLong(0L)

  // TODO: create the algorithm that can optimize it on the fly
  val OBJECT_UPLOAD_THREAD_POOL_SIZE = 100
  val OBJECT_DOWNLOAD_THREAD_POOL_SIZE = 100
  
  val baseurl = account.userProps.getProperty("azure-account")

  var SASs = HashMap[String, String]()

  /**
   * Initializes the AzureManager, loading and parsing any personal or personal decrypted Shared Access Signature (SAS) tokens.
   */
  def init(): Unit = {
    logger.trace("AzureManager init for user: " + account.MY_USER)
    
    SASs.clear()
    
    for (prop <- account.userProps.keys().asScala.asInstanceOf[Iterator[String]]) {
      
        if (prop.startsWith("sas-")) {
          if (account.MY_USER == "admin") {
            SASs += (prop -> account.userProps.getProperty(prop))
          }
          else {
            SASs += (prop -> account.getAndDecryptProperty(prop))
          }
      }
    }
  }
  
  private def getContainerSAS(containerName: String): String = {
    if (account.MY_USER == "admin") {
      try {
        return account.userProps.getProperty("sas-" + containerName)
      }
      catch {
        case t: Throwable => throw new AzureManagerContainerNotFoundException(s"No SAS found by admin - ${containerName}.")
      }
    }
    else if (containerName.endsWith(account.MY_USER) || containerName.endsWith("users-all") ) {
      try {
        return SASs.get("sas-" + containerName).get
      }
      catch {
        case t: Throwable => throw new AzureManagerContainerNotFoundException(s"No SAS found for myself ${account.MY_USER} - ${containerName}.")
      }
    }
    else {
         // Custodian uses UserAttributesForCustodian (catalog DELETE, dataattributes manage) for other users' containers
        if (account.MY_USER == account.CUSTODIAN_USER && containerName.contains("catalog-")) {
          val userId = containerName.substring(containerName.indexOf("catalog-") + "catalog-".length)
          val sas = account.fileSystemModel.retrieveUserAttributesForCustodian(userId).flatMap(_.catalogSas)
          logger.trace(s"\tgetContainerSAS custodian catalog: ${containerName} -> ${sas.isDefined}")
          if (sas.isDefined) return sas.get
        }
        if (account.MY_USER == account.CUSTODIAN_USER && containerName.contains("dataattributes-")) {
          val userId = containerName.substring(containerName.indexOf("dataattributes-") + "dataattributes-".length)
          val sas = account.fileSystemModel.retrieveUserAttributesForCustodian(userId).flatMap(_.dataattributesSas)
          logger.trace(s"\tgetContainerSAS custodian dataattributes: ${containerName} -> ${sas.isDefined}")
          if (sas.isDefined) return sas.get
        }
         // if this is different user chunks container
        if (containerName.contains("-chunks")) {
          val userNameForChunksContainer = containerName.substring(containerName.indexOf("chunks-") + "chunks-".length)
          
          val sas = account.fileSystemModel.retrieveUserdata(userNameForChunksContainer).readOnlyChunksSAS

          logger.trace(s"\tfindSasBlob userNameForChunksContainer: ${containerName} -> ${sas.isDefined}")
          
          return sas.getOrElse(null)
        }
        else if (containerName.contains("dataattributes")) {
          val userNameForAccessContainer = containerName.substring(containerName.indexOf("dataattributes-") + "dataattributes-".length)
          
          val sas = account.fileSystemModel.retrieveUserdata(userNameForAccessContainer).readOnlyDataAttributesSAS

          logger.trace(s"\tfindSasBlob userNameForAccessContainer: ${containerName} -> ${sas.isDefined}")
          
          return sas.getOrElse(null)
        }
        else if (containerName.contains("changes")) {
          val userNameForChangesContainer = containerName.substring(containerName.indexOf("changes-") + "changes-".length)
          
          val sas = account.fileSystemModel.retrieveUserdata(userNameForChangesContainer).writeOnlyChangesSAS

          logger.trace(s"\tfindSasBlob userNameForChangesContainer: ${containerName} -> ${sas.isDefined}")
          
          return sas.getOrElse(null)
        }
        else {
          logger.error(s"getContainerSAS cannot find SAS for ${containerName}")
        
          throw new AzureManagerContainerNotFoundException(s"No SAS found for ${containerName}.")
        }
    }    
  }

  /**
   * Deletes a blob from Azure Blob Storage. Catches 404/Not Found gracefully.
   *
   * @param containerName the target container name
   * @param blobName the blob item key to delete
   */
  def deleteObjectFromAzure(containerName: String, blobName: String): Unit = {
    try {
      logger.trace(s"\tAzure DELETE START: ${containerName} -- ${blobName}")
        
      val isChunks = containerName.contains("-chunks")
      val sasBlob = findCloudBlockBlob(containerName, blobName, isChunks)
      // One request per object: delete directly and treat 404 as "not found" (still WARN by request).
      sasBlob.delete()
      logger.trace(s"\tAzure DELETE END: ${containerName} -- ${blobName}")
    }
    catch {
      case e: BlobStorageException if e.getStatusCode == 404 =>
        val n = deleteNotFoundCounter.incrementAndGet()
        // Keep WARN severity (requested), but throttle volume to avoid console I/O bottleneck.
        if (n <= 20 || n % 100 == 0) {
          logger.warn(s"\tAzure DELETE Blob not found (#${n}): ${containerName} -- ${blobName}")
        }
      case t: Throwable =>
        logger.error(s"DELETE ${containerName} -- ${blobName}", t)
        throw t
    }
  }
  
  /**
   * Asynchronously uploads a byte array payload to Azure Blob Storage under the given container and key.
   *
   * @param array the payload byte array
   * @param containerName the target container name
   * @param blobName the target blob key (path)
   * @return a Future representing the asynchronous upload operation
   */
  def storeInAzure(array: Array[Byte], containerName: String, blobName: String) = Future {
      // Make sure we close the inFileChannel
      val cs: Buffer[Closeable] = new ArrayBuffer();
      /**
       * Adds a closeable resource to a buffer and returns it.
       *
       * @param c the closeable resource
       * @tparam C the type of the closeable resource
       * @return the closeable resource
       */
      def addClose[C <: Closeable](c: C) = { cs += c; c; }
            
      val myCatch = catching(classOf[Throwable]).withApply(t => throw new StoreCloudObjectException(s"Azure ${containerName} ${blobName}", t)).andFinally(cs.foreach(c => c.close()))
                    
      myCatch {
        
        val isChunks = containerName.contains("-chunks")
        val sasBlob = findCloudBlockBlob(containerName, blobName, isChunks)

        logger.trace(s"\tAzure STORE START: ${containerName} -- ${blobName} ${array.length}")

        // Chunks: stream the caller's array (no BinaryData.fromBytes extra copy of 8 MiB).
        // System (catalog / attributes): fromBytes — small objects, lower stream overhead.
        // SDK RequestRetryOptions already retry; a second overlapping upload used to
        // keep the first Netty body in memory after a 60s timeout.
        if (isChunks) {
          val inputStream = addClose(new ByteArrayInputStream(array))
          sasBlob.upload(inputStream, array.length, true)
        } else {
          sasBlob.upload(BinaryData.fromBytes(array), true)
        }

        logger.trace(s"\tAzure STORE END: ${containerName} -- ${blobName}")
      }
    } (ecAzureObjectUploadOps)
  
  /**
   * Asynchronously retrieves a stored byte array payload from Azure Blob Storage.
   *
   * @param containerName the target container name
   * @param blobName the target blob key to retrieve
   * @return a Future wrapping the retrieved object bytes
   */
  def retrieveFromAzure(containerName: String, blobName: String) = Future {    
      val cs: Buffer[Closeable] = new ArrayBuffer();
      /**
       * Adds a closeable resource to a buffer and returns it.
       *
       * @param c the closeable resource
       * @tparam C the type of the closeable resource
       * @return the closeable resource
       */
      def addClose[C <: Closeable](c: C) = { cs += c; c; }

      val myCatch = catching(classOf[Throwable]).withApply(t => throw new RetrieveCloudObjectException(s"Azure ${containerName} ${blobName}", t))
      .andFinally(cs.foreach(c => c.close()))
  
      myCatch {
        logger.trace(s"\tAzure RETRIEVE START: ${containerName} -- ${blobName}")

        val isChunks = containerName.contains("-chunks")
        val sasBlob = findCloudBlockBlob(containerName, blobName, isChunks)

        // Stream GET into one BAOS. downloadContent.toBytes kept a BinaryData
        // plus a second byte[] copy of every chunk.
        val res = try {
          val outs = addClose(Constants.byteArrayOutputStreamForCloudRetrieve(containerName, -1L))
          sasBlob.downloadStream(outs)
          outs.toByteArray
        } catch {
          case ex: Exception =>
            logger.warn(s"futureRetrieveAzure ${containerName} ${blobName}", ex)
            throw ex
        }

        logger.trace(s"\tAzure RETRIEVE END: ${containerName} -- ${blobName}: " + res.length)

        res
      }
    } (ecAzureObjectDownloadOps)

  /**
   * TODO: https://github.com/Azure/azure-storage-java/issues/128  
   */
  /**
   * Lists the blob names matching a prefix in an Azure container without details.
   *
   * @param containerName the target container name
   * @param prefix the filtering prefix
   * @param useFlatBlobListing true to use flat recursive listing; false for directory hierarchies
   * @return a Java Iterator of listed blob names
   */
  def getAzureListWithoutDetails(containerName: String, prefix: String, useFlatBlobListing: Boolean): java.util.Iterator[String] = {
    val containerClient: BlobContainerClient = findCloudBlobContainer(containerName)

    logger.trace("getAzureListWithoutDetails: " + containerClient.getBlobContainerName() + " " + prefix + " " + useFlatBlobListing)

    // Metadata needed to detect HNS folder markers (hdi_isfolder); hierarchy prefixes stay.
    val listOptions = new ListBlobsOptions()
      .setPrefix(prefix)
      .setDetails(new BlobListDetails().setRetrieveMetadata(true))
    val timeout = Duration.ofSeconds(180)

    // List blobs based on flat or hierarchical listing
    val blobs = if (useFlatBlobListing) {
      containerClient.listBlobs(listOptions, timeout).asScala
    } else {
      containerClient.listBlobsByHierarchy("/", listOptions, timeout).asScala
    }

    // Skip HNS folder-marker blobs; keep hierarchy prefixes (directories) like S3 commonPrefixes.
    blobs
      .filterNot(isAzureHnsFolderMarker)
      .map(azureListEntryName)
      .asJava.iterator()
  }
  
  /**
   * Lists the blobs in an Azure container, retrieving metadata details such as last modified date.
   *
   * @param containerName the target container name
   * @param prefix the filtering prefix
   * @param useFlatBlobListing true for flat recursive listing; false for nested hierarchies
   * @return an Iterable of tuples containing the blob name and its last modified Date
   */
  def getAzureListWithDetails(containerName: String, prefix: String, useFlatBlobListing: Boolean): Iterable[(String, Date)] = {
    val containerClient: BlobContainerClient = findCloudBlobContainer(containerName)

    logger.info("getAzureListWithDetails: " + containerClient.getBlobContainerName() + " " + prefix + " " + useFlatBlobListing)

    val listOptions = new ListBlobsOptions()
      .setPrefix(prefix)
      .setDetails(new BlobListDetails().setRetrieveMetadata(true))
    val timeout = Duration.ofSeconds(180)

    // List blobs based on flat or hierarchical listing
    val blobs = if (useFlatBlobListing) {
      containerClient.listBlobs(listOptions, timeout).asScala
    } else {
      containerClient.listBlobsByHierarchy("/", listOptions, timeout).asScala
    }

    // Map each entry to (name, last modified). Hierarchy prefixes have no blob properties.
    blobs.collect {
      case blobItem: BlobItem if !isAzureHnsFolderMarker(blobItem) =>
        if (blobItem.isPrefix) {
          (azureListEntryName(blobItem), new Date(0L))
        } else {
          val blobClient = containerClient.getBlobClient(blobItem.getName)
          val props: BlobProperties = blobClient.getProperties()
          (azureListEntryName(blobItem), Date.from(props.getLastModified.toInstant()))
        }
    }
  }

  /**
   * HNS zero-byte folder markers (`hdi_isfolder=true`) are not catalog files and must be skipped
   * (they broke share/event processing when treated as objects). Hierarchy prefixes from
   * `listBlobsByHierarchy` are real directories and must be kept — same role as S3 commonPrefixes.
   */
  private def isAzureHnsFolderMarker(blobItem: BlobItem): Boolean = {
    if (blobItem.isPrefix) {
      false
    } else {
      val meta = blobItem.getMetadata
      meta != null && Option(meta.get("hdi_isfolder")).exists(_.equalsIgnoreCase("true"))
    }
  }

  /** Decode blob/prefix name; strip trailing `/` from hierarchy prefixes. */
  private def azureListEntryName(blobItem: BlobItem): String = {
    val name = urlDecode(blobItem.getName)
    if (blobItem.isPrefix && name.endsWith("/")) name.substring(0, name.length - 1) else name
  }

  private def findCloudBlockBlob(containerName: String, blobName: String, isChunks: Boolean = false): BlobClient = {
    val containerSAS = getContainerSAS(containerName)

    // Build the SAS-based URI for the blob
    val encodedContainerName = urlEncode(containerName)
    val encodedBlobName = urlEncode(blobName)

    // Construct the full URI with SAS token
    val uri = new URI(s"$baseurl/$encodedContainerName/$encodedBlobName?$containerSAS")

    logger.trace(s"\tfindCloudBlockBlob: container=${containerName}, blob=${blobName}, sasPresent=${containerSAS != null && containerSAS.nonEmpty}")

    val client = if (isChunks) AzureHttpClients.chunksHttpClient else AzureHttpClients.systemHttpClient

    // Create a BlobClient using the SAS-based URI
    val blobClient = new BlobClientBuilder()
      .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, 5, 180, null, null, null))
      .endpoint(uri.toString)
      .httpClient(client)
      .buildClient()

    // Create a blob using the URI that contains the shared access signature.
    blobClient
  }
  
  private def findCloudBlobContainer(containerName: String): BlobContainerClient = {
    val containerSAS = getContainerSAS(containerName)

    val encodedContainerName = urlEncode(containerName)

    // Construct the full URI with SAS token
    val uri = new URI(s"$baseurl/$encodedContainerName?$containerSAS")

    logger.trace(s"\tfindCloudBlobContainer: container=${containerName}, sasPresent=${containerSAS != null && containerSAS.nonEmpty}")

    // Create a BlobClient using the SAS-based URI
    val blobContainerClient = new BlobContainerClientBuilder()
      .endpoint(uri.toString)
      .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, 5, 60, null, null, null))
      .httpClient(AzureHttpClients.systemHttpClient)
      .buildClient()

    blobContainerClient
  }
  
  /**
   * Sync upload of a local file to Azure Blob Storage.
   *
   * @param containerName the target container name
   * @param localFilePath the path to the local file to upload
   * @param objectKey the target storage blob key
   * @return a Try wrapping the object key on success
   */
  def storeInAzure(containerName: String, localFilePath: String, objectKey: String): Try[String] = Try {
    val isChunks = containerName.contains("-chunks")
    val blob = findCloudBlockBlob(containerName, objectKey, isChunks)

    blob.uploadFromFile(localFilePath)
    
    objectKey
  }
  
  /**
   * Sync download of a stored blob to a local target file.
   *
   * @param containerName the target container name
   * @param localFilePath the path to the local destination file
   * @param objectKey the target storage blob key to download
   * @return a Try wrapping the object key on success
   */
  def retrieveFromAzure(containerName: String, localFilePath: String, objectKey: String): Try[String] = Try {
    val isChunks = containerName.contains("-chunks")
    val blob = findCloudBlockBlob(containerName, objectKey, isChunks)

    blob.downloadToFile(localFilePath)
    
    objectKey
  }
  
  /**
   * Retrieves custom metadata properties of a stored blob in Azure.
   *
   * @param containerName the target container name
   * @param objectKey the target storage blob key
   * @return a mutable map of metadata keys to values
   */
  def getAzureMetadata(containerName: String, objectKey: String): mutable.Map[String, String] = {
    val isChunks = containerName.contains("-chunks")
    val blob = findCloudBlockBlob(containerName, objectKey, isChunks)

    blob.getProperties().getMetadata().asScala
  }

  // Use global cloud object thread pools with correct priority
  private val ecAzureObjectUploadOps = OpsExecutors.ecCloudObjectUploadOps
  private val ecAzureObjectDownloadOps = OpsExecutors.ecCloudObjectDownloadOps

  /**
   * Generates a delegation SAS token for an entire Azure Blob Container with full permissions.
   * If the container doesn't exist, it is created.
   *
   * @param containerName the target container name to delegate access to
   * @return the generated container SAS token query string
   */
  def generateContainerSAS(containerName: String): String = {
    // Use the connection string to create the BlobServiceClient
    val connectionString = account.userProps.getProperty("adminStorageConnectionString")
    val blobServiceClient = new BlobServiceClientBuilder()
      .connectionString(connectionString)
      .buildClient()

    // Get the container client
    val containerClient = blobServiceClient.getBlobContainerClient(containerName)

    // Create the container if it doesn't exist
    if (!containerClient.exists()) {
      containerClient.create()
    }

    // Define SAS permissions
    val permissions = new BlobContainerSasPermission()
      .setReadPermission(true)
      .setWritePermission(true)
      .setListPermission(true)
      .setDeletePermission(true)

    // Set SAS start and expiry times
    val startTime = OffsetDateTime.now().minusDays(1) // Start time in case of clock skew
    val expiryTime = OffsetDateTime.now().plusYears(10) // Set SAS expiry time to 10 years

    // Create the SAS token settings
    val sasSignatureValues = new BlobServiceSasSignatureValues(expiryTime, permissions)
      .setStartTime(startTime)

    // Generate the SAS token for the container
    val sasToken = containerClient.generateSas(sasSignatureValues)

    sasToken // Return the SAS token
  }
}

object AzureURLGenerator {
  
  /**
   * Main entry point to generate a container SAS token for testing purposes.
   *
   * @param args command line arguments
   */
  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: AzureManager <account-properties-name-or-path> <container-name>")
      System.exit(1)
    }
    val account = new Account()
    val path = if (new File(args(0)).isAbsolute) args(0) else Account.ALTASTATA_ACCOUNTS_HOME + File.separator + args(0)
    account.loadAccountProperties(path)

    new AzureManager()(account).generateContainerSAS(args(1))
    println("Container SAS generated successfully")
  }

}

