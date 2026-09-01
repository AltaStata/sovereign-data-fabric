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

import java.util.Properties
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import org.slf4j.LoggerFactory
import scala.collection._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Buffer
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.Exception.catching
import scala.sys.process.BasicIO
import com.altastata.utils.Constants
import java.net.URI
import java.net.URLEncoder
import com.altastata.filesystem.StoreCloudObjectException
import com.altastata.filesystem.RetrieveCloudObjectException
import com.altastata.filesystem.securecloud.OpsExecutors
import scala.util.Try
import java.nio.file.Path
import java.util.Date
import com.altastata.utils.Account
import java.util.concurrent.Executors
import org.apache.commons.lang3.concurrent.BasicThreadFactory
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.Calendar
import java.util.EnumSet
import java.net.URLDecoder
import scala.collection.mutable.HashMap
import org.apache.commons.io.IOUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.io.File
import org.apache.commons.io.FileUtils
import com.altastata.filesystem.utils.DirHandler

case class LocalFSManagerContainerNotFoundException(intend: String)
  extends RuntimeException(s"${intend}")

class LocalFSManager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)

  // TODO: create the algorithm that can optimize it on the fly
  val OBJECT_UPLOAD_THREAD_POOL_SIZE = 100
  val OBJECT_DOWNLOAD_THREAD_POOL_SIZE = 100

  var rootPath = account.userProps.getProperty("root-prefix")

  /**
   * Initializes the LocalFSManager, resolving the root prefix directory path from the account properties.
   */
  def init(): Unit = {
    logger.trace("LocalFS init for user: " + account.MY_USER)
    rootPath = account.userProps.getProperty("root-prefix")
  }

  /**
   * Resolves {@code container/blob} under {@code root-prefix}. Rejects {@code ..} (and similar)
   * that would escape the configured root.
   */
  private def resolveUnderRoot(containerName: String, blobName: String): Path = {
    val root = FileSystems.getDefault.getPath(rootPath).toAbsolutePath.normalize()
    val target =
      if (blobName == null || blobName.isEmpty) root.resolve(containerName).normalize()
      else root.resolve(containerName).resolve(blobName).normalize()
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException(
        s"LocalFS path escapes root-prefix: ${containerName}/${blobName}")
    }
    target
  }

  /**
   * Synchronously deletes a file and its empty parent directories from the local filesystem-backed mock storage.
   *
   * @param containerName the target mock bucket/container name
   * @param blobName the file key/path to delete
   */
  def deleteObjectFromLocalFS(containerName: String, blobName: String): Unit = {
    logger.trace(s"\tLocalFS DELETE START: ${containerName} -- ${blobName}")

    val filePath = resolveUnderRoot(containerName, blobName)

    // delete the file
    if (Files.exists(filePath)) {
      Files.delete(filePath)
      
      // try to delete the directory
      var parentDirPath = filePath.getParent
  
      while (!parentDirPath.toString.endsWith(containerName)) {
          
        val dirPath = parentDirPath
        parentDirPath = dirPath.getParent

        synchronized {
          // check if directory is empty
          if (Files.exists(dirPath)) {
            val dirStream = Files.newDirectoryStream(dirPath)
      
            if (!dirStream.iterator().hasNext()) {          
                Files.delete(dirPath)
            }

            dirStream.close
          }
        }
      }
    }

    logger.trace(s"\tLocalFS DELETE START: ${containerName} -- ${blobName}")
  }

  /**
   * Asynchronously writes a byte array payload to the local filesystem-backed mock storage.
   *
   * @param array the payload bytes to write
   * @param containerName the target mock bucket/container name
   * @param blobName the target file key/path
   * @param size the size of the payload bytes to write
   * @return a Future representing the async write operation
   */
  def storeInLocalFS(array: Array[Byte], containerName: String, blobName: String, size: Long) = Future {

    // Make sure we close the inFileChannel
    val cs: Buffer[Closeable] = new ArrayBuffer();
    /**
     * Registers a closeable resource to be closed after the operation completes.
     *
     * @param c the closeable resource
     * @return the closeable resource
     */
    def addClose[C <: Closeable](c: C) = { cs += c; c; }

    val myCatch = catching(classOf[Throwable]).withApply(t => throw new StoreCloudObjectException(s"LocalFS ${containerName} ${blobName}", t)).andFinally(cs.foreach(c => c.close()))

    myCatch {

      try {
        logger.trace(s"\tLocalFS STORE START: ${rootPath} -- ${containerName} -- ${blobName}")

        val file = resolveUnderRoot(containerName, blobName).toFile

        if (file.getParentFile.exists() == false) {
          file.getParentFile.mkdirs()
        }

        FileUtils.writeByteArrayToFile(file, array)
      } catch {
        case ex: IllegalArgumentException =>
          throw ex
        case ex: Exception =>
          logger.warn("futureStoreLocalFS", ex)

          logger.warn(s"futureStoreLocalFS second time: ${containerName} -- ${blobName}")
          Thread.sleep(5000)

          FileUtils.writeByteArrayToFile(resolveUnderRoot(containerName, blobName).toFile, array)
      }

      logger.trace(s"\tLocalFS STORE END: ${containerName} -- ${blobName}")
    }
  }(ecLocalFSObjectUploadOps)

  /**
   * Asynchronously reads a byte array payload from the local filesystem-backed mock storage.
   *
   * @param containerName the target mock bucket/container name
   * @param blobName the target file key/path to read
   * @return a Future wrapping the retrieved file bytes
   */
  def retrieveFromLocalFS(containerName: String, blobName: String) = Future {
    val cs: Buffer[Closeable] = new ArrayBuffer();
    /**
     * Registers a closeable resource to be closed after the operation completes.
     *
     * @param c the closeable resource
     * @return the closeable resource
     */
    def addClose[C <: Closeable](c: C) = { cs += c; c; }

    val myCatch = catching(classOf[Throwable]).withApply(t => throw new RetrieveCloudObjectException(s"LocalFS ${containerName} ${blobName}", t))
      .andFinally(cs.foreach(c => c.close()))

    myCatch {
      logger.trace(s"\tLocalFS RETRIEVE START: ${containerName} -- ${blobName}")

      var res: Array[Byte] = null
      try {
        res = FileUtils.readFileToByteArray(resolveUnderRoot(containerName, blobName).toFile)
      } catch {
        case ex: Exception =>
          logger.warn("futureRetrieveLocalFS error", ex)
          throw ex
      }

      logger.trace(s"\tLocalFS RETRIEVE END: ${containerName} -- ${blobName}: " + res.length)

      res
    }
  }(ecLocalFSObjectDownloadOps)

  /**
   * Lists the relative file paths under a mock container matching a prefix, without additional properties.
   *
   * @param containerName the target mock bucket/container name
   * @param prefix the filtering prefix
   * @param useFlatBlobListing true to list recursively; false to list immediate child levels only
   * @return a Java Iterator of listed file path strings
   */
  def getLocalFSListWithoutDetails(containerName: String, prefix: String, useFlatBlobListing: Boolean): java.util.Iterator[String] = {
    val bucketPath = resolveUnderRoot(containerName, "")
    val bucket = bucketPath.toString
    val listRoot = if (prefix == null || prefix.isEmpty) bucketPath else resolveUnderRoot(containerName, prefix)

    logger.trace("getLocalFSListWithoutDetails: " + bucket + " -- " + prefix + " useFlatBlobListing: " + useFlatBlobListing)

    val filesStream =
      DirHandler.getFileTree(listRoot.toFile, useFlatBlobListing).map(_.getAbsolutePath.substring(bucket.length + 1))

    filesStream.iterator.asJava
  }

  /**
   * Lists the relative file paths under a mock container matching a prefix, retrieving size and modified dates.
   *
   * @param containerName the target mock bucket/container name
   * @param prefix the filtering prefix
   * @param useFlatBlobListing true to list recursively; false to list immediate child levels only
   * @return an Iterable of tuples (file path, size, last modified date)
   */
  def getLocalFSListWithDetails(containerName: String, prefix: String, useFlatBlobListing: Boolean): Iterable[(String, java.lang.Long, Date)] = {
    val bucketPath = resolveUnderRoot(containerName, "")
    val bucket = bucketPath.toString
    val listRoot = if (prefix == null || prefix.isEmpty) bucketPath else resolveUnderRoot(containerName, prefix)

    logger.trace("getLocalFSListWithDetails: " + bucket + " -- " + prefix + " useFlatBlobListing: " + useFlatBlobListing)

    val filesStream = DirHandler.getFileTree(listRoot.toFile, useFlatBlobListing)

    filesStream.map(x => (x.getAbsolutePath.substring(bucket.length + 1), java.lang.Long.valueOf(0), new Date(0L))).toList
  }

  // Use global cloud object thread pools with correct priority
  private val ecLocalFSObjectUploadOps = OpsExecutors.ecCloudObjectUploadOps
  private val ecLocalFSObjectDownloadOps = OpsExecutors.ecCloudObjectDownloadOps

}

object LocalFSManagerObject {

  /**
   * Main entry point to run local verification and testing of the LocalFSManager operations.
   *
   * @param args command line arguments
   */
  def main(args: Array[String]): Unit = {
    val account = new Account()

    val localFSManager = new LocalFSManager()(account)

    localFSManager.init()
    val list = localFSManager.getLocalFSListWithDetails("mycloud", "altastata", true)

    for (el <- list) {
      println("Element: " + el)
    }

    Await.result(localFSManager.storeInLocalFS("Hello".getBytes, "mycloud/altastata", "dir/Hello.txt", 5), Duration.Inf)

    val result = Await.result(localFSManager.retrieveFromLocalFS("mycloud/altastata", "dir/Hello.txt"), Duration.Inf)

    println("Result: " + new String(result))

    localFSManager.deleteObjectFromLocalFS("mycloud/altastata", "dir/Hello.txt")
  }
}

