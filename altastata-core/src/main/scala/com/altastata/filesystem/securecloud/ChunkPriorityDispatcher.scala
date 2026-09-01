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

import java.util.concurrent.{ConcurrentLinkedQueue, PriorityBlockingQueue}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import org.slf4j.LoggerFactory

/**
 * SRPT (Shortest Remaining Processing Time) chunk dispatcher.
 *
 * A single shared work queue ordered by each file's *remaining bytes* so that small
 * transfers overtake large ones — including small files arriving from a concurrent
 * request — while a large file that is almost done finishes rather than being preempted
 * forever. See `TRANSFER_SCHEDULING_DESIGN.md` for the full rationale.
 *
 * Design invariants (do not "optimize" away without re-reading the design):
 *  - Single-token: at most one [[Node]] per [[FileJob]] is ever in the queue, so a job's
 *    chunk claim (`chunks.poll`) and key update are effectively serialized by the queue.
 *  - Snapshot key: a [[Node]] captures `remainingBytes` at insertion; the comparator never
 *    reads the live counter (that would corrupt `PriorityBlockingQueue` ordering).
 *  - Claim-time decrement: `remainingBytes` drops when a chunk is *claimed*, not when it
 *    completes, so a fully-claimed job stops floating to the top.
 *  - Completion via `outstanding`: the file's `Promise` completes only when the last chunk
 *    has *finished* (which can be after the job left the queue), never on an empty queue.
 *  - Fast-fail: once any chunk of a file fails, the remaining claimed chunks skip I/O and
 *    just drain `outstanding`, so a doomed file collapses instead of transferring all N.
 *
 * @param poolSize       number of worker threads = max chunks in flight = memory bound
 * @param name           thread-name prefix (e.g. "chunk-retrieve", "chunk-store")
 * @param threadPriority worker thread priority (parity with the legacy chunk pools)
 */
final class ChunkPriorityDispatcher(
    val poolSize: Int,
    name: String,
    threadPriority: Int = Thread.NORM_PRIORITY) {
  import ChunkPriorityDispatcher.Chunk

  private val logger = LoggerFactory.getLogger(getClass)

  /** One schedulable transfer: a whole file, or a chunk range for streaming/partial reads. */
  private final class FileJob(
      val chunks: ConcurrentLinkedQueue[Chunk],
      total: Int,
      initialRemainingBytes: Long,
      val process: Chunk => Unit) {
    /** Drives priority; decremented at claim time. Snapshotted into a [[Node]] on insert. */
    val remainingBytes = new AtomicLong(initialRemainingBytes)
    /** Chunks not yet finished; the job is done when this hits 0. */
    val outstanding = new AtomicInteger(total)
    val promise = Promise[Unit]()
    /** First captured failure; benign data race (any one error is enough to report). */
    @volatile var firstError: Throwable = _
  }

  /**
   * Queue element wrapping a [[FileJob]] with a *snapshot* of its priority key and a FIFO
   * sequence number for a stable tie-break among equal keys.
   */
  private final class Node(val job: FileJob, val key: Long, val seq: Long)
      extends Comparable[Node] {
    override def compareTo(o: Node): Int = {
      val c = java.lang.Long.compare(key, o.key) // smaller remaining bytes first
      if (c != 0) c else java.lang.Long.compare(seq, o.seq) // FIFO among equal keys
    }
  }

  private val seq = new AtomicLong()
  private val queue = new PriorityBlockingQueue[Node]()
  @volatile private var running = true

  private def enqueue(job: FileJob): Unit =
    queue.put(new Node(job, job.remainingBytes.get(), seq.getAndIncrement()))

  private val workers: IndexedSeq[Thread] = (0 until poolSize).map { i =>
    val t = new Thread(new Runnable { def run(): Unit = workLoop() }, s"$name-$i")
    t.setDaemon(true)
    t.setPriority(threadPriority)
    t.start()
    t
  }

  private def workLoop(): Unit = {
    while (running) {
      val node =
        try queue.take()
        catch { case _: InterruptedException => null } // shutdown() or spurious interrupt
      if (node != null) {
        val job = node.job
        val chunk = job.chunks.poll() // claim one chunk
        if (chunk != null) {
          job.remainingBytes.addAndGet(-chunk.sizeBytes) // claim-time decrement
          if (!job.chunks.isEmpty) enqueue(job) // siblings stay schedulable
          try {
            if (job.firstError == null) job.process(chunk) // fast-fail: skip I/O after a failure
          } catch {
            // Must record *all* Throwables, including Fatal (OutOfMemoryError, StackOverflowError).
            // NonFatal-only left firstError null while finally still decremented outstanding → the
            // file Future could succeed even though a chunk never uploaded, and the worker died.
            case t: Throwable =>
              if (job.firstError == null) job.firstError = t
              if (!NonFatal(t)) {
                logger.error(s"$name worker: fatal error on chunk id=${chunk.id}; failing job, keeping worker", t)
              }
          } finally {
            if (job.outstanding.decrementAndGet() == 0) // completion only on last finished chunk
              job.promise.complete(
                if (job.firstError == null) Success(()) else Failure(job.firstError))
          }
        }
      }
    }
    // On interrupt: running == false (shutdown) exits the loop; running == true (spurious)
    // loops back to take() and keeps the worker alive — a worker is never lost silently.
  }

  /**
   * Submit a file's chunks for SRPT-ordered processing.
   *
   * @param chunks  the chunks to transfer (id + byte size each)
   * @param process per-chunk work (network I/O + crypto); may throw to fail the file
   * @return a Future that completes when all chunks finish, or fails with the first error
   */
  def submit(chunks: Seq[Chunk])(process: Chunk => Unit): Future[Unit] = {
    val q = new ConcurrentLinkedQueue[Chunk]()
    chunks.foreach(q.add)
    val job = new FileJob(q, chunks.size, chunks.iterator.map(_.sizeBytes).sum, process)
    if (chunks.isEmpty) job.promise.success(()) else enqueue(job)
    job.promise.future
  }

  /** Stop all workers. For tests/clean lifecycle; production keeps one dispatcher per JVM. */
  def shutdown(): Unit = {
    running = false
    workers.foreach(_.interrupt())
  }

  /** Test aid: how many worker threads are still alive. */
  private[securecloud] def aliveWorkerCount: Int = workers.count(_.isAlive)
}

object ChunkPriorityDispatcher {

  /** A unit of work: chunk id within its file plus its byte size (drives SRPT priority). */
  final case class Chunk(id: Long, sizeBytes: Long)
}
