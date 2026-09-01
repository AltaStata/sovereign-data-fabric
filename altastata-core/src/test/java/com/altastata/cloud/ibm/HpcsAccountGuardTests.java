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

package com.altastata.cloud.ibm;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class HpcsAccountGuardTests {

    @Test
    public void allowsEmptyDirectory() throws IOException {
        Path dir = Files.createTempDirectory("hpcs-empty");
        HpcsAccountGuard.assertSafeToWriteKeyFiles(dir.toString());
    }

    @Test
    public void allowsDirectoryWithOnlyPublicKey() throws IOException {
        Path dir = Files.createTempDirectory("hpcs-public-only");
        Files.write(dir.resolve("public.key"), "pem".getBytes(StandardCharsets.UTF_8));
        HpcsAccountGuard.assertSafeToWriteKeyFiles(dir.toString());
    }

    @Test
    public void blocksExistingGrep11KeyFiles() throws IOException {
        Path dir = Files.createTempDirectory("hpcs-existing");
        Files.write(dir.resolve("public.key"), "pem".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("hpcs-privkey.blob"), new byte[] {1, 2, 3});
        try {
            HpcsAccountGuard.assertSafeToWriteKeyFiles(dir.toString());
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("overwrite"));
            assertTrue(ex.getMessage().contains(HpcsAccountGuard.DEFAULT_SANDBOX_USER));
            assertTrue(ex.getMessage().contains("ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT"));
        }
    }

    @Test
    public void rejectsNullAccountDir() {
        try {
            HpcsAccountGuard.assertSafeToWriteKeyFiles(null);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("accountDir"));
        }
    }
}
