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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginV2DirectoryPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsDirectoryUnderAccountsRoot() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Path accountDir = accountsRoot.resolve("amazon.rsa.bob123");
        Files.createDirectories(accountDir);

        LoginV2DirectoryPolicy policy = new LoginV2DirectoryPolicy(accountsRoot, true);
        String canonical = policy.validateAndCanonicalize(accountDir.toString());

        assertEquals(accountDir.toAbsolutePath().normalize().toString(), canonical);
    }

    @Test
    void rejectsPathOutsideAccountsRoot() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Files.createDirectories(accountsRoot);
        Path outsider = tempDir.resolve("outside");

        LoginV2DirectoryPolicy policy = new LoginV2DirectoryPolicy(accountsRoot, true);

        assertThrows(SecurityException.class,
                () -> policy.validateAndCanonicalize(outsider.toString()));
    }

    @Test
    void rejectsWhenDirectoryLoginDisabled() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Path accountDir = accountsRoot.resolve("amazon.rsa.bob123");
        Files.createDirectories(accountDir);

        LoginV2DirectoryPolicy policy = new LoginV2DirectoryPolicy(accountsRoot, false);

        assertThrows(SecurityException.class,
                () -> policy.validateAndCanonicalize(accountDir.toString()));
    }

    @Test
    void rejectsBlankPath() {
        LoginV2DirectoryPolicy policy = new LoginV2DirectoryPolicy(tempDir, true);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndCanonicalize("   "));
    }

    @Test
    void rejectsNonDirectory() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Files.createDirectories(accountsRoot);
        Path file = accountsRoot.resolve("not-a-dir");
        Files.writeString(file, "x");

        LoginV2DirectoryPolicy policy = new LoginV2DirectoryPolicy(accountsRoot, true);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndCanonicalize(file.toString()));
    }
}
