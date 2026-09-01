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

package com.altastata.filesystem.common;

import com.altastata.api.*;
import com.altastata.api.AltaStataFileSystem.OperationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Represents a secure file or directory mapped to the AltaStata cloud storage.
 * 
 * CloudFile tracks metadata, lifecycle versions (for file history and conflict resolution),
 * and dynamic UI state (upload/download progress, operational status). Since AltaStata
 * implements an append-only, versioned file system, a single CloudFile object can hold 
 * multiple {@link VersionAttributes}, resolving to the correct one based on snapshot time.
 */
public class CloudFile implements Comparable<CloudFile>, Serializable {
    static public DateFormat DATEFORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

	private static final long serialVersionUID = 1L;

	private static Logger LOGGER = LoggerFactory.getLogger(CloudFile.class);

	private String path;

	private boolean isDirectory = false;
	
	private ConcurrentSkipListSet<VersionAttributes> versions = new ConcurrentSkipListSet<VersionAttributes>();
	
	// Based on http://stackoverflow.com/questions/22105162/is-it-possible-to-bind-a-stringproperty-to-a-pojos-string-in-javafx
	transient private volatile OperationState operationState = OperationState.NONE;
	transient private volatile double progressValue = 0;

	transient private List<OperationStateChangeListener> operationStateChangeListeners = new ArrayList<OperationStateChangeListener>();
	transient private List<ProgressValueChangeListener> progressValueChangeListeners = new ArrayList<ProgressValueChangeListener>();

	transient private boolean mobileSelectStatus = false;
	transient private final PropertyChangeSupport pcsMobileSelectStatus = new PropertyChangeSupport(this);
	transient private PropertyChangeListener mobileSelectStatusValueChangeListener = null;
		
	// only for kryo serialization
	/**
	 * Default constructor initialized with the default root directory path.
	 * Intended mainly for serialization libraries (e.g. Kryo).
	 */
	public CloudFile() {
		this.path = FileSystemHandler.INIT_DIR;
	}
	
	/**
	 * Constructs a new CloudFile with the specified path and directory flag.
	 *
	 * @param pathname the path representing the file or directory
	 * @param isDirectory true if this represents a directory; false if a file
	 */
	public CloudFile(String pathname, boolean isDirectory) {
		this.path = pathname;
		this.isDirectory = isDirectory;
	}
	
	/**
	 * Checks if this object represents a directory.
	 *
	 * @return true if it is a directory; false otherwise
	 */
	public boolean isDirectory() {
		return isDirectory;
	}

	/**
	 * Resolves the parent directory path of this file.
	 *
	 * @return the parent directory path string
	 */
	public String getParent() {
		if (path == null || path.isEmpty()) {
			return FileSystemHandler.INIT_DIR;
		}

		int lastSlashIndex = path.lastIndexOf('/');
		if (lastSlashIndex == -1) {
			return FileSystemHandler.INIT_DIR;
		}

		return path.substring(0, lastSlashIndex);
	}

	/**
	 * Resolves the base name of this file (excluding directory path).
	 *
	 * @return the file or directory name
	 */
	public String getName() {
		if (path == null || path.isEmpty()) {
			return FileSystemHandler.INIT_DIR; // Return empty string for null or empty input
		}

		int lastSeparatorIndex = path.lastIndexOf('/');
		if (lastSeparatorIndex == -1) {
			return path; // No separator found, the entire path is the name
		}

		return path.substring(lastSeparatorIndex + 1); // Return everything after the last separator
	}

	/**
	 * Gets the complete path of this file.
	 *
	 * @return the path string
	 */
	public String getPath() {
        return path;
    }
	
	/**
	 * Finds the latest file version that was created on or before the given snapshot timestamp.
	 *
	 * @param timestamp the cutoff epoch millisecond timestamp
	 * @return the best matching version attributes, or null if no match is found
	 */
	public VersionAttributes getBestMatchingVersionAttributes(long timestamp) {
		VersionAttributes result = null;
		for (VersionAttributes versionAttributes : versions) {
			if (versionAttributes.getCreateTime() <= timestamp) {
				result = versionAttributes;
			}
		}
		
		return result;
	}

