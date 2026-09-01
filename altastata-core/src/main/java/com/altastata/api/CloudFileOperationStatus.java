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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the status and progress of an asynchronous cloud file operation.
 * 
 * Tracks the type of operation, its current {@link OperationState}, progress value (0 to 1),
 * details, streaming status, and any encountered errors. It provides event listener registries
 * for state changes, progress updates, and streaming-started events to facilitate integration with
 * graphical user interfaces and progress bars.
 * 
 * @author AltaStata
 */
public class CloudFileOperationStatus {

	private String operationType;
	private String cloudFileVersionPath;
	private OperationState operationState;
	private double progressValue;
	private boolean isOperationCanceling = false;

	private boolean streamStarted = false; // the first chunk was retrieved or uploaded
	private Throwable error = null;
	private String details;
	
	private List<OperationStateChangeListener> operationStateChangeListeners = new ArrayList<OperationStateChangeListener>();
	private List<ProgressValueChangeListener> progressValueChangeListeners = new ArrayList<ProgressValueChangeListener>();
	private List<StreamStartedListener> streamStartedListeners = new ArrayList<StreamStartedListener>();

	/**
	 * Constructs a new CloudFileOperationStatus with a file path and starting operation state.
	 *
	 * @param cloudFileVersionPath the version-specific path of the cloud file
	 * @param operationState the initial state of the operation
	 */
	public CloudFileOperationStatus(String cloudFileVersionPath, OperationState operationState) {
		this.operationType = operationState.name();
		this.cloudFileVersionPath = cloudFileVersionPath;
		this.operationState = operationState;
	}
	
	/**
	 * Gets the operation type name.
	 *
	 * @return the operation type string
	 */
	public String getOperationType() {
		return operationType;
	}

	/**
	 * Gets the current operation state.
	 *
	 * @return the operation state enum value
	 */
	public OperationState getOperationState() {
		return operationState;
	}

	/**
	 * Gets the name of the current operation state.
	 *
	 * @return the operation state name
	 */
	public String getOperationStateValue() {
		return operationState.name();
	}
	
	/**
	 * Gets the current progress value (between 0.0 and 1.0).
	 *
	 * @return the progress value
	 */
	public double getProgressValue() {
		return progressValue;
	}

