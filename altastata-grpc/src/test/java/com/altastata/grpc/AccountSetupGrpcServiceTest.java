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
import com.altastata.grpc.proto.GenerateKeysResponse;
import com.altastata.grpc.proto.GetSupportedAccountTypesRequest;
import com.altastata.grpc.proto.GetSupportedAccountTypesResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class AccountSetupGrpcServiceTest {

    private static final class Capture<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T v) {
            value = v;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        Status statusCode() {
            return ((StatusRuntimeException) error).getStatus();
        }
    }

    @Test
    void getSupportedAccountTypesIncludesRsaAndPqc() {
        AccountSetupGrpcService svc = new AccountSetupGrpcService();
        Capture<GetSupportedAccountTypesResponse> obs = new Capture<>();
        svc.getSupportedAccountTypes(GetSupportedAccountTypesRequest.getDefaultInstance(), obs);

        Assertions.assertNull(obs.error);
        Assertions.assertTrue(obs.completed);
        Assertions.assertTrue(obs.value.getAccountTypesList().contains(AccountType.RSA));
        Assertions.assertTrue(obs.value.getAccountTypesList().contains(AccountType.PQC));
        Assertions.assertTrue(obs.value.getAccountTypesList().contains(AccountType.HPCS));
    }

    @Test
    void generateKeysRejectsMissingAccountType() {
        AccountSetupGrpcService svc = new AccountSetupGrpcService(
                new GenerateKeysService(new AccountSetupPolicy(true), type -> null));
        Capture<GenerateKeysResponse> obs = new Capture<>();
        svc.generateKeys(GenerateKeysRequest.newBuilder()
                .setPassword("secret")
                .build(), obs);

        Assertions.assertEquals(Status.Code.INVALID_ARGUMENT, obs.statusCode().getCode());
    }

    @Test
    void generateKeysRejectsWhenPolicyDenies() {
        AccountSetupGrpcService svc = new AccountSetupGrpcService(
                new GenerateKeysService(new AccountSetupPolicy(false), type -> null));
        Capture<GenerateKeysResponse> obs = new Capture<>();
        svc.generateKeys(GenerateKeysRequest.newBuilder()
                .setAccountType(AccountType.RSA)
                .setPassword("secret")
                .build(), obs);

        Assertions.assertEquals(Status.Code.PERMISSION_DENIED, obs.statusCode().getCode());
    }

    @Test
    void generateKeysReturnsAccountFilesFromHandler() throws IOException {
        StubHandler handler = new StubHandler();
        AccountSetupGrpcService svc = new AccountSetupGrpcService(
                new GenerateKeysService(new AccountSetupPolicy(true), type -> handler));

        Capture<GenerateKeysResponse> obs = new Capture<>();
        svc.generateKeys(GenerateKeysRequest.newBuilder()
                .setAccountType(AccountType.RSA)
                .setPassword("secret")
                .setSuggestedDisplayName("amazon.rsa.bob123")
                .build(), obs);

        Assertions.assertNull(obs.error);
        Assertions.assertTrue(obs.completed);
        Assertions.assertEquals("amazon.rsa.bob123", obs.value.getSuggestedDisplayName());
        Assertions.assertEquals("private-pem",
                obs.value.getAccountFilesMap().get("private.key").toStringUtf8());
        Assertions.assertEquals("public-pem",
                obs.value.getAccountFilesMap().get("public.key").toStringUtf8());
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
                        "private-pem".getBytes(StandardCharsets.UTF_8));
                Files.write(Path.of(dirPath, "public.key"),
                        "public-pem".getBytes(StandardCharsets.UTF_8));
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
