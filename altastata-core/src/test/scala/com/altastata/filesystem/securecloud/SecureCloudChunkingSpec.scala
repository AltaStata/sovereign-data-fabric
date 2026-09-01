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

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import com.altastata.utils.Constants

/**
 * Offline unit tests for the chunk-boundary math that the stream "current chunk" model
 * (see STREAM_IO_DESIGN.md §1.3) is built on: [[SecureCloudOperations.totalChunks]] and
 * [[SecureCloudOperations.chunkSize]].
 *
 * These two pure functions decide, with no cloud access:
 *   - how many chunks a file occupies (`totalChunksNumber`),
 *   - which chunk a byte position falls into (`pos / PLAIN_CHUNK_MAX_SIZE`),
 *   - the size of the (possibly partial) last chunk — i.e. where the InputStream sees EOF
 *     and where the OutputStream resumes appending (`currentChunkId = totalChunks - 1`).
 *
 * They run in CI (no account, no network). We call the *real* trait methods via the
 * `SecureCloudStream` object (which mixes in `SecureCloudOperations`).
 */
@RunWith(classOf[JUnitRunner])
class SecureCloudChunkingSpec extends AnyFunSuite {

  // Call the real implementations under test.
  private def totalChunks(size: Long, chunk: Int): Long = SecureCloudStream.totalChunks(size, chunk)
  private def chunkSize(size: Long, id: Long, chunk: Int): Int = SecureCloudStream.chunkSize(size, id, chunk)

  // Small synthetic chunk size makes boundaries easy to reason about and cheap to enumerate.
  private val C = 4

  test("totalChunks: empty file occupies zero chunks") {
    assert(totalChunks(0L, C) === 0L)
  }

  test("totalChunks: a file smaller than one chunk still occupies one chunk") {
    assert(totalChunks(1L, C) === 1L)
    assert(totalChunks((C - 1).toLong, C) === 1L)
  }

  test("totalChunks: an exact multiple of the chunk size occupies exactly that many chunks") {
    assert(totalChunks(C.toLong, C) === 1L)
    assert(totalChunks((2 * C).toLong, C) === 2L)
    assert(totalChunks((10 * C).toLong, C) === 10L)
  }

  test("totalChunks: one byte past a boundary rolls into the next chunk") {
    assert(totalChunks((C + 1).toLong, C) === 2L)
    assert(totalChunks((2 * C + 1).toLong, C) === 3L)
  }

  test("chunkSize: every chunk but the last is full") {
    val size = 2L * C + 1 // 3 chunks: full, full, 1-byte tail
    assert(chunkSize(size, 0L, C) === C)
    assert(chunkSize(size, 1L, C) === C)
    assert(chunkSize(size, 2L, C) === 1)
  }

  test("chunkSize: an exact multiple makes the last chunk full too") {
    val size = 3L * C
    assert(chunkSize(size, 0L, C) === C)
    assert(chunkSize(size, 1L, C) === C)
    assert(chunkSize(size, 2L, C) === C)
  }

  test("chunkSize: a single partial chunk reports the partial length") {
    assert(chunkSize(3L, 0L, C) === 3)
  }

  test("invariant: chunk sizes sum back to the file size for many sizes") {
    for (size <- 0L to (5L * C)) {
      val n = totalChunks(size, C)
      val sum = (0L until n).map(id => chunkSize(size, id, C).toLong).sum
      assert(sum === size, s"sum of chunk sizes != file size for size=$size")
    }
  }

  test("invariant: only the last chunk may be partial; all earlier chunks are full") {
    for (size <- 1L to (5L * C)) {
      val n = totalChunks(size, C)
      for (id <- 0L until n - 1) {
        assert(chunkSize(size, id, C) === C,
          s"non-last chunk $id of size=$size should be full ($C), got ${chunkSize(size, id, C)}")
      }
      val last = chunkSize(size, n - 1, C)
      assert(last >= 1 && last <= C, s"last chunk of size=$size out of range: $last")
    }
  }

  test("append precondition matches the partial-last-chunk case (OutputStream resume logic)") {
    // The OutputStream reloads the last chunk to keep appending iff `size % chunk > 0`
    // (SecureCloudStream.scala). That must hold exactly when the last chunk is partial.
    for (size <- 1L to (5L * C)) {
      val n = totalChunks(size, C)
      val lastIsPartial = chunkSize(size, n - 1, C) < C
      val resumeCondition = size % C > 0
      assert(lastIsPartial === resumeCondition,
        s"append-resume condition mismatch at size=$size")
    }
  }

  test("byte position maps to the expected chunk id at and around boundaries") {
    // This is the exact expression read()/read(bytes) use: pos / chunkSize.
    val size = 3L * C
    assert((0L / C) === 0L)
    assert(((C - 1).toLong / C) === 0L)
    assert((C.toLong / C) === 1L)          // first byte of chunk 1
    assert(((2L * C - 1) / C) === 1L)
    assert(((2L * C) / C) === 2L)          // first byte of chunk 2
    assert(totalChunks(size, C) === 3L)
  }

  test("real PLAIN_CHUNK_MAX_SIZE: multi-chunk file with a partial tail (matches IT payload shape)") {
    val chunk = Constants.PLAIN_CHUNK_MAX_SIZE
    val size = 2L * chunk + 12345L // the shape used by the round-trip integration test
    assert(totalChunks(size, chunk) === 3L)
    assert(chunkSize(size, 0L, chunk) === chunk)
    assert(chunkSize(size, 1L, chunk) === chunk)
    assert(chunkSize(size, 2L, chunk) === 12345)
  }
}
