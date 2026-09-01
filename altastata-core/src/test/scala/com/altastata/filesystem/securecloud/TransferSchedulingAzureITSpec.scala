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
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.collection.mutable

import com.altastata.api.{AccountRegistry, AltaStataFileSystem, CloudFileOperationStatus}
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.utils.{Account, Constants}

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Integration test for the SRPT chunk dispatcher against a real cloud account (Azure).
 *
 * It uploads a mixed batch (many small files + one multi-chunk large file), downloads it
 * back, and verifies every file round-trips byte-for-byte — exercising both the
 * `chunkStoreDispatcher` (upload) and `chunkRetrieveDispatcher` (download, bulk path),
 * including multi-chunk reassembly for the large file.
 *
 * A second test covers the **streaming** download path (`getFileInputStream` →
 * `retrieveCloudFileContent(isStreaming = true)`), which intentionally stays on the legacy
 * `ecChunkRetrieveOps` pool (not the SRPT dispatcher). It is a regression guard for the
 * shared `processChunk` body that both the bulk and streaming paths now call, and verifies
 * full reads plus an arbitrary mid-file byte offset that crosses a chunk boundary.
 *
 * A third test starts a large-file bulk download (non-blocking), then downloads a concurrent
 * small-file batch and asserts the small batch completes while the large file is still in flight.
 *
 * A fourth test uploads many small text files in one batch (default 1100, named file-0001.txt ..
 * file-1100.txt — same convention as the S3 soak script), lists them on the cloud, downloads them
 * back, and verifies every index 1..N is present with readable text content.
 *
 * Cost guard: skipped unless RUN_AZURE_IT=1. Configurable via env:
 *   RUN_AZURE_IT=1                  enable the test
 *   ALTASTATA_IT_ACCOUNT=...        account dir name (default: azure.rsa.bob123)
 *   ALTASTATA_IT_PASSWORD=...       account password (default: 123)
 *   ALTASTATA_IT_LARGE_MB=...       large-file size in MB (default: 20 → 3 chunks)
 *   ALTASTATA_IT_SMALL_COUNT=...    number of small files (default: 30)
 *   RUN_AZURE_CROSS_IT=1            enable cross-request SRPT test (heavier; default off)
 *   ALTASTATA_IT_CROSS_LARGE_MB=...   large file for cross test (default: 80)
 *   ALTASTATA_IT_CROSS_SMALL_COUNT=... small files for cross test (default: 20)
 *   RUN_AZURE_BULK_IT=1             enable 1100-file bulk soak test (default off)
 *   RUN_AZURE_BULK_ONLY=1           run only the bulk soak test (skip mixed/streaming/cross)
 *   RUN_AZURE_BULK_UPLOAD_ONLY=1    bulk test: upload (+ list) only; skip download round-trip
 *   RUN_AZURE_BULK_KEEP=1           skip cloud cleanup after bulk test (leave files for UI)
 *   ALTASTATA_IT_BULK_SMALL_COUNT=... number of small files in bulk test (default: 1100)
 *
 * Run:
 *   RUN_AZURE_IT=1 ./gradlew :altastata-core:test \
 *     --tests 'com.altastata.filesystem.securecloud.TransferSchedulingAzureITSpec'
 *   RUN_AZURE_IT=1 RUN_AZURE_BULK_IT=1 ./gradlew :altastata-core:cleanTest :altastata-core:test \
 *     --tests 'com.altastata.filesystem.securecloud.TransferSchedulingAzureITSpec'
 */
@RunWith(classOf[JUnitRunner])
class TransferSchedulingAzureITSpec extends AnyFunSuite {

  private def env(k: String, default: String): String = sys.env.getOrElse(k, default)
  private val bulkOnlyMode = sys.env.get("RUN_AZURE_BULK_ONLY").contains("1")
  private val bulkUploadOnlyMode = sys.env.get("RUN_AZURE_BULK_UPLOAD_ONLY").contains("1")

  private def md5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def listFilesRecursively(root: Path): Seq[Path] =
    if (!Files.exists(root)) Seq.empty
    else Files.walk(root).iterator().asScala.filter(Files.isRegularFile(_)).toSeq

  private def countCloudFiles(fs: AltaStataFileSystem, cloudPathPrefix: String): Int =
    fs.listCloudFilesVersions(cloudPathPrefix, true, null, null).asScala.size

