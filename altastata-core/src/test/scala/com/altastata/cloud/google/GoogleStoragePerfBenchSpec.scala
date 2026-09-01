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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import com.altastata.filesystem.securecloud.OpsExecutors
import com.altastata.utils.Account
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Live GCS microbench: many tiny objects + 8 MiB chunks, parallel put/get.
 * Not a unit test — run explicitly:
 *   ./gradlew :altastata-core:test --tests com.altastata.cloud.google.GoogleStoragePerfBenchSpec
 */
@RunWith(classOf[JUnitRunner])
class GoogleStoragePerfBenchSpec extends AnyFunSuite {

  private val SmallCount = 200
  private val SmallSize = 1024
  private val ChunkCount = 16
  private val ChunkSize = 8 * 1024 * 1024

  test("GCS parallel small + 8MiB chunk put/get throughput") {
    val accountDir = Option(System.getenv("ALTASTATA_ACCOUNT_DIR")).filter(_.nonEmpty)
      .getOrElse("")
    assume(accountDir.nonEmpty && new File(accountDir).isDirectory,
      "skip: set ALTASTATA_ACCOUNT_DIR to a Google account directory")
    val account = new Account()
    val errors = account.loadAccountProperties(accountDir)
    assert(errors.isEmpty, errors.mkString("; "))
    account.setPassword(sys.env.getOrElse("ALTASTATA_IT_PASSWORD", "123").toCharArray)

    val gm = account.cloudObjectHandler.asInstanceOf[GoogleCloudObjectHandler].googleManager
    val bucket = account.CHUNKS_BUCKET + "-" + account.MY_USER
    val prefix = s"perfbench/${UUID.randomUUID().toString}"
    val ecUp: ExecutionContext = OpsExecutors.ecCloudObjectUploadOps
    val ecDown: ExecutionContext = OpsExecutors.ecCloudObjectDownloadOps

    def timed[T](label: String)(body: => T): T = {
      val t0 = System.nanoTime()
      val r = body
      val ms = (System.nanoTime() - t0) / 1e6
      println(f"[GCS-PERF] $label%40s  ${ms}%10.1f ms")
      r
    }

    def putAll(keys: Seq[String], payload: Array[Byte], ec: ExecutionContext): Unit = {
      val failed = new AtomicInteger(0)
      val futs = keys.map { k =>
        gm.storeInGoogleStorage(payload, bucket, k, payload.length).recover {
          case t => failed.incrementAndGet(); throw t
        }(ec)
      }
      Await.result(Future.sequence(futs)(implicitly, ec), 10.minutes)
      assert(failed.get() == 0, s"puts failed: ${failed.get()}")
    }

    def getAll(keys: Seq[String], expectLen: Int, ec: ExecutionContext): Unit = {
      val failed = new AtomicInteger(0)
      val futs = keys.map { k =>
        gm.retrieveFromGoogleStorage(bucket, k).map { b =>
          assert(b.length == expectLen, s"$k len=${b.length}")
          b
        }(ec).recover {
          case t => failed.incrementAndGet(); throw t
        }(ec)
      }
      Await.result(Future.sequence(futs)(implicitly, ec), 10.minutes)
      assert(failed.get() == 0, s"gets failed: ${failed.get()}")
    }

    def delAll(keys: Seq[String]): Unit = {
      keys.foreach { k =>
        try gm.deleteObjectFromGoogle(bucket, k) catch { case _: Exception => () }
      }
    }

    val smallKeys = (0 until SmallCount).map(i => f"$prefix/small-$i%04d")
    val chunkKeys = (0 until ChunkCount).map(i => f"$prefix/chunk-$i%02d")
    val smallPayload = Array.fill(SmallSize)(0x41.toByte)
    val chunkPayload = Array.fill(ChunkSize)(0x42.toByte)

    println(s"[GCS-PERF] bucket=$bucket prefix=$prefix")
    println(s"[GCS-PERF] small=${SmallCount}x${SmallSize}B  chunks=${ChunkCount}x${ChunkSize}B")

    // warmup (not timed)
    val warmKeys = (0 until 8).map(i => s"$prefix/warm-$i")
    putAll(warmKeys, smallPayload, ecUp)
    getAll(warmKeys, SmallSize, ecDown)
    delAll(warmKeys)

    timed(s"PUT  ${SmallCount}x${SmallSize}B parallel") {
      putAll(smallKeys, smallPayload, ecUp)
    }
    timed(s"GET  ${SmallCount}x${SmallSize}B parallel") {
      getAll(smallKeys, SmallSize, ecDown)
    }
    timed(s"PUT  ${ChunkCount}x8MiB parallel") {
      putAll(chunkKeys, chunkPayload, ecUp)
    }
    timed(s"GET  ${ChunkCount}x8MiB parallel") {
      getAll(chunkKeys, ChunkSize, ecDown)
    }

    delAll(smallKeys ++ chunkKeys)
    println("[GCS-PERF] cleanup done")
  }
}
