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

import java.io.{File, InputStream, OutputStream}
import java.security.MessageDigest
import scala.collection.JavaConverters._

import com.altastata.api.{AccountRegistry, AltaStataFileSystem}
import com.altastata.utils.{Account, Constants}

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Integration tests for the stream I/O optimizations in `SecureCloudStream`
 * ([[com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream]] and
 * [[com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream]]),
 * exercised against a real cloud account through the public API
 * (`getFileOutputStream` / `getFileInputStream`).
 *
 * These classes can only be constructed with a live `Account` (their constructors list/create
 * the cloud file and read its `size` attribute), so the optimizations are validated end-to-end
 * here rather than in an offline unit test. The chunk-boundary math they rely on is covered
 * offline in `SecureCloudChunkingSpec`.
 *
 * Coverage maps to STREAM_IO_DESIGN.md:
 *   - Phase 1.1 OutputStream bulk write (write crossing chunk boundaries in a single call,
 *     plus single-byte write) round-trips byte-for-byte, incl. a partial final chunk.
 *   - Phase 1.2 InputStream "current chunk" model: small-buffer reads never short before EOF;
 *     single-byte read() across a chunk boundary; skip()/seek invalidates the current chunk;
 *     mid-file start position; EOF and available().
 *   - Phase 2 backpressure: a file with more chunks than the in-flight cap still round-trips.
 *   - §2.4 append mode extends an existing partial-tail file.
 *
 * Cost guard: skipped unless RUN_AZURE_IT=1. Account selection mirrors TransferSchedulingAzureITSpec:
 *   RUN_AZURE_IT=1                enable the test
 *   ALTASTATA_IT_ACCOUNT=...      account dir name (default: azure.rsa.bob123)
 *   ALTASTATA_IT_PASSWORD=...     account password (default: 123)
 *
 * Run:
 *   RUN_AZURE_IT=1 ./gradlew :altastata-core:test \
 *     --tests 'com.altastata.filesystem.securecloud.SecureCloudStreamITSpec'
 */
@RunWith(classOf[JUnitRunner])
class SecureCloudStreamITSpec extends AnyFunSuite {

  private def env(k: String, default: String): String = sys.env.getOrElse(k, default)

  private val CHUNK = Constants.PLAIN_CHUNK_MAX_SIZE

  private def md5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def deterministicBytes(seed: Long, n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    new scala.util.Random(seed).nextBytes(a)
    a
  }

  /** Drain a stream to a byte array (size = -1 → read to EOF). */
  private def drain(fs: AltaStataFileSystem, in: InputStream): Array[Byte] =
    try fs.readBufferFromInputStream(in, -1) finally in.close()

  /**
   * Open an account if RUN_AZURE_IT is set and the account dir exists; otherwise skip the test.
   * Returns (fs, uniqueCloudPrefix). The caller deletes the prefix in a finally block.
   */
  private def withAccount(testTag: String)(body: (AltaStataFileSystem, String) => Unit): Unit = {
    assume(sys.env.get("RUN_AZURE_IT").contains("1"), "set RUN_AZURE_IT=1 to run the stream integration test")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "azure.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    assume(new File(accountDir).isDirectory, s"account dir not found: $accountDir")

    val cloudPrefix = s"STREAMIT/${testTag}_${System.currentTimeMillis()}"
    var fs: AltaStataFileSystem = null
    try {
      fs = AccountRegistry.getOrCreateFromDir(accountDir)
      fs.setPassword(password)
      body(fs, cloudPrefix)
    } finally {
      if (fs != null) {
        try fs.delete(cloudPrefix, true, null, null)
        catch { case t: Throwable => info(s"cloud cleanup failed for $cloudPrefix: ${t.getMessage}") }
      }
    }
  }

  /** Write `payload` to a fresh cloud file via the output stream; return a read snapshot time. */
  private def writeFresh(fs: AltaStataFileSystem, path: String)(write: OutputStream => Unit): Long = {
    val out = fs.getFileOutputStream(path, java.lang.Long.valueOf(System.currentTimeMillis()), java.lang.Boolean.FALSE)
    try write(out) finally out.close()
    System.currentTimeMillis()
  }

  private def openRead(fs: AltaStataFileSystem, path: String, snapshot: Long, start: Long): InputStream =
    fs.getFileInputStream(path, java.lang.Long.valueOf(snapshot), java.lang.Long.valueOf(start), 4)

  // ---------------------------------------------------------------------------

