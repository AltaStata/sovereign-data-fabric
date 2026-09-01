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

package com.altastata.s3gateway.config;

/**
 * Configuration properties for S3 Gateway
 * Removes hardcoded values and makes them configurable
 */
public class S3GatewayConfig {
    
    /**
     * Default AWS region to return in bucket location responses
     */
    private String defaultRegion = "us-east-1";
    
    /**
     * Owner ID to use in S3 responses
     */
    private String ownerId = "altastata-user";
    
    /**
     * Owner display name to use in S3 responses
     */
    private String ownerDisplayName = "AltaStata User";
    
    /**
     * Default maximum keys to return in list operations
     */
    private int defaultMaxKeys = 1000;
    
    /**
     * Default creation date for buckets (ISO format)
     */
    private String defaultBucketCreationDate = "2023-01-01T00:00:00.000Z";

    // Getters and setters
    
    /**
     * Gets the default region name.
     *
     * @return region name
     */
    public String getDefaultRegion() {
        return defaultRegion;
    }

    /**
     * Sets the default region name.
     *
     * @param defaultRegion region name to set
     */
    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /**
     * Gets the owner identifier.
     *
     * @return owner ID string
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Sets the owner identifier.
     *
     * @param ownerId owner ID string to set
     */
    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * Gets the owner display name.
     *
     * @return owner display name
     */
    public String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    /**
     * Sets the owner display name.
     *
     * @param ownerDisplayName owner display name to set
     */
    public void setOwnerDisplayName(String ownerDisplayName) {
        this.ownerDisplayName = ownerDisplayName;
    }

    /**
     * Gets the default maximum number of keys to return.
     *
     * @return max keys
     */
    public int getDefaultMaxKeys() {
        return defaultMaxKeys;
    }

    /**
     * Sets the default maximum number of keys to return.
     *
     * @param defaultMaxKeys max keys to set
     */
    public void setDefaultMaxKeys(int defaultMaxKeys) {
        this.defaultMaxKeys = defaultMaxKeys;
    }

    /**
     * Gets the default creation date of S3 buckets.
     *
     * @return bucket creation ISO date string
     */
    public String getDefaultBucketCreationDate() {
        return defaultBucketCreationDate;
    }

    /**
     * Sets the default creation date of S3 buckets.
     *
     * @param defaultBucketCreationDate bucket creation ISO date string to set
     */
    public void setDefaultBucketCreationDate(String defaultBucketCreationDate) {
        this.defaultBucketCreationDate = defaultBucketCreationDate;
    }
} 
