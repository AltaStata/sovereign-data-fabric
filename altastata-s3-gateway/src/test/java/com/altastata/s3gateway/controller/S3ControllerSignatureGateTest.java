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

package com.altastata.s3gateway.controller;

import com.altastata.api.AltaStataFileSystem;
import com.altastata.grpc.S3CredentialsRegistry;
import com.altastata.s3gateway.service.AwsGeneralSigV4Validator;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ControllerSignatureGateTest {

    private static final String ACCESS_KEY = "AKIATESTKEY";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @AfterEach
    void clearTestSignatureProperty() {
        System.clearProperty("altastata.s3.accept-test-signatures");
    }

    @Test
    void testSignatureRejectedByDefault() throws Exception {
        HttpResponse<String> result = invokeValidation(testSignatureRequest(), "deleteObjects");
        assertNotNull(result, "test-signature must not skip SigV4 when the test gate is off");
        assertTrue(result.body() == null || result.body().contains("SignatureDoesNotMatch")
                || result.getStatus().getCode() >= 400);
    }

    @Test
    void testSignatureAcceptedWhenGateEnabled() throws Exception {
        System.setProperty("altastata.s3.accept-test-signatures", "true");
        HttpResponse<String> result = invokeValidation(testSignatureRequest(), "deleteObjects");
        assertNull(result, "test-signature should be accepted when the test gate is on");
    }

    @Test
    void deleteObjectsAcceptsValidSigV4() throws Exception {
        HttpResponse<String> result = invokeValidation(signedDeleteObjectsRequest(), "deleteObjects");
        assertNull(result, "valid SigV4 must pass for deleteObjects without the old access-key bypass");
    }

    @Test
    void deleteObjectsRejectsWrongSignature() throws Exception {
        MutableHttpRequest<String> valid = signedDeleteObjectsRequest();
        String tamperedAuth = valid.getHeaders().get("Authorization")
                .replaceAll("Signature=[A-Fa-f0-9]+", "Signature=deadbeef");
        MutableHttpRequest<String> request = HttpRequest.POST("/mybucket?delete", "<Delete/>")
                .header("Authorization", tamperedAuth)
                .header("Host", valid.getHeaders().get("Host"))
                .header("x-amz-date", valid.getHeaders().get("x-amz-date"))
                .header("x-amz-content-sha256", valid.getHeaders().get("x-amz-content-sha256"));
        HttpResponse<String> result = invokeValidation(request, "deleteObjects");
        assertNotNull(result, "tampered SigV4 must be rejected");
    }

    private HttpResponse<String> invokeValidation(HttpRequest<?> request, String operation) throws Exception {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        S3CredentialsRegistry credentials = mock(S3CredentialsRegistry.class);
        when(credentials.resolveForS3(ACCESS_KEY)).thenReturn(Optional.of(
                new S3CredentialsRegistry.S3ResolveResult(fs, "bob", SECRET_KEY)));

        S3Controller controller = new S3Controller(Optional.of(credentials));
        Method method = S3Controller.class.getDeclaredMethod(
                "performSignatureValidation", HttpRequest.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) method.invoke(controller, request, operation);
        return response;
    }

    private MutableHttpRequest<String> testSignatureRequest() {
        return HttpRequest.POST("/mybucket?delete", "<Delete/>")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY
                                + "/20240101/us-east-1/s3/aws4_request, "
                                + "SignedHeaders=host, Signature=test-signature")
                .header("Host", "127.0.0.1:9876");
    }

    private MutableHttpRequest<String> signedDeleteObjectsRequest() {
        String amzDate = AMZ_DATE.format(Instant.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "127.0.0.1:9876");
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", EMPTY_SHA256);
        // recreateSignature only signs headers listed in Authorization. Without this
        // placeholder the canonical request has an empty SignedHeaders set and will
        // not match performSignatureValidation (which reads SignedHeaders from auth).
        headers.put("authorization",
                "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY
                        + "/20240101/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=placeholder");

        AwsGeneralSigV4Validator validator =
                new AwsGeneralSigV4Validator(ACCESS_KEY, SECRET_KEY, "us-east-1");
        String auth = validator.getRecreatedAuthorizationHeader(
                "POST", "/mybucket", "delete", headers);

        return HttpRequest.POST("/mybucket?delete", "<Delete/>")
                .header("Authorization", auth)
                .header("Host", "127.0.0.1:9876")
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", EMPTY_SHA256);
    }
}
