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

package com.altastata.api;

/**
 * Event containing progress updates (0 to 1) for an active file operation.
 * 
 * @author AltaStata
 */
public class ProgressValueChangeEvent {

	String cloudFileVersionPath;
	Double progressValue;
	
	/**
	 * Constructs a new ProgressValueChangeEvent.
	 *
	 * @param cloudFileVersionPath the version-specific path of the cloud file
	 * @param progressValue the progress fraction (between 0.0 and 1.0)
	 */
	public ProgressValueChangeEvent(String cloudFileVersionPath, Double progressValue) {
		this.cloudFileVersionPath = cloudFileVersionPath;
		this.progressValue = progressValue;
	}

	/**
	 * Gets the version-specific cloud file path.
	 *
	 * @return the cloud file version path
	 */
	public String getCloudFileVersionPath() {
		return cloudFileVersionPath;
	}

	/**
	 * Gets the current progress value.
	 *
	 * @return the progress fraction
	 */
	public Double getProgressValue() {
		return progressValue;
	}
	
	/**
	 * Returns a string representation of the progress update event.
	 *
	 * @return a status string
	 */
	@Override
	public String toString() {
		return " cloudFilePath: " + cloudFileVersionPath +
				" progressValue: " + progressValue;
	}
}

