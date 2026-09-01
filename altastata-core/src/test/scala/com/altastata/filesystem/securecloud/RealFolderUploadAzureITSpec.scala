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

import java.io.File
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

import com.altastata.api.{AccountRegistry, AltaStataFileSystem, CloudFileOperationStatus}
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.utils.Account

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Headless integration benchmark for a real local directory (no JavaFX UI).
 *
 * Runs upload → download → delete and prints per-phase timings.
 *
 * Env:
 *   RUN_REAL_UPLOAD_IT=1                 enable test
 *   ALTASTATA_IT_ACCOUNT=...             account dir name (default: azure.rsa.bob123)
 *   ALTASTATA_IT_PASSWORD=...            account password (default: 123)
 *   ALTASTATA_IT_SOURCE_DIR=...          local directory to upload
 *   ALTASTATA_IT_CLOUD_PREFIX=...        cloud prefix root (default: REALUPLOAD)
 *   RUN_REAL_UPLOAD_KEEP=1               keep uploaded files on cloud (default: cleanup)
 */
@RunWith(classOf[JUnitRunner])
class RealFolderUploadAzureITSpec extends AnyFunSuite {

  private def env(k: String, default: String): String = sys.env.getOrElse(k, default)

  private def countFilesRecursively(root: Path): Int =
    if (!Files.exists(root)) 0
    else {
      val s = Files.walk(root)
      try s.iterator().asScala.count(Files.isRegularFile(_))
      finally s.close()
    }

  test("headless upload and download real local folder") {
    assume(sys.env.get("RUN_REAL_UPLOAD_IT").contains("1"), "set RUN_REAL_UPLOAD_IT=1 to run this integration test")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val sourceDir = new File(env("ALTASTATA_IT_SOURCE_DIR", sys.props("user.home") + "/Desktop/backup/Private/china232 podcasts"))
    val keepOnCloud = sys.env.get("RUN_REAL_UPLOAD_KEEP").contains("1")
    val cloudPrefix = s"${env("ALTASTATA_IT_CLOUD_PREFIX", "REALUPLOAD")}/${System.currentTimeMillis()}"

    assume(sourceDir.isDirectory, s"source directory not found: ${sourceDir.getAbsolutePath}")

    val localFileCount = countFilesRecursively(sourceDir.toPath)
    info(s"REAL_TEST source=${sourceDir.getAbsolutePath}, files=$localFileCount, cloudPrefix=$cloudPrefix")

    var accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    if (!new File(accountDir).isDirectory && new File(Account.ALTASTATA_ACCOUNTS_HOME + "/others/" + accountName).isDirectory) {
        accountDir = Account.ALTASTATA_ACCOUNTS_HOME + "/others/" + accountName
    }
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    var fs: AltaStataFileSystem = null
    val downloadDir = Files.createTempDirectory("altastata-it-download").toFile
    
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      // 1. Upload
      val startUpload = System.nanoTime()
      val storeResults: Seq[CloudFileOperationStatus] =
        fs.store(
          java.util.Collections.singletonList(sourceDir.getAbsolutePath),
          sourceDir.getAbsolutePath,
          cloudPrefix,
          true).asScala
      val uploadMs = (System.nanoTime() - startUpload) / 1000000L

      assert(storeResults.nonEmpty, "store returned no results")
      val failedUp = storeResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(failedUp.isEmpty, s"upload failures: ${failedUp.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")
      info(s"REAL_TEST Upload done: results=${storeResults.size}, elapsedMs=$uploadMs")
      println(s"REAL_TEST Upload done: elapsedMs=$uploadMs")

      // 2. Download
      val snapshotTime = System.currentTimeMillis()
      val startDownload = System.nanoTime()
      val retrieveResults = fs.retrieve(
        downloadDir.getAbsolutePath,
        cloudPrefix,
        true,  // includingSubdirectories
        snapshotTime,
        false, // isStreaming
        true   // waitUntilDone
      ).asScala
      val downloadMs = (System.nanoTime() - startDownload) / 1000000L

      assert(retrieveResults.nonEmpty, "retrieve returned no results")
      val failedDown = retrieveResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(failedDown.isEmpty, s"download failures: ${failedDown.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")
      info(s"REAL_TEST Download done: results=${retrieveResults.size}, elapsedMs=$downloadMs")
      println(s"REAL_TEST Download done: elapsedMs=$downloadMs")

      // 3. Delete
      val startDelete = System.nanoTime()
      if (!keepOnCloud) {
          fs.delete(cloudPrefix, true, null, null)
      }
      val deleteMs = (System.nanoTime() - startDelete) / 1000000L
      info(s"REAL_TEST Delete done: elapsedMs=$deleteMs")
      println(s"REAL_TEST Delete done: elapsedMs=$deleteMs")

      val summary = s"REAL_TEST SUMMARY account=$accountName Upload: ${uploadMs/1000.0}s, Download: ${downloadMs/1000.0}s, Delete: ${deleteMs/1000.0}s"
      info(summary)
      println(summary)

    } finally {
      if (fs != null && !keepOnCloud) {
        try fs.delete(cloudPrefix, true, null, null)
        catch { case _: Throwable => }
      }
      
      if (downloadDir.exists()) {
        try {
          Files.walk(downloadDir.toPath)
            .sorted(java.util.Comparator.reverseOrder())
            .map[File]((p: Path) => p.toFile)
            .forEach(_.delete())
        } catch { case _: Throwable => }
      }
    }
  }
}
