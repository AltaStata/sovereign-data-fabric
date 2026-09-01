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

package com.altastata.performance.gcp;

import com.altastata.performance.PerformanceProfile;

/**
 * GCP-facing wrapper around {@link PerformanceProfile} (kept for existing imports).
 */
public final class GcpPerformanceProfile {

	private final PerformanceProfile delegate;

	public final PerformanceProfile.Mode mode;
	public final int warmupRuns;
	public final int smallFileRuns;
	public final int mediumFileRuns;
	public final int largeFileRuns;
	public final int xlargeFileRuns;
	public final boolean includeLargeFiles;
	public final boolean includeXLargeFiles;

	private GcpPerformanceProfile(PerformanceProfile p) {
		this.delegate = p;
		this.mode = p.mode;
		this.warmupRuns = p.warmupRuns;
		this.smallFileRuns = p.smallFileRuns;
		this.mediumFileRuns = p.mediumFileRuns;
		this.largeFileRuns = p.largeFileRuns;
		this.xlargeFileRuns = p.xlargeFileRuns;
		this.includeLargeFiles = p.includeLargeFiles;
		this.includeXLargeFiles = p.includeXLargeFiles;
	}

	public static GcpPerformanceProfile fromArgs(String[] args) {
		return new GcpPerformanceProfile(PerformanceProfile.fromArgs(args));
	}

	public static GcpPerformanceProfile smoke() {
		return new GcpPerformanceProfile(PerformanceProfile.smoke());
	}

	public static GcpPerformanceProfile full() {
		return new GcpPerformanceProfile(PerformanceProfile.full());
	}

	public void printBanner() {
		delegate.printBanner();
	}
}
