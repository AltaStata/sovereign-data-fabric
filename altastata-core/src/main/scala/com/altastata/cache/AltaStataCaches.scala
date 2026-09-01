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

package com.altastata.cache

import com.github.benmanes.caffeine.cache.{Cache, Caffeine, Weigher}
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.util.function.BiFunction
import scala.collection.concurrent.TrieMap

/**
 * All ciphertext we cache for one file (one `storageFilePathIncludingVersion`).
 *
 * Three slots:
 *   - metadata: the encrypted StorageObjectMetadata blob from CATALOG_BUCKET
 *   - chunk0:   the encrypted chunk 0 from CHUNKS_BUCKET (only single-chunk small files)
 *   - attrs:    encrypted per-attribute blobs from DATA_PROPERTIES_BUCKET ("size", "eTag", ...)
 *
 * Mutated through [[AltaStataCaches]] putters that go through `cache.asMap().compute(...)`,
 * so Caffeine re-runs the weigher and keeps the per-account memory budget accurate.
 */
final class CachedFile {
  @volatile var metadata: Array[Byte] = null
  @volatile var chunk0:   Array[Byte] = null
  val attrs: TrieMap[String, Array[Byte]] = TrieMap.empty
}

/**
 * Per-Account bounded ciphertext cache.
 *
 * One instance lives on each [[com.altastata.utils.Account]]. Accounts therefore have fully
 * isolated caches and a logout simply throws this whole instance away (or calls
 * [[invalidateAll]]).
 *
 * Single Caffeine cache keyed by `storageFilePathIncludingVersion`. The value is a
 * [[CachedFile]] holding metadata + small-file chunk 0 + per-attribute blobs together,
 * so:
 *   - a revocation event (e.g. EVENT_REMOVE_READER) only needs `invalidate(path)` to
 *     drop everything related to that file in one shot;
 *   - eviction is coupled — when the bundle leaves, no orphaned chunk/attr ciphertext
 *     stays behind without its metadata key to decrypt it.
 *
 * Sizing comes from `*.user.properties` via [[Account]]:
 *   - cache-size-bytes        (default: 280 MiB per account)
 *
 * The Caffeine cache is built lazily on first use because Account properties are loaded
 * after Account construction. The size is therefore captured the first time any cache
 * method is called — which is always after login, so properties are available.
 *
 * Admission policy for chunks: ciphertext chunk 0 of single-chunk files may be cached when loaded via `retrieveCloudFileContent`;
 * callers may serve from cache earlier via `tryServeSingleChunkFromCacheIfSizeMatches` after validating decrypted `size`.
 *
 * Security model: every value stored here is the same ciphertext that lives in cloud
 * object storage. Decryption happens per-request using account-private keys.
 */
final class AltaStataCaches(account: Account) {

  import AltaStataCaches._

  /** Built lazily so we can read sizing from `account.cacheSizeBytes` after properties load. */
  lazy val cache: Cache[String, CachedFile] = {
    val maxBytes = account.cacheSizeBytes
    logger.info(s"AltaStataCaches: building cache with maxBytes=$maxBytes (cache-size-bytes property)")
    build(maxBytes)
  }

  /* ---------- reads (lock-free; null on miss) ---------- */

  /**
   * Retrieves the encrypted metadata blob from the cache.
   *
   * @param path The full object path (key).
   * @return The raw encrypted byte array, or `null` if not in cache.
   */
  def getMetadata(path: String): Array[Byte] = {
    val cf = cache.getIfPresent(path)
    if (cf == null) null else cf.metadata
  }

  /**
   * Retrieves the cached chunk 0 ciphertext for single-chunk files.
   *
   * @param path The full object path (key).
   * @return The raw encrypted chunk 0 byte array, or `null` if not in cache.
   */
  def getChunk(path: String): Array[Byte] = {
    val cf = cache.getIfPresent(path)
    if (cf == null) null else cf.chunk0
  }

  /** True if the chunk for this path is currently in cache. Lock-free, cheap to call from the UI. */
  /**
   * True if the chunk for this path is currently in cache. Lock-free, cheap to call from the UI.
   *
   * @param path The full object path (key).
   * @return True if chunk 0 ciphertext is present.
   */
  def hasChunk(path: String): Boolean = {
    val cf = cache.getIfPresent(path)
    cf != null && cf.chunk0 != null
  }

