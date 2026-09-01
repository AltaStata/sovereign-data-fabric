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
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.altastata.api.{AccountRegistry, AltaStataFileSystem, CloudFileOperationStatus}
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.utils.Account

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Stress IT: many tiny files → Bob upload → share Alice (Azure).
 *
 * Env:
 *   RUN_MANY_SMALL_FILES_IT=1
 *   ALTASTATA_IT_PASSWORD=123
 *   ALTASTATA_IT_SMALL_FILE_COUNT=5000
 *   ALTASTATA_IT_SMALL_FILE_BYTES=64
 *   ALTASTATA_IT_OWNER_ACCOUNT=azure.rsa.bob123
 *   ALTASTATA_IT_READER_ACCOUNT=azure.rsa.alice222
 *   ALTASTATA_IT_CLOUD_PREFIX=MANYSMALL
 *   ALTASTATA_IT_WAIT_SEC=7200
 *   RUN_MANY_SMALL_FILES_KEEP=1
 */
@RunWith(classOf[JUnitRunner])
class ManySmallFilesShareAzureITSpec extends AnyFunSuite {

  private def env(k: String, default: String): String = sys.env.getOrElse(k, default)

  private def resolveAccountDir(accountName: String): File = {
    val home = Account.ALTASTATA_ACCOUNTS_HOME
    val primary = new File(home, accountName)
    val other = new File(home + "/others", accountName)
    if (primary.isDirectory) primary
    else if (other.isDirectory) other
    else primary
  }

  private def openFs(accountName: String, password: String): AltaStataFileSystem = {
    val dir = resolveAccountDir(accountName)
    assume(dir.isDirectory, s"account dir not found: ${dir.getAbsolutePath}")
    val fs = AccountRegistry.getOrCreateFromDir(dir.getAbsolutePath)
    fs.getAccount.userProps.setProperty("password-timeout-interval", env("ALTASTATA_IT_PASSWORD_TIMEOUT_SEC", "86400"))
    fs.setPassword(password)
    Thread.sleep(100L)
    fs.setPassword(password)
    assume(fs.getAccount.isPasswordSet, s"password not set after login: $accountName")
    fs
  }

  private def assertDone(label: String, results: Seq[CloudFileOperationStatus]): Unit = {
    assert(results.nonEmpty, s"$label returned no results")
    val failed = results.filterNot(_.getOperationState == OperationState.DONE)
    assert(
      failed.isEmpty,
      s"$label failures (${failed.size}): " + failed.take(20).map(s => s.getCloudFileVersionPath + " -> " + s.getError).mkString("; ")
        + (if (failed.size > 20) s" ... +${failed.size - 20} more" else "")
    )
  }

  private def listedCount(fs: AltaStataFileSystem, prefix: String): Int = {
    val it = fs.listCloudFilesVersions(prefix, true, null, null)
    var n = 0
    while (it.hasNext) {
      it.next()
      n += 1
    }
    n
  }

  private def waitUntil(label: String, timeoutSec: Long, onTick: () => Unit)(predicate: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
    var last = false
    var lastTick = 0L
    while ({ last = predicate; !last } && System.nanoTime() < deadline) {
      val now = System.nanoTime()
      if (now - lastTick > TimeUnit.SECONDS.toNanos(30)) {
        onTick()
        lastTick = now
        println(s"MANY_SMALL_AZURE still waiting: $label")
      }
      Thread.sleep(2000L)
    }
    assert(last, s"timeout waiting for: $label (${timeoutSec}s)")
  }

  private def generateTinyFiles(count: Int, bytesPerFile: Int): File = {
    val tmp = Files.createTempDirectory("altastata-many-small-azure").toFile
    val payload = Array.fill(math.max(1, bytesPerFile))('x'.toByte)
    val width = count.toString.length
    var i = 0
    while (i < count) {
      val name = s"f${i.toString.reverse.padTo(width, '0').reverse}.txt"
      Files.write(new File(tmp, name).toPath, payload)
      i += 1
      if (i % 1000 == 0) println(s"MANY_SMALL_AZURE generated $i/$count local files")
    }
    println(s"MANY_SMALL_AZURE generated $count files × ${bytesPerFile}B under ${tmp.getAbsolutePath}")
    tmp
  }

  private def drainMsgs(fs: AltaStataFileSystem): Seq[String] =
    fs.getAccount.getUserMsgs.asScala.toList

  private def strangeMsgs(fs: AltaStataFileSystem): Seq[String] =
    drainMsgs(fs).filter(m => m.contains("Strange change") || m.contains("Strange "))