	/**
	 * Generates the full cloud storage object key for a specific version.
	 *
	 * @param versionAttributes the target version attributes
	 * @return the constructed cloud object path string
	 */
	public String getCloudObjectPathIncludingVersion(VersionAttributes versionAttributes) {
		return path + AltaStataFileSystem.FILE_MARK_SIGN + versionAttributes.getTag() + "_" + versionAttributes.getCreateTime();
	}
	
	/**
	 * Generates the cloud storage object key for the latest version available in this CloudFile's history.
	 *
	 * @return the latest version's cloud object path string
	 */
	public String getLastCloudObjectPathIncludingVersion() {
		return path + AltaStataFileSystem.FILE_MARK_SIGN + versions.last().getTag() + "_" + versions.last().getCreateTime();
	}

	/**
	 * Gets the active operation state for the file (e.g. UPLOADING, DONE).
	 *
	 * @return the current operation state
	 */
	public OperationState getOperationState() {
		return operationState;
	}
	
	/**
	 * Updates the operation state and triggers registered change listeners.
	 *
	 * @param operationStateValue the new operation state value
	 * @return this instance for chain calls
	 */
	public CloudFile setOperationStateValue(OperationState operationStateValue) {
		this.operationState = operationStateValue;

		for (OperationStateChangeListener operationStateChangeListener : operationStateChangeListeners) {
			operationStateChangeListener.operationStateChange(new OperationStateChangeEvent("STUB", operationStateValue));
		}

		return this;
	}

	/**
	 * Registers a listener to monitor operation state changes. Clears any pre-existing listeners first.
	 *
	 * @param operationStateChangeListener the listener to register
	 */
	public void addOperationStateValueChangeListener(OperationStateChangeListener operationStateChangeListener) {
		// remove any existing listener if its process, as it can update more than one UI element
		removeOperationStateValueChangeListener();
		
		operationStateChangeListeners.add(operationStateChangeListener);
    }

	/**
	 * Unregisters all operation state change listeners.
	 */
	public void removeOperationStateValueChangeListener() {
		if (operationStateChangeListeners != null) {
			operationStateChangeListeners.clear();
		}
    }

	/**
	 * Gets the list of currently registered operation state change listeners.
	 *
	 * @return the list of listeners
	 */
	public List<OperationStateChangeListener> getOperationStateChangeListeners() {
		return operationStateChangeListeners;
	}
	
	/**
	 * Gets the current operation progress fraction.
	 *
	 * @return the progress value (between 0.0 and 1.0)
	 */
	public double getProgressValue() {
		return progressValue;
	}

	/**
	 * Sets the progress value and notifies all registered progress change listeners.
	 *
	 * @param progressValue the progress fraction
	 * @return this instance for chain calls
	 */
	public CloudFile setProgressValue(double progressValue) {
		this.progressValue = progressValue;
		
		for (ProgressValueChangeListener progressValueChangeListener : progressValueChangeListeners) {
			progressValueChangeListener.progressValueChange(new ProgressValueChangeEvent("STUB", progressValue));
		}
		
		return this;
	}
		
	/**
	 * Registers a listener to monitor progress changes. Clears any pre-existing listeners first.
	 *
	 * @param progressValueChangeListener the listener to register
	 */
	public void addProgressValueChangeListener(ProgressValueChangeListener progressValueChangeListener) {
		// remove any existing listener if its process, as it can update more than one UI element 
		removeProgressValueChangeListener();
		
		progressValueChangeListeners.add(progressValueChangeListener);
    }

	/**
	 * Unregisters all progress change listeners.
	 */
	public void removeProgressValueChangeListener() {
		if (progressValueChangeListeners != null) {
			progressValueChangeListeners.clear();
		}
    }

	/**
	 * Checks if the file is selected in a mobile UI layout.
	 *
	 * @return the select status
	 */
	public boolean getMobileSelectStatus() {
		return mobileSelectStatus;
	}

	/**
	 * Gets the mobile select status value.
	 *
	 * @return the select status value
	 */
	public boolean getMobileSelectStatusValue() {
		return mobileSelectStatus;
	}
	
