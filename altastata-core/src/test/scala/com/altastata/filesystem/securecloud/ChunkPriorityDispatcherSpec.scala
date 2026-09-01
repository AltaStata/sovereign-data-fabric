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

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import com.altastata.filesystem.securecloud.ChunkPriorityDispatcher.Chunk

/**
 * Offline unit tests for [[ChunkPriorityDispatcher]] (no cloud) — the primary risk surface.
 * Determinism is achieved with latches: a single worker (poolSize = 1) plus a "blocker" job
 * lets us stage the queue contents before the worker is allowed to drain it.
 */
@RunWith(classOf[JUnitRunner])
class ChunkPriorityDispatcherSpec extends AnyFunSuite with BeforeAndAfterEach {

  private var dispatchers: List[ChunkPriorityDispatcher] = Nil

  /** Track every dispatcher so afterEach can shut workers down (no thread leaks across tests). */
  private def newDispatcher(poolSize: Int, name: String): ChunkPriorityDispatcher = {
    val d = new ChunkPriorityDispatcher(poolSize, name)
    dispatchers = d :: dispatchers
    d
  }

  override def afterEach(): Unit = {
    dispatchers.foreach(_.shutdown())
    dispatchers = Nil
  }

  private val await = 5.seconds

  private def chunks(sizes: Long*): Seq[Chunk] =
    sizes.zipWithIndex.map { case (sz, i) => Chunk(i.toLong, sz) }

  /**
   * Occupy the single worker until the returned latch is counted down, so a test can stage
   * the queue while the worker is parked. Returns (release, started) — release lets the
   * blocker finish; started fires once the worker is actually inside the blocker.
   */
  private def parkWorker(d: ChunkPriorityDispatcher): (CountDownLatch, Future[Unit]) = {
    val release = new CountDownLatch(1)
    val started = new CountDownLatch(1)
    val f = d.submit(chunks(Long.MaxValue)) { _ =>
      started.countDown()
      release.await()
    }
    started.await() // ensure the worker is parked before the caller stages more jobs
    (release, f)
  }

  // Record dispatch order *inside* process: with a single worker, process runs strictly in
  // the order the dispatcher hands out chunks. (Recording in a Future callback would race on
  // the global EC and reorder.)
  private val seqEc = scala.concurrent.ExecutionContext.global

  test("SRPT order: with one worker, smaller files complete first regardless of submit order") {
    val d = newDispatcher(1, "srpt-order")
    val (release, _) = parkWorker(d)

    val order = new ConcurrentLinkedQueue[String]()
    // Submit large first, then small — order must be decided by size, not arrival.
    val big = d.submit(chunks(1000)) { _ => order.add("big") }
    val small = d.submit(chunks(10)) { _ => order.add("small") }
    val medium = d.submit(chunks(100)) { _ => order.add("medium") }

    release.countDown()
    Await.result(Future.sequence(List(big, small, medium))(implicitly, seqEc), await)

    assert(order.asScala.toList === List("small", "medium", "big"))
  }

  test("FIFO tie-break: equal remaining bytes complete in submission order") {
    val d = newDispatcher(1, "fifo")
    val (release, _) = parkWorker(d)

    val order = new ConcurrentLinkedQueue[Int]()
    val futures = (1 to 5).map { idx => d.submit(chunks(100)) { _ => order.add(idx) } }

    release.countDown()
    Await.result(Future.sequence(futures.toList)(implicitly, seqEc), await)

    assert(order.asScala.toList === List(1, 2, 3, 4, 5))
  }

  test("Tail priority: a partially-done large file's tail beats a freshly-arrived medium file") {
    val d = newDispatcher(1, "tail")
    val (releaseBlocker, _) = parkWorker(d)

    val order = new ConcurrentLinkedQueue[String]()
    val chunk0Entered = new CountDownLatch(1)
    val releaseChunk0 = new CountDownLatch(1)

    // BIG has two 100-byte chunks (total 200). Its first chunk parks; while parked we add MED.
    val big = d.submit(chunks(100, 100)) { c =>
      order.add("big")
      if (c.id == 0L) {
        chunk0Entered.countDown()
        releaseChunk0.await()
      }
    }

    releaseBlocker.countDown() // worker now picks BIG, claims chunk0 (remaining 200 -> 100), parks
    chunk0Entered.await()

    // MED arrives now with key 150. BIG's re-enqueued tail has key 100, so the tail must win.
    val med = d.submit(chunks(150)) { _ => order.add("med") }

    releaseChunk0.countDown()
    Await.result(Future.sequence(List(big, med))(implicitly, seqEc), await)

    assert(order.asScala.toList === List("big", "big", "med"))
  }

  test("Bytes beat chunk-count: a few-byte single-chunk file beats an 8MB single-chunk file") {
    val d = newDispatcher(1, "bytes")
    val (release, _) = parkWorker(d)

    val order = new ConcurrentLinkedQueue[String]()
    val bigTail = d.submit(chunks(8L * 1024 * 1024)) { _ => order.add("big") }
    val tiny = d.submit(chunks(10)) { _ => order.add("tiny") }

    release.countDown()
    Await.result(Future.sequence(List(bigTail, tiny))(implicitly, seqEc), await)

    assert(order.asScala.toList === List("tiny", "big"))
  }

