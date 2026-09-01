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

package com.altastata.s3gateway.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a virtual object tagging operation (GET or PUT ?tagging).
 */
public class ObjectTaggingResult {

    public enum Status {
        SUCCESS,
        NO_SUCH_KEY,
        ACCESS_DENIED,
        INVALID_TAG,
        MALFORMED_XML,
        INTERNAL_ERROR
    }

    private final Status status;
    private final String message;
    private final Map<String, String> tags;

    /**
     * Private constructor to instantiate ObjectTaggingResult.
     *
     * @param status result status type
     * @param message failure description detail or null
     * @param tags associated tags map
     */
    private ObjectTaggingResult(Status status, String message, Map<String, String> tags) {
        this.status = status;
        this.message = message;
        this.tags = tags != null ? Collections.unmodifiableMap(new LinkedHashMap<>(tags)) : Collections.emptyMap();
    }

    /**
     * Mints a successful result containing the resolved tags.
     *
     * @param tags associated tags map
     * @return successful ObjectTaggingResult
     */
    public static ObjectTaggingResult success(Map<String, String> tags) {
        return new ObjectTaggingResult(Status.SUCCESS, null, tags);
    }

    /**
     * Mints a success result with no tags.
     *
     * @return successful ObjectTaggingResult with no tags
     */
    public static ObjectTaggingResult success() {
        return new ObjectTaggingResult(Status.SUCCESS, null, null);
    }

    /**
     * Mints a NO_SUCH_KEY failure result.
     *
     * @return NO_SUCH_KEY ObjectTaggingResult
     */
    public static ObjectTaggingResult noSuchKey() {
        return new ObjectTaggingResult(Status.NO_SUCH_KEY, null, null);
    }

    /**
     * Mints an ACCESS_DENIED security failure result.
     *
     * @return ACCESS_DENIED ObjectTaggingResult
     */
    public static ObjectTaggingResult accessDenied() {
        return new ObjectTaggingResult(Status.ACCESS_DENIED, null, null);
    }

    /**
     * Mints an INVALID_TAG validation failure result.
     *
     * @param message validation failure message
     * @return INVALID_TAG ObjectTaggingResult
     */
    public static ObjectTaggingResult invalidTag(String message) {
        return new ObjectTaggingResult(Status.INVALID_TAG, message, null);
    }

    /**
     * Mints a MALFORMED_XML format error result.
     *
     * @return MALFORMED_XML ObjectTaggingResult
     */
    public static ObjectTaggingResult malformedXml() {
        return new ObjectTaggingResult(Status.MALFORMED_XML, null, null);
    }

    /**
     * Mints an INTERNAL_ERROR system exception result.
     *
     * @param message exception message detail
     * @return INTERNAL_ERROR ObjectTaggingResult
     */
    public static ObjectTaggingResult internalError(String message) {
        return new ObjectTaggingResult(Status.INTERNAL_ERROR, message, null);
    }

    /**
     * Gets the operation result status.
     *
     * @return status enum
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Gets the operation result error message.
     *
     * @return error message or null
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the resolved tags map.
     *
     * @return tags map
     */
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * Checks if the operation succeeded.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
