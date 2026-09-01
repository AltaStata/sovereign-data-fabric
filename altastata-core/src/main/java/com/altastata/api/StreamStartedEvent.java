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
 * Event notifying that the first data chunk of a streaming file has been successfully
 * processed (uploaded or downloaded), signaling the start of a stream.
 * 
 * @author AltaStata
 */
public class StreamStartedEvent {

	String cloudFileVersionPath;

	/**
	 * Constructs a new StreamStartedEvent.
	 *
	 * @param cloudFileVersionPath the version-specific path of the cloud file
	 */
	public StreamStartedEvent(String cloudFileVersionPath) {
		this.cloudFileVersionPath = cloudFileVersionPath;
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
	 * Returns a string representation of this stream started event.
	 *
	 * @return a status string
	 */
	@Override
	public String toString() {
		return " cloudFilePath: " + cloudFileVersionPath;
	}
}

