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

import com.altastata.api.accountsetup.UserAccountSetupHandlerInterface;
import com.altastata.grpc.proto.AccountType;
import com.altastata.grpc.proto.GenerateKeysRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateKeysServiceTest {

    @Test
    void rejectsMissingPasswordForRsa() {
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(true), type -> null);

        assertThrows(IllegalArgumentException.class, () -> service.generate(
                GenerateKeysRequest.newBuilder()
                        .setAccountType(AccountType.RSA)
                        .build()));
    }

    @Test
    void rejectsDisplayNameWithPathSeparator() {
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(true), type -> null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.generate(
                GenerateKeysRequest.newBuilder()
                        .setAccountType(AccountType.RSA)
                        .setPassword("secret")
                        .setSuggestedDisplayName("../escape")
                        .build()));
        assertTrue(ex.getMessage().contains("path separators"));
    }

    @Test
    void generatesDefaultRsaDisplayNameWhenOmitted() {
        StubHandler handler = new StubHandler();
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(true), type -> handler);

        GenerateKeysService.Result result = service.generate(GenerateKeysRequest.newBuilder()
                .setAccountType(AccountType.RSA)
                .setPassword("secret")
                .build());

        assertTrue(result.suggestedDisplayName().startsWith("rsa."));
        assertEquals(2, result.accountFiles().size());
    }

    @Test
    void collectsAllPqcKeyFilesFromHandler() {
        PqcStubHandler handler = new PqcStubHandler();
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(true), type -> handler);

        GenerateKeysService.Result result = service.generate(GenerateKeysRequest.newBuilder()
                .setAccountType(AccountType.PQC)
                .setPassword("secret")
                .setSuggestedDisplayName("amazon.pqc.alice")
                .build());

        assertEquals(4, result.accountFiles().size());
        assertTrue(result.accountFiles().containsKey("kyber_private.key"));
        assertTrue(result.accountFiles().containsKey("dilithium_public.key"));
    }

    @Test
    void collectsAllHpcsKeyFilesFromHandler() {
        Assumptions.assumeTrue(AccountSetupSupport.isHpcsKeygenAvailable(),
                "GREP11 yaml not configured — skip HPCS GenerateKeys test");

        HpcsStubHandler handler = new HpcsStubHandler();
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(true), type -> handler);

        GenerateKeysService.Result result = service.generate(GenerateKeysRequest.newBuilder()
                .setAccountType(AccountType.HPCS)
                .setSuggestedDisplayName("amazon.rsa.hpcs.hpcsdev")
                .build());

        assertEquals(3, result.accountFiles().size());
        assertTrue(result.accountFiles().containsKey("public.key"));
        assertTrue(result.accountFiles().containsKey("hpcs-privkey.blob"));
        assertTrue(result.accountFiles().containsKey("hpcs.marker"));
    }

    @Test
    void rejectsWhenPolicyDenies() {
        GenerateKeysService service = new GenerateKeysService(
                new AccountSetupPolicy(false), type -> null);

        assertThrows(SecurityException.class, () -> service.generate(
                GenerateKeysRequest.newBuilder()
                        .setAccountType(AccountType.RSA)
                        .setPassword("secret")
                        .build()));
    }

    private static final class StubHandler implements UserAccountSetupHandlerInterface {
        @Override
        public boolean ifKeysFilesExist(String dirPath) {
            return false;
        }

        @Override
        public void generateAndSaveKeys(String dirPath, String password) {
            try {
                Files.write(Path.of(dirPath, "private.key"),
                        "private".getBytes(StandardCharsets.UTF_8));
                Files.write(Path.of(dirPath, "public.key"),
                        "public".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean ifKeysInitialized() {
            return true;
        }

        @Override
        public boolean extractKeysFromFiles(String dirPath, String password) {
            return false;
        }

        @Override
        public void reencryptAndSavePrivateKey(String password, String dirPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
            return false;
        }

        @Override
        public java.util.Properties enhancePropertiesIfNeeded(
                java.util.Properties properties, com.altastata.utils.Account account) {
            return properties;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createUserMetadata(
                com.altastata.utils.Account account) {
            return null;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createCognitoUserMetadata(
                String userName, String email, String identityId, com.altastata.utils.Account account) {
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

    private static final class PqcStubHandler implements UserAccountSetupHandlerInterface {
        @Override
        public boolean ifKeysFilesExist(String dirPath) {
            return false;
        }

        @Override
        public void generateAndSaveKeys(String dirPath, String password) {
            try {
                Path dir = Path.of(dirPath);
                Files.write(dir.resolve("kyber_private.key"), "k-priv".getBytes(StandardCharsets.UTF_8));
                Files.write(dir.resolve("kyber_public.key"), "k-pub".getBytes(StandardCharsets.UTF_8));
                Files.write(dir.resolve("dilithium_private.key"), "d-priv".getBytes(StandardCharsets.UTF_8));
                Files.write(dir.resolve("dilithium_public.key"), "d-pub".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean ifKeysInitialized() {
            return true;
        }

        @Override
        public boolean extractKeysFromFiles(String dirPath, String password) {
            return false;
        }

        @Override
        public void reencryptAndSavePrivateKey(String password, String dirPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
            return false;
        }

        @Override
        public java.util.Properties enhancePropertiesIfNeeded(
                java.util.Properties properties, com.altastata.utils.Account account) {
            return properties;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createUserMetadata(
                com.altastata.utils.Account account) {
            return null;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createCognitoUserMetadata(
                String userName, String email, String identityId, com.altastata.utils.Account account) {
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

    private static final class HpcsStubHandler implements UserAccountSetupHandlerInterface {
        @Override
        public boolean ifKeysFilesExist(String dirPath) {
            return false;
        }

        @Override
        public void generateAndSaveKeys(String dirPath, String password) {
            try {
                Path dir = Path.of(dirPath);
                Files.write(dir.resolve("public.key"), "public-pem".getBytes(StandardCharsets.UTF_8));
                Files.write(dir.resolve("hpcs-privkey.blob"), "blob".getBytes(StandardCharsets.UTF_8));
                Files.write(dir.resolve("hpcs.marker"), "marker".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean ifKeysInitialized() {
            return true;
        }

        @Override
        public boolean extractKeysFromFiles(String dirPath, String password) {
            return false;
        }

        @Override
        public void reencryptAndSavePrivateKey(String password, String dirPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
            return false;
        }

        @Override
        public java.util.Properties enhancePropertiesIfNeeded(
                java.util.Properties properties, com.altastata.utils.Account account) {
            return properties;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createUserMetadata(
                com.altastata.utils.Account account) {
            return null;
        }

        @Override
        public com.altastata.filesystem.UserMetadata createCognitoUserMetadata(
                String userName, String email, String identityId, com.altastata.utils.Account account) {
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
