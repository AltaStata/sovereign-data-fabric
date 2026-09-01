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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared timing helpers for performance harnesses: cool-down between ops and
 * outlier trimming (drop extremes symmetrically when n ≥ 3).
 */
public final class PerformanceMetrics {

	/** Default pause after each timed op so the machine/network settle. Override with {@code PERF_COOLDOWN_MS}. */
	public static final long DEFAULT_COOLDOWN_MS = 750L;

	private PerformanceMetrics() {
	}

	public static long cooldownMs() {
		String env = System.getenv("PERF_COOLDOWN_MS");
		if (env == null || env.isEmpty()) {
			return DEFAULT_COOLDOWN_MS;
		}
		try {
			return Math.max(0L, Long.parseLong(env.trim()));
		} catch (NumberFormatException e) {
			return DEFAULT_COOLDOWN_MS;
		}
	}

	/** Sleep between runs (interrupted → restore interrupt flag). */
	public static void coolDown() {
		long ms = cooldownMs();
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Backoff after a failed timed op. On heap pressure, request GC and wait longer so
	 * the next attempt is less likely to hit {@code OutOfMemoryError} again.
	 */
	public static void recoverAfterFailure(Throwable t) {
		boolean oom = false;
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof OutOfMemoryError) {
				oom = true;
				break;
			}
			String msg = c.getMessage();
			if (msg != null && msg.contains("OutOfMemoryError")) {
				oom = true;
				break;
			}
		}
		long waitMs = oom ? 5000L : 2000L;
		if (oom) {
			System.err.println("  Heap pressure detected — requesting GC before retry");
			System.gc();
		}
		try {
			Thread.sleep(waitMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Mean after {@link #trimOutliers}; empty → {@code NaN}.
	 */
	public static double trimmedMean(List<? extends Number> values) {
		List<Double> kept = trimOutliers(values);
		if (kept.isEmpty()) {
			return Double.NaN;
		}
		double sum = 0;
		for (double v : kept) {
			sum += v;
		}
		return sum / kept.size();
	}

	/**
	 * Sample standard deviation over the trimmed set (n−1). Empty / single → {@code NaN} / 0.
	 */
	public static double trimmedStdDev(List<? extends Number> values) {
		List<Double> kept = trimOutliers(values);
		if (kept.size() < 2) {
			return kept.isEmpty() ? Double.NaN : 0.0;
		}
		double mean = 0;
		for (double v : kept) {
			mean += v;
		}
		mean /= kept.size();
		double sumSq = 0;
		for (double v : kept) {
			double d = v - mean;
			sumSq += d * d;
		}
		return Math.sqrt(sumSq / (kept.size() - 1));
	}

	/**
	 * Drop extremes symmetrically so cold-start / cache spikes do not dominate.
	 * <ul>
	 *   <li>n &lt; 3 — keep all (cannot trim)</li>
	 *   <li>3–5 — drop 1 min + 1 max</li>
	 *   <li>n ≥ 6 — drop 2 min + 2 max</li>
	 * </ul>
	 */
	public static List<Double> trimOutliers(List<? extends Number> values) {
		if (values == null || values.isEmpty()) {
			return Collections.emptyList();
		}
		List<Double> sorted = new ArrayList<>(values.size());
		for (Number n : values) {
			sorted.add(n.doubleValue());
		}
		Collections.sort(sorted);
		int n = sorted.size();
		if (n < 3) {
			return sorted;
		}
		int dropEach = n >= 6 ? 2 : 1;
		return new ArrayList<>(sorted.subList(dropEach, n - dropEach));
	}

	public static double plainMean(List<? extends Number> values) {
		if (values == null || values.isEmpty()) {
			return Double.NaN;
		}
		double sum = 0;
		for (Number n : values) {
			sum += n.doubleValue();
		}
		return sum / values.size();
	}

	/**
	 * Print raw + trimmed duration/throughput for one side; comparison ratios use trimmed means.
	 *
	 * @param nativeLabel e.g. {@code DIRECT GCS}
	 * @param ratioDenomLabel e.g. {@code GCS} for {@code AltaStata/GCS}
	 */
	public static void printSideBySide(
			String operation,
			String fileName,
			String nativeLabel,
			String ratioDenomLabel,
			List<Long> nativeDurations,
			List<Double> nativeThroughputs,
			List<Long> altaDurations,
			List<Double> altaThroughputs) {
		System.out.println("\n  " + operation + " COMPARISON for " + fileName + ":");
		System.out.println("  =================================================");
		printOneSide(nativeLabel, nativeDurations, nativeThroughputs);
		printOneSide("ALTASTATA", altaDurations, altaThroughputs);
		if (!nativeDurations.isEmpty() && !altaDurations.isEmpty()) {
			double nDur = trimmedMean(nativeDurations);
			double aDur = trimmedMean(altaDurations);
			double nTp = trimmedMean(nativeThroughputs);
			double aTp = trimmedMean(altaThroughputs);
			double durationRatio = aDur / nDur;
			double throughputRatio = aTp / nTp;
			System.out.println("  COMPARISON (trimmed):");
			System.out.format("    Duration Ratio (AltaStata/%s): %.2fx\n", ratioDenomLabel, durationRatio);
			System.out.format("    Throughput Ratio (AltaStata/%s): %.2fx\n", ratioDenomLabel, throughputRatio);
			if (durationRatio > 1.0) {
				System.out.format("    AltaStata is %.1f%% SLOWER\n", (durationRatio - 1.0) * 100);
			} else {
				System.out.format("    AltaStata is %.1f%% FASTER\n", (1.0 - durationRatio) * 100);
			}
			if (throughputRatio > 1.0) {
				System.out.format("    AltaStata has %.1f%% HIGHER throughput\n", (throughputRatio - 1.0) * 100);
			} else {
				System.out.format("    AltaStata has %.1f%% LOWER throughput\n", (1.0 - throughputRatio) * 100);
			}
		}
		System.out.println("  =================================================");
	}

	private static void printOneSide(String label, List<Long> durations, List<Double> throughputs) {
		if (durations == null || durations.isEmpty()) {
			System.out.println("  " + label + ": No successful runs");
			return;
		}
		double rawDur = plainMean(durations);
		double trimDur = trimmedMean(durations);
		double trimDurSd = trimmedStdDev(durations);
		double rawTp = plainMean(throughputs);
		double trimTp = trimmedMean(throughputs);
		double trimTpSd = trimmedStdDev(throughputs);
		int kept = trimOutliers(durations).size();
		System.out.println("  " + label + ":");
		System.out.format("    Runs: %d successful (trim keeps %d)\n", durations.size(), kept);
		System.out.format("    Raw avg Duration: %.2f ms\n", rawDur);
		System.out.format("    TRIMMED Duration: %.2f ms (±%.2f ms)  [drop extremes]\n", trimDur, trimDurSd);
		System.out.format("    Raw avg Throughput: %.2f MB/s\n", rawTp);
		System.out.format("    TRIMMED Throughput: %.2f MB/s (±%.2f MB/s)\n", trimTp, trimTpSd);
	}
}