	/**
	 * Checks if the underlying stream has started (e.g. first chunk processed).
	 *
	 * @return true if stream started; false otherwise
	 */
	public boolean isStreamStarted() {
		return streamStarted;
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
	 * Gets the clean cloud file path without version markers.
	 *
	 * @return the clean cloud file path
	 */
	public String getCloudFilePath() {
		return cloudFileVersionPath.substring(0, cloudFileVersionPath.indexOf(AltaStataFileSystem.FILE_MARK_SIGN));
	}

	/**
	 * Gets the unique file version tag.
	 *
	 * @return the version tag string
	 */
	public String getCloudFileTag() {
		String suffix = cloudFileVersionPath.substring(cloudFileVersionPath.indexOf(AltaStataFileSystem.FILE_MARK_SIGN));
		
		return suffix.substring(1, suffix.indexOf("_"));
	}

	/**
	 * Gets the creation timestamp string of this file version.
	 *
	 * @return the creation timestamp string
	 */
	public String getCloudFileCreateTime() {
		String suffix = cloudFileVersionPath.substring(cloudFileVersionPath.indexOf(AltaStataFileSystem.FILE_MARK_SIGN));
		
		return suffix.substring(suffix.indexOf("_") + 1);
	}
	
	/**
	 * Gets the error traceback if the operation failed.
	 *
	 * @return the exception, or null if successful
	 */
	public Throwable getErrorTrace() {
		return error;
	}
	
	/**
	 * Gets the error message if the operation failed.
	 *
	 * @return the error message, or null if successful
	 */
	public String getError() {
		return error != null ? error.getMessage() : null;
	}
	
	/**
	 * Gets additional operation details or logs.
	 *
	 * @return the details string
	 */
	public String getDetails() {
		return details;
	}

	/**
	 * Sets additional details or status logs for the operation.
	 *
	 * @param details the details to associate with the operation status
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus setDetails(String details) {
		this.details = details;
		
		return this;
	}
	
	/**
	 * Sets the operation state and notifies all registered state change listeners.
	 *
	 * @param operationState the new state of the operation
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus setOperationState(OperationState operationState) {
		this.operationState = operationState;
		
		for (OperationStateChangeListener operationStateChangeListener : operationStateChangeListeners) {
			operationStateChangeListener.operationStateChange(new OperationStateChangeEvent(cloudFileVersionPath, operationState));
		}
		
		return this;
	}

	/**
	 * Marks the stream as started and notifies all registered stream started listeners.
	 */
	public void setStreamStarted() {
		this.streamStarted = true;

		for (StreamStartedListener streamStartedListener : streamStartedListeners) {
			streamStartedListener.streamStarted(new StreamStartedEvent(cloudFileVersionPath));
		}
	}

	/**
	 * Sets the current progress value and notifies all registered progress value listeners.
	 *
	 * @param progressValue the progress value (typically between 0.0 and 1.0)
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus setProgressValue(double progressValue) {
		this.progressValue = progressValue;
		
		for (ProgressValueChangeListener progressValueChangeListener : progressValueChangeListeners) {
			progressValueChangeListener.progressValueChange(new ProgressValueChangeEvent(cloudFileVersionPath, progressValue));
		}
		
		return this;
	}

	/**
	 * Sets an error cause, transition the state to ERROR, and returns this status.
	 *
	 * @param error the exception that caused the operation to fail
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus setError(Throwable error) {
		this.operationState = OperationState.ERROR;
		this.error = error;

		return this;
	}

	/**
	 * Registers a listener for progress value change events.
	 *
	 * @param progressValueChangeListener the listener to register
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus addProgressValueListener(ProgressValueChangeListener progressValueChangeListener) {
		progressValueChangeListeners.add(progressValueChangeListener);
		
		return this;
	}

	/**
	 * Unregisters a listener from progress value change events.
	 *
	 * @param progressValueChangeListener the listener to unregister
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus removeProgressValueListener(ProgressValueChangeListener progressValueChangeListener) {
		progressValueChangeListeners.remove(progressValueChangeListener);
		
		return this;
	}
	
	/**
	 * Registers a listener for operation state change events.
	 *
	 * @param operationStateChangeListener the listener to register
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus addOperationStateValueChangeListener(OperationStateChangeListener operationStateChangeListener) {
		operationStateChangeListeners.add(operationStateChangeListener);
		
		return this;
	}

	/**
	 * Unregisters a listener from operation state change events.
	 *
	 * @param operationStateChangeListener the listener to unregister
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus removeOperationStateValueChangeListener(OperationStateChangeListener operationStateChangeListener) {
		operationStateChangeListeners.remove(operationStateChangeListener);
		
		return this;
	}

	/**
	 * Registers a listener for stream-started events.
	 *
	 * @param streamStartedListener the listener to register
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus addStreamStartedListener(StreamStartedListener streamStartedListener) {
		streamStartedListeners.add(streamStartedListener);

		return this;
	}

	/**
	 * Unregisters a listener from stream-started events.
	 *
	 * @param streamStartedListener the listener to unregister
	 * @return this instance for builder-style chaining
	 */
	public CloudFileOperationStatus removeStreamStartedListener(StreamStartedListener streamStartedListener) {
		streamStartedListeners.remove(streamStartedListener);

		return this;
	}

	/**
	 * Command to cancel the operation that is in progress or
	 */
	public void doCancelOperation(boolean doCancel) {
		this.isOperationCanceling = doCancel;
	}

	/**
	 * Checks if the operation is in the process of being canceled.
	 *
	 * @return true if cancel requested; false otherwise
	 */
	public boolean checkIfOperationCanceling() {
		return isOperationCanceling;
	}

	/**
	 * Returns a string representation of the operation status.
	 *
	 * @return a string describing the current status details
	 */
	@Override
	public String toString() {
		return "operationType: " + operationType + 
			" cloudFileVersionPath: " + cloudFileVersionPath +
			" operationState: " + operationState.name() + 
			" progressValue: " + progressValue +
			" error: " + ((error != null)?error.getMessage():"") +
			" details: " + ((details != null)?details:"");
	}

}
