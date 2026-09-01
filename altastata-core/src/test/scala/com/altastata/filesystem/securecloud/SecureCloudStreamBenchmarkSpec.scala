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

/*
 * Optional timing benchmark for stream I/O (not a correctness gate).
 * Skipped unless RUN_STREAM_BENCHMARK=1.
 *
 * Run (either branch):
 *   RUN_AZURE_IT=1 RUN_STREAM_BENCHMARK=1 ./gradlew :altastata-core:cleanTest :altastata-core:test \
 *     --tests 'com.altastata.filesystem.securecloud.SecureCloudStreamBenchmarkSpec' 2>&1 \
 *     | rg 'BENCHMARK_'
 */

package com.altastata.filesystem.securecloud

import java.io.{File, InputStream, OutputStream}
import java.security.MessageDigest

import com.altastata.api.{AccountRegistry, AltaStataFileSystem}
import com.altastata.utils.{Account, Constants}

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SecureCloudStreamBenchmarkSpec extends AnyFunSuite {

  private val CHUNK = Constants.PLAIN_CHUNK_MAX_SIZE
  private val branch = sys.env.getOrElse("BENCHMARK_BRANCH", "unknown")

  private def md5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def deterministicBytes(seed: Long, n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    new scala.util.Random(seed).nextBytes(a)
    a
  }

  private def bench(label: String)(body: => Unit): Long = {
    val t0 = System.nanoTime()
    body
    val ms = (System.nanoTime() - t0) / 1000000L
    println(s"BENCHMARK branch=$branch test=$label elapsed_ms=$ms")
    ms
  }

  test("stream write/read benchmark (same payload shape as SecureCloudStreamITSpec)") {
    assume(sys.env.get("RUN_AZURE_IT").contains("1"), "set RUN_AZURE_IT=1")
    assume(sys.env.get("RUN_STREAM_BENCHMARK").contains("1"), "set RUN_STREAM_BENCHMARK=1")

    val accountName = sys.env.getOrElse("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = sys.env.getOrElse("ALTASTATA_IT_PASSWORD", "123")
    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val runId = System.currentTimeMillis()
    val cloudPath = s"STREAMBENCH/$branch/$runId/multi.bin"
    val size = 2 * CHUNK + 12345
    val payload = deterministicBytes(runId, size)
    val expectedMd5 = md5(payload)

    var fs: AltaStataFileSystem = null
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)

      val writeMs = bench("write_multi_chunk") {
        val out = fs.getFileOutputStream(cloudPath, java.lang.Long.valueOf(runId), java.lang.Boolean.FALSE)
        try {
          out.write(payload(0) & 0xFF)
          val mid = CHUNK + 5000
          out.write(payload, 1, mid - 1)
          out.write(payload, mid, size - mid)
        } finally out.close()
      }

      val snapshot = System.currentTimeMillis()

      val readSmallBufMs = bench("read_small_buffer_8200") {
        val in = fs.getFileInputStream(cloudPath, java.lang.Long.valueOf(snapshot), java.lang.Long.valueOf(0L), 4)
        val buf = new Array[Byte](8200)
        val acc = new java.io.ByteArrayOutputStream(size)
        try {
          var n = in.read(buf, 0, buf.length)
          while (n != -1) {
            acc.write(buf, 0, n)
            n = in.read(buf, 0, buf.length)
          }
        } finally in.close()
        assert(md5(acc.toByteArray) == expectedMd5, "read_small_buffer content mismatch")
      }

      val readSingleByteMs = bench("read_single_byte_window") {
        val start = CHUNK - 5L
        val window = 10
        val in = fs.getFileInputStream(cloudPath, java.lang.Long.valueOf(snapshot), java.lang.Long.valueOf(start), 4)
        try {
          var i = 0
          while (i < window) {
            val b = in.read()
            assert(b != -1)
            i += 1
          }
        } finally in.close()
      }

      println(s"BENCHMARK branch=$branch test=summary write_ms=$writeMs read_small_ms=$readSmallBufMs read_single_byte_ms=$readSingleByteMs payload_bytes=$size")
    } finally {
      if (fs != null) {
        try fs.delete(cloudPath, true, null, null)
        catch { case _: Throwable => () }
      }
    }
  }
}
