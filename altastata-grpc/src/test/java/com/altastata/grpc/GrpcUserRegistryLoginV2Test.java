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

package com.altastata.grpc;

import com.altastata.api.AccountId;
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcUserRegistryLoginV2Test {

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    @Test
    void installFromLoginV2_firstLoginCreatesFs_reLoginOnlySetPassword() {
        AccountId id = new AccountId("altastata-test-bob-", "bob", "amazon-s3-secure");
        String key = id.key();
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        when(live.getAccountId()).thenReturn(id);

        AtomicInteger factoryCalls = new AtomicInteger();
        Supplier<AltaStataFileSystem> factory = () -> {
            factoryCalls.incrementAndGet();
            return live;
        };

        GrpcUserRegistry.PasswordValidator validator = (pem, pwd) -> {
            if (new String(pwd).equals("WRONG")) {
                throw new RuntimeException("invalid password");
            }
        };
        GrpcUserRegistry registry = new GrpcUserRegistry(un -> null, validator);

        registry.installFromLoginV2(key, "props-a", "pem-a", "correct", factory);
        registry.installFromLoginV2(key, "props-b", "pem-b", "correct", factory);

        assertSame(live, registry.getByAccountKey(key).getAltaStataFileSystem());
        assertEquals(1, factoryCalls.get());
        verify(live, times(2)).setPassword("correct");

        assertThrows(RuntimeException.class, () ->
                registry.installFromLoginV2(key, "props-b", "pem-b", "WRONG", factory));
        assertSame(live, registry.getByAccountKey(key).getAltaStataFileSystem());
    }

    @Test
    void installFromLoginV2_sameMyUserDifferentCloudsAreSeparateEntries() {
        AccountId amazon = new AccountId("altastata-test-bob-", "bob", "amazon-s3-secure");
        AccountId azure = new AccountId("altastata-test-bob-", "bob", "azure-secure");
        AltaStataFileSystem amazonFs = mock(AltaStataFileSystem.class);
        AltaStataFileSystem azureFs = mock(AltaStataFileSystem.class);
        when(amazonFs.getAccountId()).thenReturn(amazon);
        when(azureFs.getAccountId()).thenReturn(azure);

        GrpcUserRegistry registry = new GrpcUserRegistry();
        registry.installFromLoginV2(amazon.key(), "props-a", "pem-a", "p", () -> amazonFs);
        registry.installFromLoginV2(azure.key(), "props-b", "pem-b", "p", () -> azureFs);

        assertSame(amazonFs, registry.getByAccountKey(amazon.key()).getAltaStataFileSystem());
        assertSame(azureFs, registry.getByAccountKey(azure.key()).getAltaStataFileSystem());
        assertSame(amazonFs, registry.findLiveInSameLake(amazon, "bob").getAltaStataFileSystem());
        assertSame(azureFs, registry.findLiveInSameLake(azure, "bob").getAltaStataFileSystem());
    }

    @Test
    void installFromLoginV2_invalidatesRegistryEntryWhenSetPasswordFails() {
        AltaStataFileSystem probe = mock(AltaStataFileSystem.class);
        AccountId id = new AccountId("altastata-test-bob-", "bob", "amazon-s3-secure");
        when(probe.getAccountId()).thenReturn(id);
        AccountRegistry.putForTesting(id, probe);
        doThrow(new RuntimeException("bad password")).when(probe).setPassword("wrong");

        GrpcUserRegistry registry = new GrpcUserRegistry();
        assertThrows(RuntimeException.class, () ->
                registry.installFromLoginV2(id.key(), "props", "pem", "wrong", () -> probe));
        assertNull(AccountRegistry.get(id));
    }
}