  /**
   * Retrieves an encrypted custom attribute value from the cache.
   *
   * @param path The full object path (key).
   * @param name The attribute name (e.g., "size", "eTag").
   * @return The encrypted attribute bytes, or `null` if not cached.
   */
  def getAttr(path: String, name: String): Array[Byte] = {
    val cf = cache.getIfPresent(path)
    if (cf == null) null else cf.attrs.getOrElse(name, null)
  }

  /* ---------- writes (go through compute() so Caffeine re-weighs) ---------- */

  /**
   * Caches the encrypted metadata blob for a file.
   *
   * @param path  The full object path (key).
   * @param bytes The raw encrypted metadata bytes.
   */
  def putMetadata(path: String, bytes: Array[Byte]): Unit =
    upsert(path, (cf: CachedFile) => cf.metadata = bytes)

  /**
   * Caches the encrypted chunk 0 ciphertext for single-chunk files.
   *
   * @param path  The full object path (key).
   * @param bytes The raw encrypted chunk 0 bytes.
   */
  def putChunk(path: String, bytes: Array[Byte]): Unit =
    upsert(path, (cf: CachedFile) => cf.chunk0 = bytes)

  /**
   * Caches an encrypted custom file attribute.
   *
   * @param path  The full object path (key).
   * @param name  The attribute name.
   * @param bytes The encrypted attribute bytes.
   */
  def putAttr(path: String, name: String, bytes: Array[Byte]): Unit =
    upsert(path, (cf: CachedFile) => { cf.attrs.put(name, bytes); () })

  /**
   * Clears ciphertext chunk0 only (e.g. size or chunk changed on cloud).
   *
   * @param path The full object path (key).
   */
  def clearChunk(path: String): Unit =
    upsert(path, (cf: CachedFile) => cf.chunk0 = null)

  /* ---------- invalidations ---------- */

  /**
   * Drop one attribute slot in a file's bundle, leaving metadata/chunk untouched.
   *
   * @param path The full object path (key).
   * @param name The attribute name to invalidate.
   */
  def invalidateAttr(path: String, name: String): Unit = {
    cache.asMap().computeIfPresent(path, new BiFunction[String, CachedFile, CachedFile] {
      override def apply(k: String, cf: CachedFile): CachedFile = { cf.attrs.remove(name); cf }
    })
  }

  /**
   * Drop the entire bundle for a file (used on delete and on access-revocation events).
   *
   * @param path The full object path (key).
   */
  def invalidate(path: String): Unit = cache.invalidate(path)

  /**
   * Drop everything (called on Account logout / reset).
   */
  def invalidateAll(): Unit = cache.invalidateAll()

  /* ---------- internals ---------- */

  /**
   * Helper method to atomically create or update a cached file entry.
   *
   * @param path   The full object path (key).
   * @param mutate A function to mutate the CachedFile instance.
   */
  private def upsert(path: String, mutate: CachedFile => Unit): Unit = {
    cache.asMap().compute(path, new BiFunction[String, CachedFile, CachedFile] {
      override def apply(k: String, existing: CachedFile): CachedFile = {
        val cf = if (existing == null) new CachedFile else existing
        mutate(cf)
        cf
      }
    })
  }
}

object AltaStataCaches {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Helper factory to instantiate and configure a thread-safe Caffeine Cache instance.
   * Uses a custom Weigher to accurately calculate memory consumption in bytes.
   *
   * @param maxBytes The maximum memory budget in bytes allowed for cached files.
   * @return A configured Caffeine Cache instance.
   */
  private def build(maxBytes: Long): Cache[String, CachedFile] = {
    val weigher: Weigher[String, CachedFile] = new Weigher[String, CachedFile] {
      override def weigh(key: String, value: CachedFile): Int = {
        var w: Long = 0L
        val m = value.metadata; if (m != null) w += m.length
        val c = value.chunk0;   if (c != null) w += c.length
        val it = value.attrs.iterator
        while (it.hasNext) {
          val (n, v) = it.next()
          // Approximate name weight; values are the dominant cost.
          w += (if (n == null) 0 else n.length) + (if (v == null) 0 else v.length)
        }
        if (w < 0L || w > Int.MaxValue.toLong) Int.MaxValue else w.toInt
      }
    }
    Caffeine
      .newBuilder()
      .maximumWeight(maxBytes)
      .weigher[String, CachedFile](weigher)
      .recordStats()
      .build[String, CachedFile]()
  }
}