	/**
	 * Updates the mobile select status and triggers a property change event on the listener support.
	 *
	 * @param mobileSelectStatusValue the new select status value
	 */
	public void setMobileSelectStatusValue(boolean mobileSelectStatusValue) {
		this.pcsMobileSelectStatus.firePropertyChange("MobileSelectStatus", this.mobileSelectStatus, mobileSelectStatusValue);
		this.mobileSelectStatus = mobileSelectStatusValue;
	}

	/**
	 * Registers a listener to monitor changes in mobile selection status. Clears any pre-existing listeners first.
	 *
	 * @param mobileSelectStatusValueChangeListener the property change listener to register
	 */
	public void addMobileSelectStatusValueChangeListener(PropertyChangeListener mobileSelectStatusValueChangeListener) {
		// remove any existing listener if its process, as it can update more than one UI element 
		removeMobileSelectStatusValueChangeListeners();
		
		pcsMobileSelectStatus.addPropertyChangeListener(mobileSelectStatusValueChangeListener);
		this.mobileSelectStatusValueChangeListener = mobileSelectStatusValueChangeListener;
    }
	
	/**
	 * Unregisters all mobile select status listeners.
	 */
	public void removeMobileSelectStatusValueChangeListeners() {
		if (this.mobileSelectStatusValueChangeListener != null) {
			pcsMobileSelectStatus.removePropertyChangeListener(this.mobileSelectStatusValueChangeListener);
		}
    }
		
	/**
	 * Adds a version to this file's version history.
	 *
	 * @param versionAttributes the version attributes to add
	 */
	public void addVersion(VersionAttributes versionAttributes) {
		versions.add(versionAttributes);
	}

	/**
	 * Removes a version from this file's version history.
	 *
	 * @param versionAttributes the version attributes to remove
	 */
	public void removeVersion(VersionAttributes versionAttributes) {
		versions.remove(versionAttributes);
	}
	
	/**
	 * Finds a version attributes by its exact creation timestamp.
	 *
	 * @param createTime the target epoch millisecond creation timestamp
	 * @return the matching version attributes, or null if not found
	 */
	public VersionAttributes findVersionAttributesByCreateTime(Long createTime) {
		for (VersionAttributes versionAttributes : versions) {
			if (versionAttributes.getCreateTime() == createTime) {
				return versionAttributes;
			}
		}
		
		return null;
	}

	/**
	 * Gets the complete set of version history for this file, ordered chronologically.
	 *
	 * @return the concurrent skip list set of version attributes
	 */
	public ConcurrentSkipListSet<VersionAttributes> getVersions() {
		return versions;
	}
	
	/**
	 * Removes a version from history by its creation timestamp.
	 *
	 * @param createTime the epoch millisecond creation timestamp of the version to remove
	 */
	public void removeVersion(Long createTime) {
		VersionAttributes versionAttributes = findVersionAttributesByCreateTime(createTime);
		if (versionAttributes != null) {
			versions.remove(versionAttributes);
		}
	}

	/**
	 * Returns a string representation of the cloud file, detailing its path and version list.
	 *
	 * @return a status and history string
	 */
	@Override
	public String toString() {
		String attrsString = "";
		for (VersionAttributes attrs : versions) {
			attrsString += " {" + attrs.getTag() + " - " + DATEFORMAT.format(attrs.getCreateTime()) + "}";
		}
		
		return super.toString() + " @ " + getPath() + attrsString;
	}

	/**
	 * Generates a hash code for this cloud file based on its path.
	 *
	 * @return a hash code value
	 */
	@Override
	public int hashCode() {
		int pathHashCode = (path != null) ? path.hashCode() : 0;
		return pathHashCode ^ 1234321; // Exclusive OR with 1234321
	}

	/**
	 * Compares this file to another object for value equality based on its path.
	 *
	 * @param obj the other object to compare with
	 * @return true if equal; false otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if ((obj != null) && (obj instanceof CloudFile)) {
			return compareTo((CloudFile) obj) == 0;
		}
		return false;
	}

	/**
	 * Compares this file to another based on lexicographical order of their paths.
	 *
	 * @param otherCloudFile the other cloud file to compare to
	 * @return comparison integer
	 */
	@Override
	public int compareTo(CloudFile otherCloudFile) {
		return path.compareTo(otherCloudFile.getPath());
	}
}
