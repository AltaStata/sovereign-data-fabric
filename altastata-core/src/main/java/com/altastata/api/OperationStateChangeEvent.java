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

import com.altastata.api.AltaStataFileSystem.OperationState;

/**
 * Event representing a state transition in a cloud file operation (e.g., UPLOADING to DONE).
 * 
 * @author AltaStata
 */
public class OperationStateChangeEvent {

	String cloudFileVersionPath;
	OperationState operationState;
	
	/**
	 * Constructs a new OperationStateChangeEvent.
	 *
	 * @param cloudFileVersionPath the version-specific path of the cloud file
	 * @param operationState the new state of the operation
	 */
	public OperationStateChangeEvent(String cloudFileVersionPath, OperationState operationState) {
		this.cloudFileVersionPath = cloudFileVersionPath;
		this.operationState = operationState;
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
	 * Gets the new operation state.
	 *
	 * @return the operation state
	 */
	public OperationState getOperationState() {
		return operationState;
	}
}