  test("bulk write + read: multi-chunk file with a partial final chunk round-trips byte-for-byte") {
    withAccount("bulk") { (fs, prefix) =>
      val path = s"$prefix/multi.bin"
      val size = 2 * CHUNK + 12345 // 3 chunks: full, full, partial
      val payload = deterministicBytes(1, size)

      val snapshot = writeFresh(fs, path) { out =>
        // Single-byte write (write(b:Int) path).
        out.write(payload(0) & 0xFF)
        // One write call that crosses a chunk boundary (bulk write internal loop), then the rest.
        val mid = CHUNK + 5000
        out.write(payload, 1, mid - 1)
        out.write(payload, mid, size - mid)
      }

      val got = drain(fs, openRead(fs, path, snapshot, 0L))
      assert(got.length === size, s"length ${got.length} != $size")
      assert(md5(got) === md5(payload), "content mismatch")
    }
  }

  test("InputStream: small-buffer reads never return short before EOF") {
    withAccount("noshort") { (fs, prefix) =>
      val path = s"$prefix/noshort.bin"
      val size = CHUNK + 4096 // crosses one boundary
      val payload = deterministicBytes(2, size)
      val snapshot = writeFresh(fs, path)(_.write(payload))

      val in = openRead(fs, path, snapshot, 0L)
      try {
        val bufLen = 8200 // not a divisor of CHUNK, so reads land mid-chunk and across the boundary
        val buf = new Array[Byte](bufLen)
        val acc = new java.io.ByteArrayOutputStream(size)
        var total = 0
        var n = in.read(buf, 0, bufLen)
        while (n != -1) {
          acc.write(buf, 0, n)
          total += n
          // A short read is only allowed when it reached EOF (no bytes remain in the file).
          if (n < bufLen) assert(total === size, s"short read ($n < $bufLen) before EOF at total=$total")
          n = in.read(buf, 0, bufLen)
        }
        assert(total === size)
        assert(md5(acc.toByteArray) === md5(payload))
      } finally in.close()
    }
  }

  test("InputStream: single-byte read() reconstructs bytes across a chunk boundary") {
    withAccount("singlebyte") { (fs, prefix) =>
      val path = s"$prefix/single.bin"
      val size = CHUNK + 100
      val payload = deterministicBytes(3, size)
      val snapshot = writeFresh(fs, path)(_.write(payload))

      val start = CHUNK - 5L // read window straddles the chunk0/chunk1 boundary
      val window = 10
      val in = openRead(fs, path, snapshot, start)
      try {
        val out = new Array[Byte](window)
        var i = 0
        while (i < window) {
          val b = in.read()
          assert(b != -1, s"unexpected EOF at offset ${start + i}")
          out(i) = b.toByte
          i += 1
        }
        val expected = java.util.Arrays.copyOfRange(payload, start.toInt, start.toInt + window)
        assert(java.util.Arrays.equals(out, expected), "single-byte window mismatch across boundary")
      } finally in.close()
    }
  }

  test("InputStream: skip() seeks (absolute) and invalidates the current chunk") {
    withAccount("skip") { (fs, prefix) =>
      val path = s"$prefix/skip.bin"
      val size = 2 * CHUNK + 2000
      val payload = deterministicBytes(4, size)
      val snapshot = writeFresh(fs, path)(_.write(payload))

      val in = openRead(fs, path, snapshot, 0L)
      try {
        // Read a little from chunk 0.
        val head = new Array[Byte](100)
        assert(in.read(head, 0, 100) === 100)
        assert(java.util.Arrays.equals(head, java.util.Arrays.copyOfRange(payload, 0, 100)))

        // skip(n) in this stream is an ABSOLUTE seek (used by Hadoop seek); jump across a boundary.
        val fwd = CHUNK + 777L
        in.skip(fwd)
        val tail = new Array[Byte](200)
        readFully(in, tail)
        assert(java.util.Arrays.equals(tail, java.util.Arrays.copyOfRange(payload, fwd.toInt, fwd.toInt + 200)),
          "forward seek across boundary returned wrong bytes")

        // Seek backwards into chunk 0 (current chunk must be reloaded).
        in.skip(50L)
        val back = new Array[Byte](10)
        readFully(in, back)
        assert(java.util.Arrays.equals(back, java.util.Arrays.copyOfRange(payload, 50, 60)),
          "backward seek returned wrong bytes")
      } finally in.close()
    }
  }

