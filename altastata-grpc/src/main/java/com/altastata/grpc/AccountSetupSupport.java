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
import com.altastata.cloud.ibm.HpcsGrep11KeyGenerator;
import com.altastata.grpc.proto.AccountType;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Shared helpers for {@link AccountSetupGrpcService}.
 */
final class AccountSetupSupport {

    static final List<String> RSA_KEY_FILES =
            Arrays.asList("private.key", "public.key");
    static final List<String> PQC_KEY_FILES = Arrays.asList(
            "kyber_private.key", "kyber_public.key",
            "dilithium_private.key", "dilithium_public.key");
    static final List<String> HPCS_KEY_FILES = Arrays.asList(
            "public.key", "hpcs-privkey.blob", "hpcs.marker");

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private AccountSetupSupport() {
    }

    /**
     * Resolves the list of supported AccountType values on this gateway.
     *
     * @return supported account types list
     */
    static List<AccountType> supportedAccountTypes() {
        List<AccountType> types = new ArrayList<>();
        types.add(AccountType.RSA);
        types.add(AccountType.PQC);
        types.add(AccountType.HPCS);
        return types;
    }

    /**
     * Whether this gateway host can run {@code GenerateKeys} for HPCS via GREP11 gRPC.
     * Listed in {@link #supportedAccountTypes()} regardless; keygen fails at runtime if false.
     */
    static boolean isHpcsKeygenAvailable() {
        return HpcsGrep11KeyGenerator.resolveYamlPath(null) != null;
    }

    /**
     * Resolves the AccountType from the raw user properties content.
     *
     * @param userProperties raw user properties string
     * @return derived AccountType
     */
    static AccountType accountTypeFromUserProperties(String userProperties) {
        Properties props = parseUserProperties(userProperties);
        if ("HPCS".equalsIgnoreCase(props.getProperty("key-protection", ""))) {
            return AccountType.HPCS;
        }
        if ("PQC".equalsIgnoreCase(props.getProperty("metadata-encryption", "RSA"))) {
            return AccountType.PQC;
        }
        return AccountType.RSA;
    }

    /**
     * Infer account type from key files present in an account directory
     * (no {@code *user.properties} required).
     */
    static AccountType accountTypeFromKeyDirectory(Path accountDir) {
        if (accountDir == null || !Files.isDirectory(accountDir)) {
            throw new IllegalArgumentException("Not a directory: " + accountDir);
        }
        if (Files.isRegularFile(accountDir.resolve("kyber_private.key"))
                || Files.isRegularFile(accountDir.resolve("dilithium_private.key"))) {
            return AccountType.PQC;
        }
        if (Files.isRegularFile(accountDir.resolve("hpcs-privkey.blob"))
                || Files.isRegularFile(accountDir.resolve("hpcs.marker"))) {
            return AccountType.HPCS;
        }
        if (Files.isRegularFile(accountDir.resolve("private.key"))) {
            return AccountType.RSA;
        }
        throw new IllegalArgumentException(
                "No recognizable private key files in " + accountDir);
    }

    /**
     * Retrieves the file names of keys required for the given AccountType.
     *
     * @param accountType target account type
     * @return list of required file names
     */
    static List<String> keyFileBasenames(AccountType accountType) {
        switch (accountType) {
            case RSA:
                return RSA_KEY_FILES;
            case PQC:
                return PQC_KEY_FILES;
            case HPCS:
                return HPCS_KEY_FILES;
            default:
                throw new IllegalArgumentException("Unsupported account_type: " + accountType);
        }
    }

    /**
     * Resolves the default user properties file name using structural properties context.
     *
     * @param userProperties raw user properties content
     * @return derived user properties filename
     */
    static String defaultUserPropertiesFileName(String userProperties) {
        AccountId id = AccountId.fromUserProperties(userProperties);
        String prefix = id.getContainerPrefix();
        if (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "-" + id.getMyUser() + ".user.properties";
    }

    /**
     * Scans the given account directory and retrieves the name of the unique user properties file.
     *
     * @param accountDir target folder
     * @return name of the user properties file
     * @throws IOException if folder scanning fails or no properties file found
     */
    static String findUserPropertiesFileName(Path accountDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(accountDir, "*user.properties")) {
            Path propsFile = null;
            for (Path candidate : stream) {
                if (propsFile != null) {
                    throw new IllegalStateException(
                            "More than one *user.properties file in: " + accountDir);
                }
                propsFile = candidate;
            }
            if (propsFile == null) {
                throw new IllegalStateException(
                        "No *user.properties file in: " + accountDir);
            }
            return propsFile.getFileName().toString();
        }
    }

    /**
     * Parses the raw user properties content into a Properties object.
     *
     * @param userProperties raw properties string
     * @return parsed Properties instance
     */
    static Properties parseUserProperties(String userProperties) {
        if (userProperties == null || userProperties.trim().isEmpty()) {
            throw new IllegalArgumentException("user_properties is required");
        }
        Properties props = new Properties();
        try (StringReader reader = new StringReader(userProperties)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Can't parse user properties", e);
        }
        return props;
    }
}
