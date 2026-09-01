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

/*
 * GREP11 (gRPC) RSA key generation for HPCS — no PKCS#11 .so required.
 * Used by JavaFX {@link com.altastata.api.accountsetup.HPCSUserAccountSetupHandler},
 * {@link HPCSCreateKey}, and gRPC {@code GenerateKeys}.
 */

package com.altastata.cloud.ibm;

import com.altastata.utils.Account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Creates an RSA key pair in IBM HPCS via GREP11 gRPC and writes account files.
 * Configuration comes from {@code grep11client.yaml} (env {@code GREP11_YAML}).
 */
public final class HpcsGrep11KeyGenerator {

    private static final String[] YAML_ENV_VARS = {
            "GREP11_YAML",
            "ALTASTATA_HPCS_YAML"
    };

    /**
     * Private constructor to prevent instantiation.
     */
    private HpcsGrep11KeyGenerator() {
    }

    /**
     * @return path to grep11client.yaml, or null if not configured
     */
    public static String resolveYamlPath(String hpcsYamlPathProperty) {
        if (hpcsYamlPathProperty != null && !hpcsYamlPathProperty.trim().isEmpty()) {
            Path path = Paths.get(hpcsYamlPathProperty.trim());
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
        }
        for (String envVar : YAML_ENV_VARS) {
            String value = System.getenv(envVar);
            if (value != null && !value.trim().isEmpty()) {
                Path path = Paths.get(value.trim());
                if (Files.isRegularFile(path)) {
                    return path.toString();
                }
            }
        }
        return null;
    }

    /**
     * Resolves the GREP11 configuration YAML path and asserts that it points to a valid, populated file.
     *
     * @param hpcsYamlPathProperty an optional input property path
     * @return the resolved non-template YAML path
     * @throws IllegalStateException if GREP11 configuration is not found or is a template
     */
    public static String requireYamlPath(String hpcsYamlPathProperty) {
        String yamlPath = resolveYamlPath(hpcsYamlPathProperty);
        if (yamlPath == null) {
            throw new IllegalStateException(
                    "GREP11 is not configured. Set hpcs-yaml-path, GREP11_YAML, or ALTASTATA_HPCS_YAML "
                            + "to a populated grep11client.yaml.");
        }
        if (yamlPath.replace('\\', '/').endsWith("altastata-core/grep11client.yaml")) {
            throw new IllegalStateException(
                    "GREP11_YAML points to the template altastata-core/grep11client.yaml.\n"
                            + "Set GREP11_YAML to a populated grep11client.yaml (no <your- placeholders).");
        }
        return yamlPath;
    }

    public static String generateAndSaveKeyPair(String accountDir,
                                                String keyLabel,
                                                String yamlPathInput) {
        HpcsAccountGuard.assertSafeToWriteKeyFiles(accountDir);
        String yamlPath = requireYamlPath(yamlPathInput);
        try {
            Grep11ConfigFromYaml config = Grep11ConfigFromYaml.load(Paths.get(yamlPath));
            String apiKey = config.apiKey;
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "IBM Cloud API key missing in " + yamlPath
                                + " (tokens.0.users.2.iamauth.apikey).");
            }
            try (Grep11RsaClient client = new Grep11RsaClient(
                    config.endpoint, config.port, config.instanceId, apiKey)) {
                Grep11RsaClient.KeyPairBlobs keyPair = client.generateKeyPair(4096);
                String publicPem = keyPair.publicKeyPem != null
                        ? keyPair.publicKeyPem
                        : Grep11RsaClient.publicKeyBlobToPem(keyPair.publicKeyBlob);
                Account minimalAccount = new Account();
                minimalAccount.userProps().setProperty("myuser", keyLabel);
                minimalAccount.userProps().setProperty("key-protection", "HPCS");
                minimalAccount.userProps().setProperty("hpcs-key-label", keyLabel);
                minimalAccount.userProps().setProperty("hpcs-yaml-path", yamlPath);
                minimalAccount.userProps().setProperty(
                        "hpcs-priv-key-blob-path", accountDir + "/hpcs-privkey.blob");
                IBMHPCSKeyManager manager = new IBMHPCSKeyManager(minimalAccount);
                manager.writePublicKeyToAccountDirectory(
                        publicPem, keyLabel, accountDir, keyPair.privateKeyBlob);
                return publicPem;
            }
        } catch (RuntimeException e) {
            throw enrichIamApiKeyError(e, yamlPath);
        } catch (Exception e) {
            throw new RuntimeException("GREP11 key generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Enriches a runtime exception with context if it is identified as an invalid IAM API key error.
     *
     * @param e the original runtime exception
     * @param yamlPath the path to the YAML configuration file used
     * @return the enriched or original exception
     */
    private static RuntimeException enrichIamApiKeyError(RuntimeException e, String yamlPath) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("BXNIM0415E")) {
            return new RuntimeException(
                    "Invalid IBM Cloud API key in grep11client.yaml: " + yamlPath, e);
        }
        return e;
    }

    /** Last path segment of account dir (e.g. {@code hpcsdev} from {@code amazon.rsa.hpcs.hpcsdev}). */
    public static String keyLabelFromAccountDir(String dirPath) {
        String name = Paths.get(dirPath).getFileName().toString();
        if (name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return name;
    }
}
