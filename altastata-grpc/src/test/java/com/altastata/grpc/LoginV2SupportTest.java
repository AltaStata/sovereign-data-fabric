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
import com.altastata.grpc.proto.LoginV2Request;
import com.altastata.grpc.proto.LoginV2Upload;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginV2SupportTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    @Test
    void resolveUpload_extractsAccountIdAndPrivateKeyPem() {
        String props = "acccontainer-prefix=altastata-test-bob-\n"
                + "myuser=bob\n"
                + "accounttype=amazon-s3-secure\n";

        LoginV2Support support = new LoginV2Support(
                new LoginV2DirectoryPolicy(tempDir, true));
        LoginV2Support.Resolved resolved = support.resolve(LoginV2Request.newBuilder()
                .setUpload(LoginV2Upload.newBuilder()
                        .setUserProperties(props)
                        .putAccountFiles("private.key",
                                ByteString.copyFromUtf8("encrypted-pem"))
                        .build())
                .build());

        assertEquals(new AccountId("altastata-test-bob-", "bob", "amazon-s3-secure"),
                resolved.accountId());
        assertEquals(props, resolved.userProperties());
        assertEquals("encrypted-pem", resolved.privateKeyPemForValidator());
    }

    @Test
    void resolveUpload_rejectsMissingUserProperties() {
        LoginV2Support support = new LoginV2Support(
                new LoginV2DirectoryPolicy(tempDir, true));

        assertThrows(IllegalArgumentException.class, () -> support.resolve(
                LoginV2Request.newBuilder()
                        .setUpload(LoginV2Upload.newBuilder().build())
                        .build()));
    }

    @Test
    void resolveDirectory_readsUserPropertiesAndPrivateKey() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Path accountDir = accountsRoot.resolve("amazon.rsa.bob123");
        Files.createDirectories(accountDir);

        String props = "acccontainer-prefix=altastata-test-bob-\n"
                + "myuser=bob\n"
                + "accounttype=amazon-s3-secure\n";
        Files.writeString(accountDir.resolve("bob.user.properties"), props);
        Files.writeString(accountDir.resolve("private.key"), "encrypted-pem");

        LoginV2Support support = new LoginV2Support(
                new LoginV2DirectoryPolicy(accountsRoot, true));
        LoginV2Support.Resolved resolved = support.resolve(LoginV2Request.newBuilder()
                .setUserAccountDirectory(accountDir.toString())
                .build());

        assertEquals("bob", resolved.accountId().getMyUser());
        assertEquals(props, resolved.userProperties());
        assertEquals("encrypted-pem", resolved.privateKeyPemForValidator());
    }

    @Test
    void resolveDirectory_rejectsPathOutsideAccountsRoot() throws IOException {
        Path accountsRoot = tempDir.resolve("accounts");
        Files.createDirectories(accountsRoot);
        Path outsider = tempDir.resolve("evil");
        Files.createDirectories(outsider);

        LoginV2Support support = new LoginV2Support(
                new LoginV2DirectoryPolicy(accountsRoot, true));

        assertThrows(SecurityException.class, () -> support.resolve(
                LoginV2Request.newBuilder()
                        .setUserAccountDirectory(outsider.toString())
                        .build()));
    }

    @Test
    void resolveRejectsUnsetAccountSource() {
        LoginV2Support support = new LoginV2Support(
                new LoginV2DirectoryPolicy(tempDir, true));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> support.resolve(LoginV2Request.newBuilder().setPassword("p").build()));
        assertTrue(ex.getMessage().contains("upload or user_account_directory"));
    }
}