  test("Single file / N chunks / M workers: each chunk processed exactly once") {
    val d = newDispatcher(8, "exactly-once")
    val n = 200
    val seen = new ConcurrentHashMap[Long, Integer]()
    val f = d.submit((0L until n.toLong).map(id => Chunk(id, 1000))) { c =>
      seen.merge(c.id, 1, (a, b) => a + b)
    }
    Await.result(f, await)

    assert(seen.size() === n)
    assert(seen.values().asScala.forall(_ == 1), "every chunk processed exactly once")
  }

  test("Concurrency stress: many files, random sizes — no lost or duplicate chunks") {
    val d = newDispatcher(16, "stress")
    val rnd = new scala.util.Random(42)
    val processed = new AtomicInteger(0)
    val duplicates = new AtomicInteger(0)
    val seen = new ConcurrentHashMap[String, Integer]()

    val futures = (0 until 100).map { fileIdx =>
      val nChunks = 1 + rnd.nextInt(20)
      val cs = (0 until nChunks).map(id => Chunk(id.toLong, 1L + rnd.nextInt(1000)))
      d.submit(cs) { c =>
        val key = s"$fileIdx-${c.id}"
        if (seen.putIfAbsent(key, 1) != null) duplicates.incrementAndGet()
        processed.incrementAndGet()
      }
    }
    Await.result(Future.sequence(futures.toList)(implicitly, scala.concurrent.ExecutionContext.global), 30.seconds)

    assert(duplicates.get() === 0, "no chunk processed twice")
    assert(processed.get() === seen.size(), "processed count matches unique chunks")
  }

  test("Error propagation: a throwing chunk fails its file; other files are unaffected") {
    val d = newDispatcher(4, "errors")
    val good1 = d.submit(chunks(10, 10, 10)) { _ => () }
    val bad = d.submit(chunks(10, 10, 10)) { c =>
      if (c.id == 1L) throw new RuntimeException("boom")
    }
    val good2 = d.submit(chunks(10, 10, 10)) { _ => () }

    Await.result(good1, await)
    Await.result(good2, await)
    val ex = intercept[RuntimeException](Await.result(bad, await))
    assert(ex.getMessage === "boom")
  }

  test("Fatal error (OOM): file Future fails and worker stays alive") {
    val d = newDispatcher(2, "fatal-oom")
    val bad = d.submit(chunks(10, 10, 10)) { c =>
      if (c.id == 0L) throw new OutOfMemoryError("simulated heap pressure")
    }
    val good = d.submit(chunks(10, 10)) { _ => () }

    val thrown = intercept[Throwable](Await.result(bad, await))
    val root = thrown match {
      case e: java.util.concurrent.ExecutionException if e.getCause != null => e.getCause
      case t => t
    }
    assert(root.isInstanceOf[OutOfMemoryError], s"expected OOM, got $root")
    assert(root.getMessage === "simulated heap pressure")
    Await.result(good, await)
    assert(d.aliveWorkerCount === 2, "workers must survive Fatal errors so capacity is not permanently lost")
  }

  test("Fast-fail: after the first chunk fails, remaining chunks skip I/O") {
    val d = newDispatcher(1, "fast-fail")
    val n = 100
    val processCalls = new AtomicInteger(0)
    val f = d.submit((0L until n.toLong).map(id => Chunk(id, 1000))) { _ =>
      processCalls.incrementAndGet()
      throw new RuntimeException("fail-first")
    }
    intercept[RuntimeException](Await.result(f, await))
    // With one worker the first claimed chunk throws and sets firstError; every later chunk
    // is skipped, so process runs exactly once (and certainly far fewer than n times).
    assert(processCalls.get() === 1, s"expected 1 process call, got ${processCalls.get()}")
  }

  test("Lone large file: all workers become active on it (max throughput)") {
    val poolSize = 8
    val d = newDispatcher(poolSize, "lone-large")
    val concurrent = new AtomicInteger(0)
    val maxConcurrent = new AtomicInteger(0)
    val allActive = new CountDownLatch(poolSize)
    val release = new CountDownLatch(1)

    val f = d.submit((0L until 32L).map(id => Chunk(id, 1000))) { _ =>
      val cur = concurrent.incrementAndGet()
      maxConcurrent.getAndAccumulate(cur, math.max)
      allActive.countDown()
      release.await()
      concurrent.decrementAndGet()
    }

    assert(allActive.await(5, TimeUnit.SECONDS), "all workers should be busy on the single file")
    release.countDown()
    Await.result(f, await)
    assert(maxConcurrent.get() === poolSize)
  }

  test("Lifecycle: shutdown stops all workers") {
    val d = new ChunkPriorityDispatcher(4, "lifecycle")
    assert(d.aliveWorkerCount === 4)
    d.shutdown()
    val deadline = System.currentTimeMillis() + 5000
    while (d.aliveWorkerCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assert(d.aliveWorkerCount === 0, "all workers should terminate after shutdown")
  }

  test("Empty submit completes immediately") {
    val d = newDispatcher(2, "empty")
    Await.result(d.submit(Seq.empty[Chunk]) { _ => () }, await)
  }
}
