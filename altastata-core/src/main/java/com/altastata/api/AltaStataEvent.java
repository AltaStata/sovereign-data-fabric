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
 * General event class for notifications within the AltaStata platform.
 * 
 * @author AltaStata
 */
public class AltaStataEvent {

	private String eventName;
	private Object data; 
	
	/**
	 * Constructs a new AltaStataEvent.
	 *
	 * @param eventName the name identifying the event type
	 * @param data the payload or contextual data associated with the event
	 */
	public AltaStataEvent(String eventName, Object data) { 
		this.eventName = eventName;
		this.data = data;
	}
	
	/**
	 * Gets the name of the event.
	 *
	 * @return the event name
	 */
	public String getEventName() {
		return eventName;
	}

	/**
	 * Gets the payload data of the event.
	 *
	 * @return the event data
	 */
	public Object getData() {
		return data;
	}
	
	/**
	 * Returns a string representation of the event.
	 *
	 * @return a string describing the event name and data
	 */
	public String toString() {
		return "eventName: " + eventName + " data: " + data;
	}
}
