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

package com.altastata.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.altastata.api.AltaStataFileSystem;
import com.altastata.cache.AltaStataCaches;
import com.altastata.utils.Account;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Periodic heap + Caffeine-cache sampler for performance runs. Prints {@code [mem]}
 * lines so they show up in the tee'd benchmark log.
 */
public final class PerformanceMemoryProbe {

	private static final long SAMPLE_MS = 2000L;

	private final AltaStataFileSystem fs;
	private final ScheduledExecutorService scheduler;
	private final AtomicReference<String> phase = new AtomicReference<>("init");
	private final AtomicLong peakHeapUsed = new AtomicLong();
	private final AtomicLong peakCaffeineBytes = new AtomicLong();
	private volatile long cacheBudgetBytes = -1L;

	private PerformanceMemoryProbe(AltaStataFileSystem fs) {
		this.fs = fs;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "perf-memory-probe");
			t.setDaemon(true);
			return t;
		});
	}

	public static PerformanceMemoryProbe start(AltaStataFileSystem fs) {
		PerformanceMemoryProbe probe = new PerformanceMemoryProbe(fs);
		probe.mark("start");
		probe.scheduler.scheduleAtFixedRate(probe::sampleQuiet, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
		return probe;
	}

	public void mark(String newPhase) {
		phase.set(newPhase);
		sample(true);
	}

	/**
	 * Request GC and sample retained heap. Use <em>between</em> timed ops: if used
	 * heap climbs after GC from file to file, something is leaking; a spike that
	 * drops back is in-flight chunks, not the Caffeine cache.
	 */
	public void afterGc(String label) {
		phase.set(label + " after-GC");
		System.gc();
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		sample(true);
	}

	public void stop() {
		scheduler.shutdownNow();
		mark("stop");
		System.out.format(
				"[mem] PEAK heapUsed=%.0f MB  caffeine=%.1f MB (budget %.0f MB)%n",
				peakHeapUsed.get() / (1024.0 * 1024.0),
				peakCaffeineBytes.get() / (1024.0 * 1024.0),
				cacheBudgetBytes / (1024.0 * 1024.0));
	}

	private void sampleQuiet() {
		sample(false);
	}

	private void sample(boolean force) {
		try {
			MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
			MemoryUsage heap = mx.getHeapMemoryUsage();
			MemoryUsage nonHeap = mx.getNonHeapMemoryUsage();
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();

			long heapUsed = heap.getUsed();
			long heapCommitted = heap.getCommitted();
			long heapMax = heap.getMax();
			peakHeapUsed.accumulateAndGet(heapUsed, Math::max);

			long caffeineBytes = 0L;
			long caffeineEntries = 0L;
			long evictions = 0L;
			double hitRate = Double.NaN;
			if (fs != null) {
				Account account = fs.getAccount();
				if (cacheBudgetBytes < 0L) {
					cacheBudgetBytes = account.cacheSizeBytes();
				}
				AltaStataCaches caches = account.caches();
				Cache<?, ?> cache = caches.cache();
				caffeineEntries = cache.estimatedSize();
				CacheStats stats = cache.stats();
				evictions = stats.evictionCount();
				hitRate = stats.hitRate();
				Optional<? extends Policy.Eviction<?, ?>> eviction = cache.policy().eviction();
				if (eviction.isPresent()) {
					OptionalLong weighted = eviction.get().weightedSize();
					if (weighted.isPresent()) {
						caffeineBytes = weighted.getAsLong();
					}
				}
				peakCaffeineBytes.accumulateAndGet(caffeineBytes, Math::max);
			}

			double heapPct = heapMax > 0 ? (100.0 * heapUsed / heapMax) : 0.0;
			System.out.format(
					"[mem] %s  heap=%.0f/%.0f MB (%.0f%% of max, committed=%.0f)  nonHeap=%.0f MB  "
							+ "caffeine=%.1f MB / %.0f MB entries=%d evict=%d hitRate=%.2f  threads=%d%n",
					phase.get(),
					heapUsed / (1024.0 * 1024.0),
					heapMax / (1024.0 * 1024.0),
					heapPct,
					heapCommitted / (1024.0 * 1024.0),
					nonHeap.getUsed() / (1024.0 * 1024.0),
					caffeineBytes / (1024.0 * 1024.0),
					cacheBudgetBytes / (1024.0 * 1024.0),
					caffeineEntries,
					evictions,
					hitRate,
					threads.getThreadCount());
		} catch (Throwable t) {
			System.err.println("[mem] sample failed: " + t.getMessage());
		}
	}
}
