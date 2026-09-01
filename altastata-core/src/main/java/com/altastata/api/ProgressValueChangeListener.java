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
 * Listener interface for receiving progress value change events during file operations.
 * 
 * @author AltaStata
 */
public interface ProgressValueChangeListener extends EventListener {
	
	/**
	 * Invoked when the progress value of an operation changes.
	 *
	 * @param evt the event containing progress details
	 */
	void progressValueChange(ProgressValueChangeEvent evt);
}
