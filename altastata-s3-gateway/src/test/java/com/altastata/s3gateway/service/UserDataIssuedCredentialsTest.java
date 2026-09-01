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

import com.altastata.api.AltaStataFileSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class UserDataIssuedCredentialsTest {

    @Test
    void forIssuedCredentialsBuildsValidatorsAndS3Service() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);

        UserData data = UserData.forIssuedCredentials(
                "AKIAEXAMPLEKEY123456",
                "secret-access-key-material",
                fs,
                "us-east-1");

        assertEquals("AKIAEXAMPLEKEY123456", data.getAccessKey());
        assertEquals("secret-access-key-material", data.getSecretKey());
        assertNotNull(data.getS3Service());
        assertNotNull(data.getGeneralValidator());
        assertNotNull(data.getPresignedValidator());
    }
}
