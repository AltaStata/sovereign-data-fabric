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
import com.altastata.api.accountsetup.UserAccountSetupHandlerInterface;
import com.altastata.grpc.proto.ChangePasswordRequest;
import com.altastata.grpc.proto.DeleteAccountRequest;
import com.altastata.utils.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountMaintenanceServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

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
    void changePasswordReturnsReencryptedKeyFiles() throws Exception {
        Path accountDir = tempDir.resolve("amazon.pqc.alice");
        Files.createDirectories(accountDir);
        writePqcFixture(accountDir, "old-secret");

        String props = pqcUserProperties("alice");
        Files.write(accountDir.resolve("altastata-test-alice-alice.user.properties"),
                props.getBytes(StandardCharsets.UTF_8));

        AltaStataFileSystem fs = mockFileSystemAt(accountDir, "alice");

        GrpcUserData userData = loggedInUser("alice", props, fs);
        Session session = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false)
                .create("alice", "test");

        AccountMaintenanceService service = maintenanceService(new PasswordChangeHandler());
        Map<String, byte[]> files = service.changePassword(
                session,
                userData,
                ChangePasswordRequest.newBuilder()
                        .setCurrentPassword("old-secret")
                        .setNewPassword("new-secret")
                        .build());

        assertEquals("reencrypted-kyber",
                new String(files.get("kyber_private.key"), StandardCharsets.UTF_8));
        assertEquals(fs, userData.getAltaStataFileSystem());
    }

    @Test
    void changePasswordInDirectoryBootstrapReencryptsKeysWithoutLogin() throws Exception {
        Path accountsHome = Paths.get(Account.ALTASTATA_ACCOUNTS_HOME()).toAbsolutePath();
        Path accountDir = accountsHome.resolve("test.pqc.bootstrap");
        Files.createDirectories(accountDir);
        try {
            writePqcFixture(accountDir, "old-secret");

            AccountMaintenanceService service = maintenanceService(new PasswordChangeHandler());
            Map<String, byte[]> files = service.changePasswordInDirectory(
                    accountDir.toString(),
                    "old-secret",
                    "new-secret");

            assertEquals("reencrypted-kyber",
                    new String(files.get("kyber_private.key"), StandardCharsets.UTF_8));
        } finally {
            org.apache.commons.io.FileUtils.deleteQuietly(accountDir.toFile());
        }
    }

    @Test
    void exportAccountIncludesPropertiesAndKeyFiles() throws Exception {
        Path accountDir = tempDir.resolve("amazon.rsa.bob");
        Files.createDirectories(accountDir);
        String props = rsaUserProperties("bob");
        Files.write(accountDir.resolve("altastata-test-bob-bob.user.properties"),
                props.getBytes(StandardCharsets.UTF_8));
        Files.write(accountDir.resolve("private.key"), "private-pem".getBytes(StandardCharsets.UTF_8));
        Files.write(accountDir.resolve("public.key"), "public-pem".getBytes(StandardCharsets.UTF_8));

        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Account account = mock(Account.class);
        when(fs.getAccount()).thenReturn(account);
        when(account.getAccountDir()).thenReturn(accountDir.toString());
        when(fs.getAccountId()).thenReturn(idFor("bob"));

        GrpcUserData userData = loggedInUser("bob", props, fs);
        Session session = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false)
                .create("bob", "test");

        Map<String, byte[]> files = maintenanceService(null).exportAccount(session, userData);

        assertEquals(props, new String(
                files.get("altastata-test-bob-bob.user.properties"), StandardCharsets.UTF_8));
        assertEquals("private-pem",
                new String(files.get("private.key"), StandardCharsets.UTF_8));
        assertEquals("public-pem",
                new String(files.get("public.key"), StandardCharsets.UTF_8));
    }

    @Test
    void deleteAccountRevokesSessionsAndRegistryState() throws Exception {
        Path accountDir = tempDir.resolve("amazon.pqc.carol");
        Files.createDirectories(accountDir);
        writePqcFixture(accountDir, "secret");
        String props = pqcUserProperties("carol");
        Files.write(accountDir.resolve("altastata-test-carol-carol.user.properties"),
                props.getBytes(StandardCharsets.UTF_8));

        GrpcUserRegistry users = new GrpcUserRegistry();
        AltaStataFileSystem fs = mockFileSystemAt(accountDir, "carol");
        users.installFromLoginV2("carol", props, "", "secret", () -> fs);

        SessionRegistry sessions = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false);
        S3CredentialsRegistry credentials = new S3CredentialsRegistry(sessions, Clock.fixed(T0, ZoneOffset.UTC));

        Session session = sessions.create("carol", "test");
        credentials.issue(session.token(), "carol", fs, "etl");

        GrpcUserData userData = users.getByAccountKey("carol");
        maintenanceService(new PasswordChangeHandler(), sessions, credentials, users)
                .deleteAccount(session, userData, DeleteAccountRequest.newBuilder()
                        .setCurrentPassword("secret")
                        .build());

        assertEquals(0, sessions.size());
        assertNull(users.getByAccountKey("carol"));
        assertEquals(0, AccountRegistry.size());
        assertEquals(0, credentials.size());
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() throws Exception {
        Path accountDir = tempDir.resolve("amazon.pqc.dave");
        Files.createDirectories(accountDir);
        writePqcFixture(accountDir, "right");
        String props = pqcUserProperties("dave");
        Files.write(accountDir.resolve("altastata-test-dave-dave.user.properties"),
                props.getBytes(StandardCharsets.UTF_8));

        AltaStataFileSystem fs = mockFileSystemAt(accountDir, "dave");

        GrpcUserData userData = loggedInUser("dave", props, fs);
        Session session = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false)
                .create("dave", "test");

        assertThrows(SecurityException.class, () -> maintenanceService(new PasswordChangeHandler())
                .changePassword(session, userData, ChangePasswordRequest.newBuilder()
                        .setCurrentPassword("wrong")
                        .setNewPassword("new")
                        .build()));
    }

    private AccountMaintenanceService maintenanceService(UserAccountSetupHandlerInterface handler) {
        SessionRegistry sessions = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false);
        return maintenanceService(handler, sessions,
                new S3CredentialsRegistry(sessions, Clock.fixed(T0, ZoneOffset.UTC)),
                new GrpcUserRegistry());
    }

    private AccountMaintenanceService maintenanceService(
            UserAccountSetupHandlerInterface handler,
            SessionRegistry sessions,
            S3CredentialsRegistry credentials,
            GrpcUserRegistry users) {
        GenerateKeysService.AccountSetupHandlerFactory factory = type -> {
            if (handler != null) {
                return handler;
            }
            throw new IllegalArgumentException("no handler for " + type);
        };
        return new AccountMaintenanceService(
                sessions, credentials, new EventBus(EventBus.DEFAULT_RING_SIZE,
                        Clock.fixed(T0, ZoneOffset.UTC)), users, factory);
    }

    private static GrpcUserData loggedInUser(String userName, String props, AltaStataFileSystem fs) {
        GrpcUserData data = new GrpcUserData(userName);
        data.setUserProperties(props);
        data.setAltaStataFileSystem(fs);
        return data;
    }

    private static AccountId idFor(String userName) {
        return new AccountId("altastata-test-" + userName + "-", userName, "amazon-s3-secure");
    }

    private static String rsaUserProperties(String userName) {
        AccountId id = idFor(userName);
        return "acccontainer-prefix=" + id.getContainerPrefix() + "\n"
                + "myuser=" + id.getMyUser() + "\n"
                + "accounttype=" + id.getAccountType() + "\n"
                + "metadata-encryption=RSA\n";
    }

    private static String pqcUserProperties(String userName) {
        return rsaUserProperties(userName).replace("metadata-encryption=RSA", "metadata-encryption=PQC");
    }

    private static void writePqcFixture(Path accountDir, String password) throws IOException {
        Files.write(accountDir.resolve("kyber_private.key"),
                ("kyber-" + password).getBytes(StandardCharsets.UTF_8));
        Files.write(accountDir.resolve("kyber_public.key"), "kyber-public".getBytes(StandardCharsets.UTF_8));
        Files.write(accountDir.resolve("dilithium_private.key"),
                ("dilithium-" + password).getBytes(StandardCharsets.UTF_8));
        Files.write(accountDir.resolve("dilithium_public.key"),
                "dilithium-public".getBytes(StandardCharsets.UTF_8));
    }

    private static AltaStataFileSystem mockFileSystemAt(Path accountDir, String userName) {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Account account = mock(Account.class);
        when(fs.getAccount()).thenReturn(account);
        when(account.getAccountDir()).thenReturn(accountDir.toString());
        when(fs.getAccountId()).thenReturn(idFor(userName));
        when(fs.setPassword(anyString())).thenReturn(fs);
        return fs;
    }

    private static final class PasswordChangeHandler implements UserAccountSetupHandlerInterface {
        @Override
        public boolean ifKeysFilesExist(String dirPath) {
            return true;
        }

        @Override
        public void generateAndSaveKeys(String dirPath, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean ifKeysInitialized() {
            return true;
        }

        @Override
        public boolean extractKeysFromFiles(String dirPath, String password) {
            return password != null
                    && (password.startsWith("old") || "secret".equals(password) || "right".equals(password));
        }

        @Override
        public void reencryptAndSavePrivateKey(String password, String dirPath) {
            try {
                Files.write(Path.of(dirPath, "kyber_private.key"),
                        "reencrypted-kyber".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
            return extractKeysFromFiles(dirPath, password);
        }

        @Override
        public java.util.Properties enhancePropertiesIfNeeded(
                java.util.Properties properties, Account account) {
            return properties;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createUserMetadata(Account account) {
            return null;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createCognitoUserMetadata(
                String userName, String email, String identityId, Account account) {
            return null;
        }

        @Override
        public String publicKeysToCopy(String dirPath) {
            return "";
        }

        @Override
        public String privateKeysToCopy(String dirPath) {
            return "";
        }
    }
}