  test("many tiny files upload then Bob shares with Alice on Azure") {
    assume(sys.env.get("RUN_MANY_SMALL_FILES_IT").contains("1"), "set RUN_MANY_SMALL_FILES_IT=1 to run this stress IT")

    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val ownerAccount = env("ALTASTATA_IT_OWNER_ACCOUNT", "azure.rsa.bob123")
    val aliceAccount = env("ALTASTATA_IT_READER_ACCOUNT", "azure.rsa.alice222")
    val fileCount = env("ALTASTATA_IT_SMALL_FILE_COUNT", "5000").toInt
    val fileBytes = env("ALTASTATA_IT_SMALL_FILE_BYTES", "64").toInt
    val waitSec = env("ALTASTATA_IT_WAIT_SEC", "7200").toLong
    val keep = sys.env.get("RUN_MANY_SMALL_FILES_KEEP").contains("1")
    val cloudPrefix = s"${env("ALTASTATA_IT_CLOUD_PREFIX", "MANYSMALL")}/${System.currentTimeMillis()}"

    assume(fileCount > 0, "ALTASTATA_IT_SMALL_FILE_COUNT must be > 0")

    val uploadDir = generateTinyFiles(fileCount, fileBytes)
    var bob: AltaStataFileSystem = null
    var alice: AltaStataFileSystem = null

    def refresh(label: String): Unit = {
      println(s"MANY_SMALL_AZURE refreshSessions ($label)")
      if (bob != null) bob.setPassword(password)
      if (alice != null) alice.setPassword(password)
    }

    try {
      bob = openFs(ownerAccount, password)
      alice = openFs(aliceAccount, password)
      refresh("after-open")

      println(s"MANY_SMALL_AZURE start prefix=$cloudPrefix files=$fileCount bytesEach=$fileBytes waitSec=$waitSec")

      val tUpload0 = System.nanoTime()
      val storeResults = bob.store(
        java.util.Collections.singletonList(uploadDir.getAbsolutePath),
        uploadDir.getAbsolutePath,
        cloudPrefix,
        true
      ).asScala
      val uploadMs = (System.nanoTime() - tUpload0) / 1000000L
      assertDone("store", storeResults)
      refresh("after-store")
      val bobListed = listedCount(bob, cloudPrefix)
      assert(bobListed >= fileCount, s"bob listed $bobListed < expected $fileCount")
      println(s"MANY_SMALL_AZURE upload done: listed=$bobListed elapsedMs=$uploadMs (~${uploadMs / 1000.0}s)")

      refresh("before-share")
      val tShare0 = System.nanoTime()
      val shareResults = bob.share(cloudPrefix, true, null, null, Array("alice222")).asScala
      val shareApiMs = (System.nanoTime() - tShare0) / 1000000L
      assertDone("bob share alice", shareResults)
      println(s"MANY_SMALL_AZURE share API done: results=${shareResults.size} elapsedMs=$shareApiMs")

      val tWait0 = System.nanoTime()
      waitUntil(s"alice lists >= $fileCount", waitSec, () => {
        refresh("wait-alice")
        val n = listedCount(alice, cloudPrefix)
        println(s"MANY_SMALL_AZURE alice progress: listed=$n / $fileCount")
      }) {
        listedCount(alice, cloudPrefix) >= fileCount
      }
      val waitMs = (System.nanoTime() - tWait0) / 1000000L
      val aliceListed = listedCount(alice, cloudPrefix)
      println(s"MANY_SMALL_AZURE alice saw $aliceListed file(s) waitMs=$waitMs")

      val strange = strangeMsgs(bob) ++ strangeMsgs(alice)
      strange.take(30).foreach(m => println(s"  STRANGE: ${m.replace("\n", " ").take(240)}"))
      if (strange.nonEmpty) {
        println(s"MANY_SMALL_AZURE WARN: ${strange.size} Strange message(s)")
      } else {
        println("MANY_SMALL_AZURE OK: no Strange change messages")
      }

      println(
        s"MANY_SMALL_AZURE SUMMARY files=$fileCount bobListed=$bobListed aliceListed=$aliceListed " +
          s"uploadSec=${uploadMs / 1000.0} shareApiSec=${shareApiMs / 1000.0} aliceWaitSec=${waitMs / 1000.0} " +
          s"strange=${strange.size} prefix=$cloudPrefix"
      )
    } finally {
      if (bob != null && !keep) {
        try {
          refresh("before-cleanup")
          bob.delete(cloudPrefix, true, null, null)
        } catch { case NonFatal(_) => }
      }
      try {
        Files.walk(uploadDir.toPath)
          .sorted(java.util.Comparator.reverseOrder())
          .map[File]((p: Path) => p.toFile)
          .forEach(_.delete())
      } catch { case NonFatal(_) => }
    }
  }
}
