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

import com.altastata.api.accountsetup.HPCSUserAccountSetupHandler;
import com.altastata.api.accountsetup.PQCUserAccountSetupHandler;
import com.altastata.api.accountsetup.RSAUserAccountSetupHandler;
import com.altastata.api.accountsetup.UserAccountSetupHandlerInterface;
import com.altastata.cloud.ibm.HpcsGrep11KeyGenerator;
import com.altastata.grpc.proto.AccountType;
import com.altastata.grpc.proto.GenerateKeysRequest;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs account keygen in a temp directory and returns key files for
 * {@code GenerateKeysResponse} (no persistence on gateway).
 */
@Singleton
public final class GenerateKeysService {

    private static final List<String> RSA_FILES = AccountSetupSupport.RSA_KEY_FILES;
    private static final List<String> PQC_FILES = AccountSetupSupport.PQC_KEY_FILES;
    private static final List<String> HPCS_FILES = AccountSetupSupport.HPCS_KEY_FILES;

    private final AccountSetupPolicy policy;
    private final AccountSetupHandlerFactory handlerFactory;

    /**
     * Default constructor for GenerateKeysService initializing default setup policies and handlers.
     */
    public GenerateKeysService() {
        this(new AccountSetupPolicy(), AccountSetupHandlerFactory.DEFAULT);
    }

    GenerateKeysService(AccountSetupPolicy policy, AccountSetupHandlerFactory handlerFactory) {
        this.policy = policy;
        this.handlerFactory = handlerFactory;
    }

    /**
     * Generates a new cryptographic keypair for the specified account type and returns files.
     *
     * @param request request specifying account type, optional password, and display name options
     * @return Result containing suggested display name and generated file byte arrays
     */
    Result generate(GenerateKeysRequest request) {
        policy.requireAccountSetupPermitted();

        AccountType accountType = request.getAccountType();
        if (accountType == AccountType.ACCOUNT_TYPE_UNSPECIFIED
                || accountType == AccountType.UNRECOGNIZED) {
            throw new IllegalArgumentException("account_type is required");
        }
        if (requiresPassword(accountType)
                && (request.getPassword() == null || request.getPassword().isEmpty())) {
            throw new IllegalArgumentException("password is required");
        }
        if (accountType == AccountType.HPCS) {
            HpcsGrep11KeyGenerator.requireYamlPath(null);
        }

        String displayName = resolveDisplayName(request, accountType);
        Path tempRoot = null;
        try {
            tempRoot = Files.createTempDirectory("altastata-keygen-");
            Path accountDir = tempRoot.resolve(sanitizeDisplayName(displayName));
            Files.createDirectories(accountDir);

            UserAccountSetupHandlerInterface handler = handlerFactory.create(accountType);
            handler.generateAndSaveKeys(accountDir.toString(), request.getPassword());

            Map<String, byte[]> accountFiles = readAccountFiles(accountDir, accountType);
            if (accountFiles.isEmpty()) {
                throw new IllegalStateException("Key generation produced no account files");
            }
            return new Result(displayName, accountFiles);
        } catch (IOException e) {
            throw new IllegalStateException("Key generation failed", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? "Key generation failed" : e.getMessage(), e);
        } finally {
            if (tempRoot != null) {
                deleteRecursively(tempRoot);
            }
        }
    }

    /**
     * Determines whether the selected account type requires a security password.
     *
     * @param accountType target account type
     * @return true if password is required; false otherwise
     */
    private static boolean requiresPassword(AccountType accountType) {
        return accountType == AccountType.RSA || accountType == AccountType.PQC;
    }

    /**
     * Resolves or assigns a fallback unique display name for the newly generated account.
     *
     * @param request the key generation request
     * @param accountType target account type
     * @return resolved display name string
     */
    private static String resolveDisplayName(GenerateKeysRequest request, AccountType accountType) {
        String suggested = request.getSuggestedDisplayName();
        if (suggested != null && !suggested.trim().isEmpty()) {
            return suggested.trim();
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        switch (accountType) {
            case RSA:
                return "rsa." + suffix;
            case PQC:
                return "pqc." + suffix;
            case HPCS:
                return "hpcs." + suffix;
            default:
                throw new IllegalArgumentException("Unsupported account_type: " + accountType);
        }
    }

    /**
     * Sanitizes display name input to prevent path injections or directory traversals.
     *
     * @param displayName raw input display name
     * @return sanitized display name
     */
    private static String sanitizeDisplayName(String displayName) {
        if (displayName.contains("/") || displayName.contains("\\") || displayName.contains("..")) {
            throw new IllegalArgumentException("suggested_display_name must not contain path separators");
        }
        return displayName;
    }

    /**
      * Reads account files for a given account type.
      * @param accountDir directory to read from
      * @param accountType type of account
      * @return map of file names to contents
      * @throws IOException if read fails
      */
    private static Map<String, byte[]> readAccountFiles(Path accountDir, AccountType accountType)
            throws IOException {
        List<String> basenames;
        switch (accountType) {
            case RSA:
                basenames = RSA_FILES;
                break;
            case PQC:
                basenames = PQC_FILES;
                break;
            case HPCS:
                basenames = HPCS_FILES;
                break;
            default:
                throw new IllegalArgumentException("Unsupported account_type: " + accountType);
        }

        Map<String, byte[]> files = new HashMap<>();
        for (String basename : basenames) {
            Path file = accountDir.resolve(basename);
            if (Files.isRegularFile(file)) {
                files.put(basename, Files.readAllBytes(file));
            }
        }
        return files;
    }

    /**
     * Recursively deletes a directory or file.
     *
     * @param root folder or file path to delete
     */
    private static void deleteRecursively(Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    static final class Result {
        private final String suggestedDisplayName;
        private final Map<String, byte[]> accountFiles;

        Result(String suggestedDisplayName, Map<String, byte[]> accountFiles) {
            this.suggestedDisplayName = suggestedDisplayName;
            this.accountFiles = accountFiles;
        }

        String suggestedDisplayName() {
            return suggestedDisplayName;
        }

        Map<String, byte[]> accountFiles() {
            return accountFiles;
        }
    }

    @FunctionalInterface
    interface AccountSetupHandlerFactory {
        AccountSetupHandlerFactory DEFAULT = type -> {
            switch (type) {
                case RSA:
                    return new RSAUserAccountSetupHandler();
                case PQC:
                    return new PQCUserAccountSetupHandler();
                case HPCS:
                    return new HPCSUserAccountSetupHandler();
                default:
                    throw new IllegalArgumentException("Unsupported account_type: " + type);
            }
        };

        UserAccountSetupHandlerInterface create(AccountType accountType);
    }
}