  private def cleanupLocalDir(dir: Path): Unit = {
    listFilesRecursively(dir).foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => })
    try Files.walk(dir).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
    catch { case _: Throwable => }
  }

  private val BulkFilePattern = """file-(\d+)\.txt""".r

  /** Same naming as altastata-s3-gateway simulate-1000-files-parallel-boto3.sh (1-based). */
  private def bulkFileName(i: Int, width: Int): String =
    s"file-${("%0" + width + "d").format(i)}.txt"

  /** Readable UTF-8 payload — visible in UI / object viewers (not empty-looking binary). */
  private def bulkFileText(i: Int, bulkCount: Int): String =
    s"Test content for file $i of $bulkCount"

  private def parseBulkFileIndex(pathOrName: String): Option[Int] =
    BulkFilePattern.findFirstMatchIn(pathOrName).map(_.group(1).toInt)

  private def assertFullIndexRange(label: String, present: Set[Int], bulkCount: Int): Unit = {
    val missing = (1 to bulkCount).filterNot(present.contains)
    val extra = present.filter(_ > bulkCount)
    assert(missing.isEmpty && extra.isEmpty,
      s"$label: expected indices 1..$bulkCount (last file-${("%0" + math.max(4, bulkCount.toString.length) + "d").format(bulkCount)}.txt), " +
        s"got ${present.size} files, last index ${if (present.isEmpty) -1 else present.max}; " +
        s"missing=${missing.take(10).mkString(",")}${if (missing.size > 10) s"...+${missing.size - 10}" else ""}")
  }

  test("SRPT dispatcher: mixed batch round-trips through Azure (upload + download)") {
    assume(!bulkOnlyMode, "RUN_AZURE_BULK_ONLY=1: skipping mixed test")
    assume(
      sys.env.get("RUN_AZURE_IT").contains("1"),
      "set RUN_AZURE_IT=1 to run the Azure integration test")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val largeMb = env("ALTASTATA_IT_LARGE_MB", "20").toInt
    val smallCount = env("ALTASTATA_IT_SMALL_COUNT", "30").toInt

    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val runId = System.currentTimeMillis()
    val cloudPrefix = s"SRPTIT/run_$runId"

    val srcDir = Files.createTempDirectory(s"srpt-src-$runId")
    val outDir = Files.createTempDirectory(s"srpt-out-$runId")

    // name -> md5 of original content, to verify the round trip by file name.
    val expected = mutable.LinkedHashMap[String, String]()
    val rnd = new scala.util.Random(runId)

    // Many small files (a few KB each).
    (0 until smallCount).foreach { i =>
      val bytes = new Array[Byte](512 + rnd.nextInt(4096))
      rnd.nextBytes(bytes)
      val name = f"small_$i%03d.bin"
      Files.write(srcDir.resolve(name), bytes)
      expected(name) = md5(bytes)
    }

    // One large, multi-chunk file (PLAIN_CHUNK_MAX_SIZE is 8 MB, so 20 MB ⇒ 3 chunks).
    val largeBytes = new Array[Byte](largeMb * 1024 * 1024)
    rnd.nextBytes(largeBytes)
    Files.write(srcDir.resolve("large.bin"), largeBytes)
    expected("large.bin") = md5(largeBytes)

    var fs: AltaStataFileSystem = null
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      // ---- Upload (exercises chunkStoreDispatcher) ----
      val storeResults: Seq[CloudFileOperationStatus] =
        fs.store(java.util.Collections.singletonList(srcDir.toFile.getAbsolutePath),
                 srcDir.toFile.getAbsolutePath, cloudPrefix, true).asScala
      val snapshot = System.currentTimeMillis()

      assert(storeResults.nonEmpty, "store returned no results")
      val storeFailures = storeResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(storeFailures.isEmpty,
        s"upload failures: ${storeFailures.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      // ---- Download (exercises chunkRetrieveDispatcher, bulk path) ----
      val retrieveResults: Seq[CloudFileOperationStatus] =
        fs.retrieve(outDir.toFile.getAbsolutePath, cloudPrefix, true, snapshot, false, true).asScala

      assert(retrieveResults.nonEmpty, "retrieve returned no results")
      val dlFailures = retrieveResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(dlFailures.isEmpty,
        s"download failures: ${dlFailures.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      // ---- Verify content round-trips (match by file name) ----
      val downloaded: Map[String, Path] =
        listFilesRecursively(outDir).map(p => p.getFileName.toString -> p).toMap

      expected.foreach { case (name, expectedMd5) =>
        assert(downloaded.contains(name), s"missing downloaded file: $name")
        val actualMd5 = md5(Files.readAllBytes(downloaded(name)))
        assert(actualMd5 == expectedMd5, s"content mismatch for $name")
      }

      info(s"round-tripped ${expected.size} files (${smallCount} small + 1 large ${largeMb}MB) via $cloudPrefix")
    } finally {
      // Best-effort cloud cleanup.
      if (fs != null) {
        try fs.delete(cloudPrefix, true, null, null)
        catch { case t: Throwable => info(s"cloud cleanup failed for $cloudPrefix: ${t.getMessage}") }
      }
      Seq(srcDir, outDir).foreach(cleanupLocalDir)
    }
  }

  test("bulk many small files: 1100 text files upload, index range 1..N, round-trip") {
    assume(
      sys.env.get("RUN_AZURE_IT").contains("1"),
      "set RUN_AZURE_IT=1 to run the Azure integration test")
    assume(
      sys.env.get("RUN_AZURE_BULK_IT").contains("1"),
      "set RUN_AZURE_BULK_IT=1 to run the bulk soak test")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val bulkCount = env("ALTASTATA_IT_BULK_SMALL_COUNT", "1100").toInt
    assume(bulkCount >= 1000, s"bulk soak requires at least 1000 files (got $bulkCount)")

    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val runId = System.currentTimeMillis()
    val cloudPrefix = s"SRPTIT/bulk_$runId"
    val srcDir = Files.createTempDirectory(s"srpt-bulk-src-$runId")
    val outDir = Files.createTempDirectory(s"srpt-bulk-out-$runId")

    val expected = mutable.LinkedHashMap[String, String]()
    val nameWidth = math.max(4, bulkCount.toString.length)

    (1 to bulkCount).foreach { i =>
      val name = bulkFileName(i, nameWidth)
      val text = bulkFileText(i, bulkCount)
      Files.write(srcDir.resolve(name), text.getBytes("UTF-8"))
      expected(name) = text
    }

    val localCount = listFilesRecursively(srcDir).size
    assert(localCount == bulkCount, s"created $localCount local files, expected $bulkCount")
    val firstName = bulkFileName(1, nameWidth)
    val lastName = bulkFileName(bulkCount, nameWidth)
    info(s"local batch: $localCount text files ($firstName .. $lastName)")

    var fs: AltaStataFileSystem = null
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      println(s"PHASE upload START files=$bulkCount prefix=$cloudPrefix")
      println(s"UPLOAD_START files=$bulkCount prefix=$cloudPrefix")
      val uploadStartNs = System.nanoTime()
      val storeResults: Seq[CloudFileOperationStatus] =
        fs.store(java.util.Collections.singletonList(srcDir.toFile.getAbsolutePath),
                 srcDir.toFile.getAbsolutePath, cloudPrefix, true).asScala
      val uploadElapsedSec = (System.nanoTime() - uploadStartNs) / 1e9
      val snapshot = System.currentTimeMillis()
      println(f"PHASE upload END elapsed=${uploadElapsedSec}%.2fs results=${storeResults.size}")
      println(f"UPLOAD_DONE files=$bulkCount prefix=$cloudPrefix elapsed=${uploadElapsedSec}%.2fs")

      assert(storeResults.size == bulkCount,
        s"store returned ${storeResults.size} results, expected $bulkCount")

      val storeFailures = storeResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(storeFailures.isEmpty,
        s"upload failures (${storeFailures.size}): ${storeFailures.take(5).map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      val uploadedIndices = storeResults.flatMap(r => parseBulkFileIndex(r.getCloudFileVersionPath)).toSet
      assertFullIndexRange("after upload", uploadedIndices, bulkCount)
      val uploadRate = if (uploadElapsedSec > 0) bulkCount.toDouble / uploadElapsedSec else 0.0
      info(f"bulk upload timing: files=$bulkCount elapsed=${uploadElapsedSec}%.2fs rate=${uploadRate}%.1f files/s")
      println(f"BULK_UPLOAD_TIMING files=$bulkCount elapsed=${uploadElapsedSec}%.2fs rate=${uploadRate}%.1f files/s")

      println(s"PHASE list START prefix=$cloudPrefix")
      val listStartNs = System.nanoTime()
      val listCount = countCloudFiles(fs, cloudPrefix)
      val listElapsedSec = (System.nanoTime() - listStartNs) / 1e9
      println(f"PHASE list END count=$listCount elapsed=${listElapsedSec}%.2fs")
      assert(listCount >= bulkCount,
        s"cloud list saw $listCount files under $cloudPrefix, expected >= $bulkCount (want $firstName .. $lastName)")

      if (bulkUploadOnlyMode) {
        info(s"bulk upload-only: $bulkCount files uploaded and listed ($listCount); download skipped")
        println(s"PHASE download SKIPPED (RUN_AZURE_BULK_UPLOAD_ONLY=1)")
      } else {
      println(s"PHASE download START files=$bulkCount")
      val dlStartNs = System.nanoTime()
      val retrieveResults: Seq[CloudFileOperationStatus] =
        fs.retrieve(outDir.toFile.getAbsolutePath, cloudPrefix, true, snapshot, false, true).asScala
      val dlElapsedSec = (System.nanoTime() - dlStartNs) / 1e9
      val dlRate = if (dlElapsedSec > 0) bulkCount.toDouble / dlElapsedSec else 0.0
      println(f"PHASE download END elapsed=${dlElapsedSec}%.2fs rate=${dlRate}%.1f files/s results=${retrieveResults.size}")

      assert(retrieveResults.size == bulkCount,
        s"retrieve returned ${retrieveResults.size} results, expected $bulkCount")

      val dlFailures = retrieveResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(dlFailures.isEmpty,
        s"download failures (${dlFailures.size}): ${dlFailures.take(5).map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      val downloaded: Map[String, Path] =
        listFilesRecursively(outDir).map(p => p.getFileName.toString -> p).toMap

      assert(downloaded.size == bulkCount,
        s"downloaded ${downloaded.size} local files, expected $bulkCount")

      val downloadedIndices = downloaded.keys.flatMap(parseBulkFileIndex).toSet
      assertFullIndexRange("after download", downloadedIndices, bulkCount)

      expected.foreach { case (name, expectedText) =>
        assert(downloaded.contains(name), s"missing downloaded file: $name")
        val path = downloaded(name)
        assert(Files.size(path) > 0, s"downloaded file is empty: $name")
        val actualText = new String(Files.readAllBytes(path), "UTF-8")
        assert(actualText == expectedText, s"content mismatch for $name: got '$actualText'")
      }

      info(s"bulk soak: $bulkCount text files ($firstName .. $lastName) uploaded, listed ($listCount), round-tripped via $cloudPrefix")
      }
    } finally {
      if (fs != null && !sys.env.get("RUN_AZURE_BULK_KEEP").contains("1")) {
        try fs.delete(cloudPrefix, true, null, null)
        catch { case t: Throwable => info(s"cloud cleanup failed for $cloudPrefix: ${t.getMessage}") }
      } else if (fs != null) {
        println(s"BULK_KEEP cloudPrefix=$cloudPrefix (not deleted)")
      }
      Seq(srcDir, outDir).foreach(cleanupLocalDir)
    }
  }

  test("streaming download: multi-chunk file streams back byte-for-byte (full + mid-file offset)") {
    assume(!bulkOnlyMode, "RUN_AZURE_BULK_ONLY=1: skipping streaming test")
    assume(
      sys.env.get("RUN_AZURE_IT").contains("1"),
      "set RUN_AZURE_IT=1 to run the Azure integration test")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val largeMb = env("ALTASTATA_IT_LARGE_MB", "20").toInt

    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val runId = System.currentTimeMillis()
    val cloudPrefix = s"SRPTIT/stream_$runId"
    val fileName = "stream_large.bin"

    val srcDir = Files.createTempDirectory(s"srpt-stream-$runId")

    // One large, multi-chunk file so streaming exercises chunk-boundary handling.
    val rnd = new scala.util.Random(runId)
    val largeBytes = new Array[Byte](largeMb * 1024 * 1024)
    rnd.nextBytes(largeBytes)
    Files.write(srcDir.resolve(fileName), largeBytes)
    val expectedFullMd5 = md5(largeBytes)

    var fs: AltaStataFileSystem = null

    // Drain the whole stream to a byte array (readBufferFromInputStream(-1) = IOUtils.toByteArray).
    def drain(in: java.io.InputStream): Array[Byte] =
      try fs.readBufferFromInputStream(in, -1) finally in.close()

    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      // store() replaces localFSPrefix (srcDir) with cloudPrefix, so the cloud path is
      // cloudPrefix + "/" + fileName.
      val storeResults: Seq[CloudFileOperationStatus] =
        fs.store(java.util.Collections.singletonList(srcDir.resolve(fileName).toFile.getAbsolutePath),
                 srcDir.toFile.getAbsolutePath, cloudPrefix, true).asScala
      val snapshot = System.currentTimeMillis()

      val storeFailures = storeResults.filterNot(_.getOperationState == OperationState.DONE)
      assert(storeFailures.isEmpty,
        s"upload failures: ${storeFailures.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      val cloudFilePath = cloudPrefix + "/" + fileName

      // ---- Full streaming read (isStreaming path; grouped flow-control on the legacy pool) ----
      val fullStreamed = drain(fs.getFileInputStream(cloudFilePath, snapshot, 0L, 4))
      assert(fullStreamed.length == largeBytes.length,
        s"streamed length ${fullStreamed.length} != original ${largeBytes.length}")
      assert(md5(fullStreamed) == expectedFullMd5, "full streamed content mismatch")

      // ---- Mid-file offset read: arbitrary byte offset that crosses a chunk boundary ----
      val offset = Constants.PLAIN_CHUNK_MAX_SIZE.toLong + 12345L // inside chunk 1
      assume(offset < largeBytes.length, "offset must be within the file")
      val partialStreamed = drain(fs.getFileInputStream(cloudFilePath, snapshot, offset, 4))
      val expectedTail = java.util.Arrays.copyOfRange(largeBytes, offset.toInt, largeBytes.length)
      assert(partialStreamed.length == expectedTail.length,
        s"partial length ${partialStreamed.length} != expected ${expectedTail.length}")
      assert(md5(partialStreamed) == md5(expectedTail), "partial streamed content mismatch")

      info(s"streamed ${largeMb}MB file: full + from offset $offset via $cloudFilePath")
    } finally {
      if (fs != null) {
        try fs.delete(cloudPrefix, true, null, null)
        catch { case t: Throwable => info(s"cloud cleanup failed for $cloudPrefix: ${t.getMessage}") }
      }
      listFilesRecursively(srcDir).foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => })
      try Files.walk(srcDir).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
      catch { case _: Throwable => }
    }
  }

  test("cross-request SRPT: small files finish while a large file download is still in flight") {
    assume(!bulkOnlyMode, "RUN_AZURE_BULK_ONLY=1: skipping cross-request test")
    assume(
      sys.env.get("RUN_AZURE_IT").contains("1"),
      "set RUN_AZURE_IT=1 to run the Azure integration test")
    assume(
      sys.env.get("RUN_AZURE_CROSS_IT").contains("1"),
      "set RUN_AZURE_CROSS_IT=1 to run the cross-request SRPT test (uploads a large file)")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val largeMb = env("ALTASTATA_IT_CROSS_LARGE_MB", "80").toInt
    val smallCount = env("ALTASTATA_IT_CROSS_SMALL_COUNT", "20").toInt

    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val runId = System.currentTimeMillis()
    val largePrefix = s"SRPTIT/cross_large_$runId"
    val smallPrefix = s"SRPTIT/cross_small_$runId"

    val largeSrcDir = Files.createTempDirectory(s"srpt-cross-large-$runId")
    val smallSrcDir = Files.createTempDirectory(s"srpt-cross-small-$runId")
    val outLarge = Files.createTempDirectory(s"srpt-cross-out-large-$runId")
    val outSmall = Files.createTempDirectory(s"srpt-cross-out-small-$runId")

    val rnd = new scala.util.Random(runId)
    val largeBytes = new Array[Byte](largeMb * 1024 * 1024)
    rnd.nextBytes(largeBytes)
    Files.write(largeSrcDir.resolve("large.bin"), largeBytes)
    val expectedLargeMd5 = md5(largeBytes)

    val expectedSmall = mutable.LinkedHashMap[String, String]()
    (0 until smallCount).foreach { i =>
      val bytes = new Array[Byte](512 + rnd.nextInt(4096))
      rnd.nextBytes(bytes)
      val name = f"small_$i%03d.bin"
      Files.write(smallSrcDir.resolve(name), bytes)
      expectedSmall(name) = md5(bytes)
    }

    @volatile var largeError: Throwable = null

    var fs: AltaStataFileSystem = null
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      val largeStore = fs.store(
        java.util.Collections.singletonList(largeSrcDir.toFile.getAbsolutePath),
        largeSrcDir.toFile.getAbsolutePath, largePrefix, true).asScala
      val largeStoreFailures = largeStore.filterNot(_.getOperationState == OperationState.DONE)
      assert(largeStoreFailures.isEmpty,
        s"large upload failed: ${largeStoreFailures.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      val smallStore = fs.store(
        java.util.Collections.singletonList(smallSrcDir.toFile.getAbsolutePath),
        smallSrcDir.toFile.getAbsolutePath, smallPrefix, true).asScala
      val smallStoreFailures = smallStore.filterNot(_.getOperationState == OperationState.DONE)
      assert(smallStoreFailures.isEmpty,
        s"small upload failed: ${smallStoreFailures.map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")}")

      val snapshot = System.currentTimeMillis()

      // Large download runs on a background thread so the small batch can start while it is in flight.
      val largeThread = new Thread(new Runnable {
        def run(): Unit = {
          try {
            val results = fs.retrieve(
              outLarge.toFile.getAbsolutePath, largePrefix, true, snapshot, false, true).asScala
            assert(results.nonEmpty, "large retrieve returned no results")
            assert(results.forall(_.getOperationState == OperationState.DONE),
              s"large download failures: ${results.filterNot(_.getOperationState == OperationState.DONE).map(_.getError).mkString("; ")}")
          } catch {
            case t: Throwable => largeError = t
          }
        }
      }, s"srpt-it-large-$runId")

      largeThread.start()
      Thread.sleep(500)

      val smallStatuses = fs.retrieve(
        outSmall.toFile.getAbsolutePath, smallPrefix, true, snapshot, false, true).asScala
      assert(smallStatuses.nonEmpty, "small retrieve returned no results")
      assert(smallStatuses.forall(_.getOperationState == OperationState.DONE),
        s"small download failures: ${smallStatuses.filterNot(_.getOperationState == OperationState.DONE).map(_.getError).mkString("; ")}")

      // SRPT: small batch done while the large download thread is still running.
      assert(largeThread.isAlive,
        s"large download should still be in flight when small batch completes (${largeMb}MB file; " +
          s"try ALTASTATA_IT_CROSS_LARGE_MB=500 on very fast links)")

      largeThread.join(600000)
      if (largeError != null) throw largeError

      val downloadedLarge = outLarge.resolve("large.bin")
      assert(Files.exists(downloadedLarge), "missing downloaded large file")
      assert(md5(Files.readAllBytes(downloadedLarge)) == expectedLargeMd5, "large content mismatch")

      val downloadedSmall = listFilesRecursively(outSmall).map(p => p.getFileName.toString -> p).toMap
      expectedSmall.foreach { case (name, expectedMd5) =>
        assert(downloadedSmall.contains(name), s"missing small file: $name")
        assert(md5(Files.readAllBytes(downloadedSmall(name))) == expectedMd5, s"small content mismatch: $name")
      }

      info(s"cross-request SRPT: ${smallCount} small files done while ${largeMb}MB large still in flight")
    } finally {
      if (fs != null) {
        Seq(largePrefix, smallPrefix).foreach { prefix =>
          try fs.delete(prefix, true, null, null)
          catch { case t: Throwable => info(s"cloud cleanup failed for $prefix: ${t.getMessage}") }
        }
      }
      Seq(largeSrcDir, smallSrcDir, outLarge, outSmall).foreach { dir =>
        listFilesRecursively(dir).foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => })
        try Files.walk(dir).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
        catch { case _: Throwable => }
      }
    }
  }
}
