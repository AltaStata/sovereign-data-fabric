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

/**
 * Shared run profile for cloud performance benchmarks (GCP / Azure).
 *
 * <ul>
 *   <li>{@code smoke} — 1MB / 10MB / 100MB only</li>
 *   <li>{@code full} — includes 1GB and 5GB</li>
 *   <li>{@code large} — 1GB and 5GB only (same run counts as full)</li>
 * </ul>
 *
 * Select via first CLI arg or {@code PERF_PROFILE=smoke|full|large}.
 */
public final class PerformanceProfile {

	public enum Mode {
		SMOKE,
		FULL,
		LARGE
	}

	public final Mode mode;
	public final int warmupRuns;
	public final int smallFileRuns;
	public final int mediumFileRuns;
	public final int largeFileRuns;
	public final int xlargeFileRuns;
	public final boolean includeLargeFiles;
	public final boolean includeXLargeFiles;

	private PerformanceProfile(
			Mode mode,
			int warmupRuns,
			int smallFileRuns,
			int mediumFileRuns,
			int largeFileRuns,
			int xlargeFileRuns,
			boolean includeLargeFiles,
			boolean includeXLargeFiles) {
		this.mode = mode;
		this.warmupRuns = warmupRuns;
		this.smallFileRuns = smallFileRuns;
		this.mediumFileRuns = mediumFileRuns;
		this.largeFileRuns = largeFileRuns;
		this.xlargeFileRuns = xlargeFileRuns;
		this.includeLargeFiles = includeLargeFiles;
		this.includeXLargeFiles = includeXLargeFiles;
	}

	/**
	 * Resolve profile. Explicit CLI arg ({@code smoke|full|large}) wins over a stale
	 * {@code PERF_PROFILE} env left by a previous script in the same shell.
	 */
	public static PerformanceProfile fromArgs(String[] args) {
		String profile = null;
		if (args != null) {
			for (String raw : args) {
				if (raw == null) {
					continue;
				}
				for (String tok : raw.trim().split("\\s+")) {
					if ("smoke".equalsIgnoreCase(tok)
							|| "full".equalsIgnoreCase(tok)
							|| "large".equalsIgnoreCase(tok)) {
						profile = tok;
						break;
					}
				}
				if (profile != null) {
					break;
				}
			}
		}
		if (profile == null || profile.isEmpty()) {
			profile = System.getenv("PERF_PROFILE");
		}
		if ("smoke".equalsIgnoreCase(profile)) {
			return smoke();
		}
		if ("large".equalsIgnoreCase(profile)) {
			return largeOnly();
		}
		return full();
	}

	public static PerformanceProfile smoke() {
		// More small-file runs so trim(min/max) is meaningful on latency-noisy downloads.
		return new PerformanceProfile(Mode.SMOKE, 1, 12, 8, 0, 0, false, false);
	}

	public static PerformanceProfile full() {
		return new PerformanceProfile(Mode.FULL, 2, 20, 12,
				envInt("PERF_LARGE_FILE_RUNS", 5),
				envInt("PERF_XLARGE_FILE_RUNS", 3), true, true);
	}

	/** 1GB / 5GB only — use when smaller sizes were already measured. */
	public static PerformanceProfile largeOnly() {
		return new PerformanceProfile(Mode.LARGE, 0, 0, 0,
				envInt("PERF_LARGE_FILE_RUNS", 5),
				envInt("PERF_XLARGE_FILE_RUNS", 3), true, true);
	}

	/** Override run counts without changing the published large/full defaults. */
	private static int envInt(String name, int fallback) {
		String raw = System.getenv(name);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Math.max(1, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public void printBanner() {
		System.out.println("Profile: " + mode.name().toLowerCase());
		System.out.println("Runs — warm-up: " + warmupRuns
				+ ", small (1–10MB): " + smallFileRuns
				+ ", medium (100MB): " + mediumFileRuns
				+ ", large (1GB): " + (includeLargeFiles ? largeFileRuns : "skip")
				+ ", xlarge (5GB): " + (includeXLargeFiles ? xlargeFileRuns : "skip"));
		System.out.println("Stats: trimmed mean (drop min+max when n≥3); cool-down "
				+ PerformanceMetrics.cooldownMs() + " ms between ops (PERF_COOLDOWN_MS)");
	}
}
