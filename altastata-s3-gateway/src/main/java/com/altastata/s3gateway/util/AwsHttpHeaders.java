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

/*
 * AWS HTTP Headers constants
 * Based on S3Mock implementation
 */
package com.altastata.s3gateway.util;

public final class AwsHttpHeaders {
    
    // AWS chunked encoding constants
    public static final String AWS_CHUNKED = "aws-chunked";
    
    // AWS V4 signing constants
    public static final String STREAMING_AWS_4_HMAC_SHA_256_PAYLOAD = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
    public static final String STREAMING_AWS_4_HMAC_SHA_256_PAYLOAD_TRAILER = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
    
    // AWS headers
    public static final String X_AMZ_DECODED_CONTENT_LENGTH = "x-amz-decoded-content-length";
    public static final String X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256";
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private AwsHttpHeaders() {
        // Utility class
    }
} 