  test("InputStream: mid-file start position returns the tail; available() is the full size") {
    withAccount("startpos") { (fs, prefix) =>
      val path = s"$prefix/startpos.bin"
      val size = 2 * CHUNK + 333
      val payload = deterministicBytes(5, size)
      val snapshot = writeFresh(fs, path)(_.write(payload))

      val start = CHUNK + 12345L
      val in = openRead(fs, path, snapshot, start)
      val avail = in.available()
      val got = drain(fs, in)
      val expectedTail = java.util.Arrays.copyOfRange(payload, start.toInt, size)
      assert(got.length === expectedTail.length)
      assert(md5(got) === md5(expectedTail), "tail mismatch from mid-file start")
      assert(avail === size, s"available()=$avail should report full size=$size")
    }
  }

  test("InputStream: reading at EOF returns -1") {
    withAccount("eof") { (fs, prefix) =>
      val path = s"$prefix/eof.bin"
      val size = CHUNK + 7 // EOF lands mid-(last)chunk
      val payload = deterministicBytes(6, size)
      val snapshot = writeFresh(fs, path)(_.write(payload))

      val in = openRead(fs, path, snapshot, size.toLong) // start exactly at EOF
      try {
        assert(in.read() === -1, "read() at EOF should be -1")
        assert(in.read(new Array[Byte](16), 0, 16) === -1, "read(buf) at EOF should be -1")
      } finally in.close()
    }
  }

  test("backpressure: more chunks than per-stream cap round-trips") {
    // Per-stream cap is MAX_PENDING_CHUNKS_PER_STREAM (=8). Write enough full chunks to
    // exceed the cap so storePlainTextBuffer must await the oldest upload at least once.
    withAccount("backpressure") { (fs, prefix) =>
      val path = s"$prefix/many-chunks.bin"
      val chunks = OpsExecutors.MAX_PENDING_CHUNKS_PER_STREAM + 2 // 10 → forces cap path
      val size = chunks * CHUNK + 11 // last chunk partial
      val payload = deterministicBytes(7, size)

      // Write in moderate blocks so multiple full chunks flush while earlier uploads are still
      // in flight (queue grows, then successful futures are drained on subsequent flushes).
      val snapshot = writeFresh(fs, path) { out =>
        var p = 0
        val block = 1 << 20 // 1 MiB
        while (p < size) {
          val n = math.min(block, size - p)
          out.write(payload, p, n)
          p += n
        }
      }

      val got = drain(fs, openRead(fs, path, snapshot, 0L))
      assert(got.length === size, s"length ${got.length} != $size")
      assert(md5(got) === md5(payload), "content mismatch under backpressure")
    }
  }

  test("append mode extends a partial-tail file and round-trips as the concatenation") {
    withAccount("append") { (fs, prefix) =>
      val path = s"$prefix/append.bin"
      val baseSize = CHUNK + 100 // partial last chunk, so append resumes mid-chunk
      val base = deterministicBytes(8, baseSize)
      writeFresh(fs, path)(_.write(base))

      // Append enough to fill the partial last chunk and spill into a new chunk.
      val extra = deterministicBytes(9, CHUNK)
      val outAppend = fs.getFileOutputStream(path, java.lang.Long.valueOf(System.currentTimeMillis()), java.lang.Boolean.TRUE)
      try outAppend.write(extra) finally outAppend.close()
      val snapshot = System.currentTimeMillis()

      val got = drain(fs, openRead(fs, path, snapshot, 0L))
      val expected = base ++ extra
      assert(got.length === expected.length, s"length ${got.length} != ${expected.length}")
      assert(md5(got) === md5(expected), "append concatenation mismatch")
    }
  }

  test("empty file: close with no writes round-trips to zero bytes") {
    withAccount("empty") { (fs, prefix) =>
      val path = s"$prefix/empty.bin"
      val snapshot = writeFresh(fs, path)(_ => ())

      val in = openRead(fs, path, snapshot, 0L)
      try assert(in.read() === -1, "empty file read() should be -1")
      finally in.close()

      val got = drain(fs, openRead(fs, path, snapshot, 0L))
      assert(got.length === 0, s"empty file should drain to 0 bytes, got ${got.length}")
    }
  }

  /** Read exactly buf.length bytes or fail (the stream guarantees fill-to-len before EOF). */
  private def readFully(in: InputStream, buf: Array[Byte]): Unit = {
    var off = 0
    while (off < buf.length) {
      val n = in.read(buf, off, buf.length - off)
      assert(n != -1, s"unexpected EOF after $off/${buf.length} bytes")
      off += n
    }
  }
}
