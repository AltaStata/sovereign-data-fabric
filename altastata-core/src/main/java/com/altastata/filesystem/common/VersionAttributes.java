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

import com.altastata.api.AltaStataFileSystem;
import com.altastata.utils.Account;

import java.io.Serializable;

/**
 * Holds cryptographic attributes, tags, and timestamps for a specific version of a {@link CloudFile}.
 * 
 * Since AltaStata implements an append-only, version-controlled file system, each modification 
 * creates a new version tracked by an instance of this class. The version matches a unique creator tag
 * (historically the creator's username) and creation time.
 */
public class VersionAttributes implements Comparable<VersionAttributes>, Serializable {

	private static final long serialVersionUID = 1L;
	
	public static Account account = null;
	
	CloudFile cloudFile;
	
	// many users can call their files with the same name, 
	// that is why we created tag parameter and assign the creator user name to it
	private String tag = null;

	private long createTime = System.currentTimeMillis();
		
	/**
	 * Constructs a new VersionAttributes instance for a specific file version.
	 *
	 * @param cloudFile the cloud file this version belongs to
	 * @param createTime the epoch millisecond creation timestamp of this version
	 * @param account the user account context
	 */
	public VersionAttributes(CloudFile cloudFile, long createTime, Account account) {
		this.cloudFile = cloudFile;
		this.createTime = createTime;
		this.account = account;
		this.tag = account.MY_USER();
	}
		
	/**
	 * Gets the associated CloudFile.
	 *
	 * @return the cloud file object
	 */
	public CloudFile getCloudFile() {
		return cloudFile;
	}
	
	/**
	 * Sets the associated CloudFile.
	 *
	 * @param cloudFile the cloud file to set
	 */
	public void setCloudFile(CloudFile cloudFile) {
		this.cloudFile = cloudFile;
	}

	/**
	 * Gets the version tag (typically creator username).
	 *
	 * @return the tag string
	 */
	public String getTag() {
		return tag;
	}

	/**
	 * Sets the version tag.
	 *
	 * @param tag the tag to set
	 */
	public void setTag(String tag) {
		this.tag = tag;
	}
	
	/**
	 * Retrieves an metadata attribute value for this specific version from the file system model.
	 *
	 * @param name the name of the attribute
	 * @return the attribute value string
	 */
	public String getVersionDataAttribute(String name) {
		return account.fileSystemModel().getDataAttributeForCloudFile(cloudFile, getCreateTime(), name);
	}
		
	/**
	 * Gets the creation time of this version.
	 *
	 * @return the creation timestamp
	 */
	public long getCreateTime() {
		return createTime;
	}
	
	/**
	 * Returns a string representation of the version attributes, combining path, tag, and timestamp.
	 *
	 * @return the serialized version string
	 */
	@Override
	public String toString() {
		return getCloudFile().getPath() + AltaStataFileSystem.FILE_MARK_SIGN + getTag() + "_" + getCreateTime();
	}
	
	/**
	 * Compares this version attributes to another based on their creation timestamps.
	 *
	 * @param other the other version attributes to compare with
	 * @return positive if this is newer, negative if older, zero if equal
	 */
	@Override
	public int compareTo(VersionAttributes other) {
		if (this.getCreateTime() > other.getCreateTime())
			return 1;
		else if (this.getCreateTime() < other.getCreateTime())
			return -1;
		else
			return 0;
	}
	
	/**
	 * Checks if this version is equal to another object based on their string representation.
	 *
	 * @param other the other object to compare with
	 * @return true if equal; false otherwise
	 */
	@Override
	public boolean equals(Object other) {
		return this.toString().equals(other.toString());
	}

}
