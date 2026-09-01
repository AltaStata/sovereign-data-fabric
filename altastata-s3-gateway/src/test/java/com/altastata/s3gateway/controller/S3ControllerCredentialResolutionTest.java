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
import com.altastata.s3gateway.service.UserData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3 auth must resolve issued gRPC credentials ({@link S3CredentialsRegistry})
 * rather than legacy PUT bootstrap on {@code userDataMap}.
 */
class S3ControllerCredentialResolutionTest {

    @Test
    void getUserDataByAccessKeyResolvesIssuedGrpcCredentials() throws Exception {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        S3CredentialsRegistry credentials = mock(S3CredentialsRegistry.class);
        when(credentials.resolveForS3("AKIATESTKEY")).thenReturn(Optional.of(
                new S3CredentialsRegistry.S3ResolveResult(fs, "bob", "secret-key")));

        S3Controller controller = new S3Controller(Optional.of(credentials));

        Method lookup = S3Controller.class.getDeclaredMethod(
                "getUserDataByAccessKey", String.class);
        lookup.setAccessible(true);
        UserData userData = (UserData) lookup.invoke(controller, "AKIATESTKEY");

        assertNotNull(userData);
        assertEquals("AKIATESTKEY", userData.getAccessKey());
        assertEquals("secret-key", userData.getSecretKey());
        assertNotNull(userData.getS3Service());
    }

    @Test
    void getUserDataByAccessKeyReusesIssuedCredentialsInstanceForMultipartState() throws Exception {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        S3CredentialsRegistry credentials = mock(S3CredentialsRegistry.class);
        when(credentials.resolveForS3("AKIATESTKEY")).thenReturn(Optional.of(
                new S3CredentialsRegistry.S3ResolveResult(fs, "bob", "secret-key")));

        S3Controller controller = new S3Controller(Optional.of(credentials));

        Method lookup = S3Controller.class.getDeclaredMethod(
                "getUserDataByAccessKey", String.class);
        lookup.setAccessible(true);
        UserData first = (UserData) lookup.invoke(controller, "AKIATESTKEY");
        UserData second = (UserData) lookup.invoke(controller, "AKIATESTKEY");

        assertSame(first, second);
        assertSame(first.getS3Service(), second.getS3Service());
    }
}
