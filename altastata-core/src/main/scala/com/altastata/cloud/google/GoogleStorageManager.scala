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

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.util.control.Exception.catching

import com.altastata.filesystem.RetrieveCloudObjectException
import com.altastata.filesystem.StoreCloudObjectException
import com.altastata.filesystem.securecloud.OpsExecutors
import com.altastata.utils.Account
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.apache.v2.ApacheHttpTransport
import com.google.api.gax.retrying.RetrySettings
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.StorageRetryStrategy
import org.apache.commons.io.IOUtils
import org.apache.http.NoHttpResponseException
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.socket.PlainConnectionSocketFactory
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

/** Unused legacy type; kept for compatibility — nothing in the codebase throws or catches this. */
case class GoogleManagerContainerNotFoundException(intend: String)
  extends RuntimeException(s"${intend}")

class GoogleManager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)

  var chunksStorage: Storage = null
  var systemStorage: Storage = null

  /**
   * Resolves the Google Cloud Storage service client Appropriate for the bucketName.
   * If the bucketName contains "-chunks", returns chunksStorage client; otherwise systemStorage client.
   */
  def storage(bucketName: String): Storage =
    if (bucketName != null && bucketName.contains("-chunks")) chunksStorage else systemStorage

  /** Returns the default system Google Cloud Storage service client. */
  def storage(): Storage = systemStorage

  /** Initializes the Google Cloud Storage service clients from service-account credentials. */
  def init(): Unit = {
    logger.info("GoogleStorageManager init for user: " + account.MY_USER)

    val credentialsJson =
      if (account.MY_USER == "admin") account.getProperty("credentials")
      else account.getAndDecryptProperty("credentials")

    // Separate Apache pools: 8 MiB chunk PUTs must not occupy catalog/attribute connections.
    // Client-level retries + longer HTTP timeouts cut Socket closed under bulk tiny PUTs;
    // withTransientRetries remains as a second line of defense for what the library still surfaces.
    chunksStorage = buildStorage(credentialsJson, OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_CHUNKS)
    systemStorage = buildStorage(credentialsJson, OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_SYSTEM)

    logger.info("GoogleStorageManager END init for user: " + account.MY_USER)
  }

  /**
   * Apache pool for GCS JSON. Stale keep-alives from GCS/LBs show up as
   * [[NoHttpResponseException]]; validate-after-inactivity plus idle eviction
   * drop those sockets before reuse. [[withTransientRetries]] still retries the
   * single blob if one slips through, so a bulk 5 GB retrieve does not fail
   * the whole file on one dead GET.
   */
  private def buildApacheHttpClient(maxConnections: Int) = {
    val socketFactoryRegistry =
      RegistryBuilder.create[ConnectionSocketFactory]()
        .register("http", PlainConnectionSocketFactory.getSocketFactory)
        .register("https", SSLConnectionSocketFactory.getSocketFactory)
        .build()
    val connManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry)
    connManager.setMaxTotal(maxConnections)
    connManager.setDefaultMaxPerRoute(maxConnections)
    connManager.setValidateAfterInactivity(1000)
    ApacheHttpTransport.newDefaultHttpClientBuilder()
      .setConnectionManager(connManager)
      .evictExpiredConnections()
      .evictIdleConnections(30, TimeUnit.SECONDS)
      .build()
  }

  /**
   * Apache HTTP transport sized for AltaStata fan-out. Chunks vs system pools
   * are separate so 8 MiB bodies cannot starve catalog / attributes / listing.
   */
  private def buildStorage(credentialsJson: String, maxConnections: Int): Storage = {
    val apacheTransport = new ApacheHttpTransport(buildApacheHttpClient(maxConnections))
    val transportFactory = new HttpTransportFactory {
      override def create(): HttpTransport = apacheTransport
    }

    val transport: HttpTransportOptions =
      HttpTransportOptions.newBuilder()
        .setHttpTransportFactory(transportFactory)
        .setConnectTimeout(60 * 1000)
        .setReadTimeout(180 * 1000)
        .build()

    // Keep SDK retries moderate; withTransientRetries covers Socket closed / NoHttpResponse.
    val retry: RetrySettings =
      RetrySettings.newBuilder()
        .setMaxAttempts(5)
        .setInitialRetryDelayDuration(Duration.ofMillis(250))
        .setMaxRetryDelayDuration(Duration.ofSeconds(8))
        .setRetryDelayMultiplier(2.0)
        .setTotalTimeoutDuration(Duration.ofMinutes(3))
        .setInitialRpcTimeoutDuration(Duration.ofSeconds(60))
        .setMaxRpcTimeoutDuration(Duration.ofSeconds(180))
        .setRpcTimeoutMultiplier(1.5)
        .build()

    StorageOptions.newBuilder()
      .setCredentials(ServiceAccountCredentials.fromStream(IOUtils.toInputStream(credentialsJson, "UTF-8")))
      .setTransportOptions(transport)
      .setRetrySettings(retry)
      // Chunk/object keys are UUID-scoped; retrying create/upload on the same key is safe.
      .setStorageRetryStrategy(StorageRetryStrategy.getUniformStorageRetryStrategy)
      .build()
      .getService
  }

  /**
   * Retries a GCS call with exponential backoff for transient I/O
   * (e.g. "Socket closed" under high fan-out of tiny PUTs).
   */
  private def withTransientRetries[T](op: String, bucketName: String, blobName: String)(body: => T): T = {
    val maxAttempts = 4
    var attempt = 1
    var last: Throwable = null
    while (attempt <= maxAttempts) {
      try {
        return body
      } catch {
        case ex: Exception if GoogleManager.isTransientGcsFailure(ex) && attempt < maxAttempts =>
          last = ex
          val delayMs = math.min(4000L, 200L * (1L << (attempt - 1)))
          logger.warn(
            s"$op transient failure attempt=$attempt/$maxAttempts $bucketName -- $blobName: ${ex.getMessage}; retry in ${delayMs}ms")
          Thread.sleep(delayMs)
          attempt += 1
        case ex: Exception =>
          throw ex
      }
    }
    throw last
  }

  // TODO: ignore if object does not exist
  /** Deletes a blob from Google Cloud Storage. */
  def deleteObjectFromGoogle(bucketName: String, blobName: String): Unit = {
    logger.trace(s"\tGoogleBlob DELETE START: ${bucketName} -- ${blobName}")
    withTransientRetries("deleteObjectFromGoogle", bucketName, blobName) {
      storage(bucketName).delete(BlobId.of(bucketName, blobName))
    }
    logger.trace(s"\tGoogleBlob DELETE END: ${bucketName} -- ${blobName}")
  }

  /** Asynchronously uploads a byte array payload to GCS. */
  def storeInGoogleStorage(array: Array[Byte], bucketName: String, blobName: String, size: Long) = Future {
    val myCatch = catching(classOf[Throwable])
      .withApply(t => throw new StoreCloudObjectException(s"GoogleStorage ${bucketName} ${blobName}", t))

    myCatch {
      val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, blobName))
        .setContentType("application/octet-stream")
        .build()
      logger.trace(s"\tGoogleBlob STORE START: ${bucketName} -- ${blobName} ${array.length}")
      withTransientRetries("storeInGoogleStorage", bucketName, blobName) {
        storage(bucketName).create(blobInfo, array, 0, size.toInt)
      }
      logger.trace(s"\tGoogleBlob STORE END: ${bucketName} -- ${blobName}")
    }
  }(ecGoogleStorageObjectUploadOps)

  /**
   * Asynchronously downloads a byte array payload from GCS.
   * Single GET via [[Storage.readAllBytes]] (no metadata get + second download).
   */
  def retrieveFromGoogleStorage(bucketName: String, blobName: String) = Future {
    val myCatch = catching(classOf[Throwable])
      .withApply(t => throw new RetrieveCloudObjectException(s"GoogleStorage ${bucketName} ${blobName}", t))

    myCatch {
      logger.trace(s"\tGoogleBlob RETRIEVE START: ${bucketName} -- ${blobName}")
      val res = withTransientRetries("retrieveFromGoogleStorage", bucketName, blobName) {
        try {
          storage(bucketName).readAllBytes(BlobId.of(bucketName, blobName))
        } catch {
          case se: StorageException if se.getCode == 404 =>
            throw new RetrieveCloudObjectException(
              s"GoogleStorage ${bucketName} ${blobName} - Blob not found", se)
        }
      }
      if (res == null) {
        throw new RetrieveCloudObjectException(
          s"GoogleStorage ${bucketName} ${blobName} - Blob not found", null)
      }
      logger.trace(s"\tGoogleBlob RETRIEVE END: ${bucketName} -- ${blobName}: " + res.length)
      res
    }
  }(ecGoogleStorageObjectDownloadOps)

  /**
   * Retrieves a list of object keys in a given GCS bucket matching a prefix, without additional properties.
   * Pages explicitly via pageToken (same contract as S3 continuationToken) so large prefixes
   * are not truncated to the first GCS page (~1000 objects).
   */
  def getGoogleStorageListWithoutDetails(bucketName: String, prefix: String, useFlatBlobListing: Boolean): java.util.Iterator[String] = {
    logger.debug(s"getGoogleStorageListWithoutDetails START: $bucketName prefix=$prefix flat=$useFlatBlobListing")

    val pageSize = 1000L

    def fetchPage(pageToken: String): com.google.api.gax.paging.Page[com.google.cloud.storage.Blob] = {
      val opts = scala.collection.mutable.ArrayBuffer[BlobListOption](
        BlobListOption.prefix(prefix),
        BlobListOption.pageSize(pageSize)
      )
      if (!useFlatBlobListing) {
        opts += BlobListOption.currentDirectory
      }
      if (pageToken != null && pageToken.nonEmpty) {
        opts += BlobListOption.pageToken(pageToken)
      }
      withTransientRetries("getGoogleStorageListWithoutDetails", bucketName, prefix) {
        storage(bucketName).list(bucketName, opts: _*)
      }
    }

    new java.util.Iterator[String] {
      private var page = fetchPage(null)
      private var values: java.util.Iterator[com.google.cloud.storage.Blob] = page.getValues.iterator
      private var pages = 1

      private def advancePageIfNeeded(): Unit = {
        while (!values.hasNext && page.hasNextPage) {
          page = fetchPage(page.getNextPageToken)
          values = page.getValues.iterator
          pages += 1
          logger.debug(
            s"getGoogleStorageListWithoutDetails page=$pages bucket=$bucketName prefix=$prefix")
        }
      }

      override def hasNext: Boolean = {
        advancePageIfNeeded()
        val more = values.hasNext
        if (!more) {
          logger.debug(
            s"getGoogleStorageListWithoutDetails END: $bucketName prefix=$prefix pages=$pages")
        }
        more
      }

      override def next(): String = {
        advancePageIfNeeded()
        values.next().getName
      }
    }
  }

  private val ecGoogleStorageObjectUploadOps = OpsExecutors.ecCloudObjectUploadOps
  private val ecGoogleStorageObjectDownloadOps = OpsExecutors.ecCloudObjectDownloadOps
}

