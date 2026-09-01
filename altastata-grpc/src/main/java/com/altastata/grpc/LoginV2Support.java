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
import com.altastata.grpc.proto.LoginV2Request;
import com.altastata.grpc.proto.LoginV2Upload;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves {@link LoginV2Request} account material into an
 * {@link AccountRegistry} factory for {@link GrpcUserRegistry#installFromLoginV2}.
 */
final class LoginV2Support {

    private final LoginV2DirectoryPolicy directoryPolicy;

    LoginV2Support() {
        this(new LoginV2DirectoryPolicy());
    }

    LoginV2Support(LoginV2DirectoryPolicy directoryPolicy) {
        this.directoryPolicy = directoryPolicy;
    }

    /**
     * Resolves the login source case (either uploaded memory files or a local account folder path)
     * and maps it to a canonical Resolved model.
     *
     * @param request the login request containing account materials
     * @return resolved context parameters
     */
    Resolved resolve(LoginV2Request request) {
        switch (request.getAccountSourceCase()) {
            case UPLOAD:
                return resolveUpload(request.getUpload());
            case USER_ACCOUNT_DIRECTORY:
                return resolveDirectory(request.getUserAccountDirectory());
            case ACCOUNTSOURCE_NOT_SET:
            default:
                throw new IllegalArgumentException(
                        "Exactly one of upload or user_account_directory must be set");
        }
    }

    /**
     * Resolves key material submitted as a direct memory file upload.
     *
     * @param upload the uploaded login parameters
     * @return resolved context parameters
     */
    private Resolved resolveUpload(LoginV2Upload upload) {
        if (upload.getUserProperties() == null || upload.getUserProperties().trim().isEmpty()) {
            throw new IllegalArgumentException("upload.user_properties is required");
        }
        AccountId accountId = AccountId.fromUserProperties(upload.getUserProperties());
        Map<String, byte[]> files = toByteArrayMap(upload.getAccountFilesMap());
        String privateKeyPem = extractPrivateKeyPemForValidator(
                upload.getUserProperties(), files);
        Supplier<AltaStataFileSystem> fsFactory = () ->
                AccountRegistry.getOrCreateFromUpload(upload.getUserProperties(), files);
        return new Resolved(
                accountId,
                upload.getUserProperties(),
                privateKeyPem,
                fsFactory);
    }

    /**
     * Resolves key material from a co-located local folder path.
     *
     * @param userAccountDirectory target local account folder path
     * @return resolved context parameters
     */
    private Resolved resolveDirectory(String userAccountDirectory) {
        String canonicalDir = directoryPolicy.validateAndCanonicalize(userAccountDirectory);
        AccountId accountId = AccountId.fromAccountDir(canonicalDir);
        String userProperties = readUserPropertiesText(canonicalDir);
        String privateKeyPem = readPrivateKeyPemIfPresent(canonicalDir);
        Supplier<AltaStataFileSystem> fsFactory = () ->
                AccountRegistry.getOrCreateFromDir(canonicalDir);
        return new Resolved(accountId, userProperties, privateKeyPem, fsFactory);
    }

    /**
     * Converts a Protobuf ByteString map to standard byte array map.
     *
     * @param accountFiles raw protobuf key files
     * @return map of filename keys to byte array payloads
     */
    private static Map<String, byte[]> toByteArrayMap(Map<String, ByteString> accountFiles) {
        Map<String, byte[]> files = new HashMap<>();
        for (Map.Entry<String, ByteString> entry : accountFiles.entrySet()) {
            files.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return files;
    }

    /**
     * Extracts private.key from files map for credential validation.
     *
     * @param userProperties user properties configurations
     * @param accountFiles map of filenames to payload bytes
     * @return PEM private key string or empty if missing
     */
    private static String extractPrivateKeyPemForValidator(
            String userProperties, Map<String, byte[]> accountFiles) {
        if (accountFiles.containsKey("private.key")) {
            byte[] pem = accountFiles.get("private.key");
            if (pem != null && pem.length > 0) {
                return new String(pem, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /**
     * Reads the user.properties config file from the target directory folder.
     *
     * @param accountDir path to account directory
     * @return properties configuration content
     */
    private static String readUserPropertiesText(String accountDir) {
        Path dir = Paths.get(accountDir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*user.properties")) {
            Path propsFile = null;
            for (Path candidate : stream) {
                if (propsFile != null) {
                    throw new IllegalArgumentException(
                            "More than one *user.properties file in: " + accountDir);
                }
                propsFile = candidate;
            }
            if (propsFile == null) {
                throw new IllegalArgumentException(
                        "No *user.properties file in: " + accountDir);
            }
            return Files.readString(propsFile);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Can't read user properties from: " + accountDir, e);
        }
    }

    /**
     * Reads private.key from the given account directory path if present.
     *
     * @param accountDir path to account directory
     * @return PEM private key content, or empty if file missing
     */
    private static String readPrivateKeyPemIfPresent(String accountDir) {
        Path privateKey = Paths.get(accountDir, "private.key");
        if (!Files.isRegularFile(privateKey)) {
            return "";
        }
        try {
            return Files.readString(privateKey);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Can't read private.key from: " + accountDir, e);
        }
    }

    static final class Resolved {
        private final AccountId accountId;
        private final String userProperties;
        private final String privateKeyPemForValidator;
        private final Supplier<AltaStataFileSystem> fileSystemFactory;

        Resolved(AccountId accountId,
                 String userProperties,
                 String privateKeyPemForValidator,
                 Supplier<AltaStataFileSystem> fileSystemFactory) {
            this.accountId = accountId;
            this.userProperties = userProperties;
            this.privateKeyPemForValidator = privateKeyPemForValidator;
            this.fileSystemFactory = fileSystemFactory;
        }

        /**
         * Gets the unique AccountId model for this resolved session.
         *
         * @return account identifier
         */
        AccountId accountId() {
            return accountId;
        }

        /**
         * Gets the raw properties config text.
         *
         * @return properties text block
         */
        String userProperties() {
            return userProperties;
        }

        /**
         * Gets the PEM private key payload (if any) to feed to key loaders.
         *
         * @return private key PEM string
         */
        String privateKeyPemForValidator() {
            return privateKeyPemForValidator;
        }

        /**
         * Gets the factory Supplier to instantiate the AltaStataFileSystem context.
         *
         * @return filesystem supplier context
         */
        Supplier<AltaStataFileSystem> fileSystemFactory() {
            return fileSystemFactory;
        }
    }
}
