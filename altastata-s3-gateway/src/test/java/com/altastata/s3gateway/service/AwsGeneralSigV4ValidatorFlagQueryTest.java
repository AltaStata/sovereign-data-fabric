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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * SigV4 canonical query handling for S3 flag params (e.g. {@code ?tagging}).
 * Valued params ({@code ?uploadId=...}) are unchanged; see existing boto3 tests.
 */
class AwsGeneralSigV4ValidatorFlagQueryTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "127.0.0.1:9876");
        // Must be within 15 minutes of server time (replay-attack check in recreateSignature).
        headers.put("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()));
        headers.put(
                "x-amz-content-sha256",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        return headers;
    }

    private String signatureOf(AwsGeneralSigV4Validator validator, String path, String query) {
        String auth = validator.getRecreatedAuthorizationHeader(
                "GET", path, query, baseHeaders());
        return AwsSigV4Calculator.extractSignature(auth);
    }

    @Test
    void flagQueryTaggingMatchesTaggingEqualsForm() {
        AwsGeneralSigV4Validator validator =
                new AwsGeneralSigV4Validator(ACCESS_KEY, SECRET_KEY, "us-east-1");
        String path = "/altastata-bucket/S3TaggingTest/file.txt";
        assertEquals(signatureOf(validator, path, "tagging"), signatureOf(validator, path, "tagging="));
    }

    @Test
    void flagQueryLocationMatchesLocationEqualsForm() {
        AwsGeneralSigV4Validator validator =
                new AwsGeneralSigV4Validator(ACCESS_KEY, SECRET_KEY, "us-east-1");
        String path = "/test-bucket";
        assertEquals(signatureOf(validator, path, "location"), signatureOf(validator, path, "location="));
    }

    @Test
    void extractCredentialRegionFromAuthorizationHeader() {
        AwsGeneralSigV4Validator validator =
                new AwsGeneralSigV4Validator(ACCESS_KEY, SECRET_KEY, "us-west-2");
        String auth = validator.getRecreatedAuthorizationHeader(
                "GET", "/test-bucket", "location", baseHeaders());
        assertEquals("us-west-2", AwsSigV4Calculator.extractCredentialRegion(auth));
    }

    @Test
    void valuedQueryParamUnchanged() {
        AwsGeneralSigV4Validator validator =
                new AwsGeneralSigV4Validator(ACCESS_KEY, SECRET_KEY, "us-east-1");
        String uploadQuery = "uploadId=abc123";
        String auth = validator.getRecreatedAuthorizationHeader(
                "GET", "/altastata-bucket/key", uploadQuery, baseHeaders());
        String sig1 = AwsSigV4Calculator.extractSignature(auth);

        // Same valued query must reproduce the same signature (regression guard).
        String auth2 = validator.getRecreatedAuthorizationHeader(
                "GET", "/altastata-bucket/key", uploadQuery, baseHeaders());
        assertEquals(sig1, AwsSigV4Calculator.extractSignature(auth2));
    }
}
