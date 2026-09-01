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

import java.util.EventListener;

/**
 * Listener interface for monitoring changes in the {@link OperationState} of a cloud operation.
 * 
 * @author AltaStata
 */
public interface OperationStateChangeListener extends EventListener {
	
	/**
	 * Invoked when the state of an operation transitions.
	 *
	 * @param evt the event containing state details
	 */
	void operationStateChange(OperationStateChangeEvent evt);
}