object GoogleManager {
  /** True when GCS / network failures are worth retrying (connection drops under load). */
  def isTransientGcsFailure(t: Throwable): Boolean = {
    var cur = t
    while (cur != null) {
      cur match {
        case se: StorageException if se.getCode == 408 || se.getCode == 429 || se.getCode >= 500 =>
          return true
        case _: NoHttpResponseException =>
          return true
        case _: java.net.SocketException =>
          return true
        case _: java.net.SocketTimeoutException =>
          return true
        case _: java.io.InterruptedIOException =>
          return true
        case _ =>
      }
      val msg = Option(cur.getMessage).getOrElse("").toLowerCase
      if (msg.contains("socket closed")
        || msg.contains("connection reset")
        || msg.contains("broken pipe")
        || msg.contains("connection refused")
        || msg.contains("unavailable")
        || msg.contains("rate limit")
        || msg.contains("goaway")
        || msg.contains("remote host terminated")
        || msg.contains("failed to respond")) {
        return true
      }
      cur = cur.getCause
    }
    false
  }
}

/** Local dev entry point only; not used by production code or tests. */
object GoogleStorageTest {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      System.err.println("Usage: GoogleStorageTest <account-properties-name-or-path>")
      System.exit(1)
    }
    val account = new Account()
    val path = if (new File(args(0)).isAbsolute) args(0) else Account.ALTASTATA_ACCOUNTS_HOME + File.separator + args(0)
    account.loadAccountProperties(path)
  }
}
