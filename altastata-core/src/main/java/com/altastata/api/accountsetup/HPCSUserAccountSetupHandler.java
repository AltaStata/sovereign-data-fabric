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

package com.altastata.api.accountsetup;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.cloud.ibm.HpcsGrep11KeyGenerator;
import com.altastata.cloud.ibm.IBMHPCSKeyManager;
import com.altastata.filesystem.UserMetadata;
import com.altastata.utils.Account;

/**
 * HPCS account setup via GREP11 gRPC (no PKCS#11 .so for key generation).
 *
 * <p>JavaFX {@code SetupUI} calls {@link #generateAndSaveKeys} when the account name
 * contains {@code hpcs}. Requires {@code GREP11_YAML} or {@code ALTASTATA_HPCS_YAML}
 * pointing to a populated {@code grep11client.yaml}. The UI password field is the
 * IAM API key (optional if the YAML already contains one).
 *
 * <p>Writes {@code public.key}, {@code hpcs-privkey.blob}, and {@code hpcs.marker}.
 * Sign/decrypt at runtime also use GREP11 when the blob is present.
 */
public class HPCSUserAccountSetupHandler implements UserAccountSetupHandlerInterface {

    private static Logger LOGGER = LoggerFactory.getLogger(HPCSUserAccountSetupHandler.class);

    private String publicKeyPEM = null;

    private String keyLabel = null;

    private boolean keysInitialized = false;

    private Account tempAccount = null;

    /**
     * Checks if the HPCS key marker file exists in the directory.
     *
     * @param dirPath The absolute directory path.
     * @return True if 'hpcs.marker' exists.
     */
    @Override
    public boolean ifKeysFilesExist(String dirPath) {
        String markerFilePath = dirPath + File.separator + "hpcs.marker";
        return new File(markerFilePath).exists();
    }

    private String grep11YamlPathInput = null;

    /** Optional path from SetupUI; falls back to {@code GREP11_YAML} when null/empty. */
    /**
     * Optional path from SetupUI; falls back to {@code GREP11_YAML} when null/empty.
     *
     * @param yamlPath The path to grep11client.yaml on the host.
     */
    public void setGrep11YamlPath(String yamlPath) {
        grep11YamlPathInput = yamlPath;
    }

    /**
     * Generates a new RSA public key and saves it locally, while saving the private key's label
     * and reference properties to use IBM Cloud HPCS via GREP11.
     *
     * @param dirPath  The account directory path to save keys to.
     * @param password The IBM Cloud IAM API Key to authenticate with HPCS.
     */
    @Override
    public void generateAndSaveKeys(String dirPath, String password) {
        LOGGER.info("Generating HPCS keys via GREP11 gRPC for account at: {}", dirPath);

        try {
            keyLabel = HpcsGrep11KeyGenerator.keyLabelFromAccountDir(dirPath);
            String yamlPath = HpcsGrep11KeyGenerator.requireYamlPath(grep11YamlPathInput);

            publicKeyPEM = HpcsGrep11KeyGenerator.generateAndSaveKeyPair(
                    dirPath, keyLabel, yamlPath);

            tempAccount = new Account();
            tempAccount.userProps().setProperty("myuser", keyLabel);
            tempAccount.userProps().setProperty("hpcs-key-label", keyLabel);
            tempAccount.userProps().setProperty("hpcs-yaml-path", yamlPath);
            tempAccount.userProps().setProperty(
                    "hpcs-priv-key-blob-path", dirPath + File.separator + "hpcs-privkey.blob");
            tempAccount.userProps().setProperty("key-protection", "HPCS");

            keysInitialized = true;
            LOGGER.info("GREP11 HPCS key generation complete for label: {}", keyLabel);

        } catch (Exception e) {
            LOGGER.error("Failed to generate HPCS keys via GREP11", e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("BXNIM0415E")) {
                msg = "Invalid IBM Cloud API key in grep11client.yaml (check GREP11_YAML).";
            }
            throw new RuntimeException("HPCS key generation failed: " + msg, e);
        }
    }

    /**
     * Indicates whether the HPCS setup and keys initialization is complete.
     *
     * @return True if initialized.
     */
    @Override
    public boolean ifKeysInitialized() {
        return keysInitialized;
    }

    /**
     * Extracts and validates HPCS key information from existing files in the directory.
     *
     * @param dirPath  The account directory path containing key files.
     * @param password The IBM Cloud IAM API key used to verify access.
     * @return True if HPCS configuration and keys are valid and accessible.
     */
    @Override
    public boolean extractKeysFromFiles(String dirPath, String password) {
        LOGGER.info("Loading HPCS key information from: " + dirPath);

        try {
            String markerFilePath = dirPath + File.separator + "hpcs.marker";
            if (!new File(markerFilePath).exists()) {
                LOGGER.error("HPCS marker file not found: " + markerFilePath);
                return false;
            }

            Properties markerProps = new Properties();
            try (FileReader reader = new FileReader(markerFilePath)) {
                markerProps.load(reader);
            }

            keyLabel = markerProps.getProperty("key-label");
            if (keyLabel == null) {
                LOGGER.error("Key label not found in marker file");
                return false;
            }

            String publicKeyPath = dirPath + File.separator + "public.key";
            if (new File(publicKeyPath).exists()) {
                publicKeyPEM = new String(Files.readAllBytes(Paths.get(publicKeyPath)));
            }

            tempAccount = buildGrep11Account(dirPath, password, keyLabel);

            IBMHPCSKeyManager hpcsManager = new IBMHPCSKeyManager(tempAccount);

            if (!hpcsManager.isHPCSConfigured()) {
                LOGGER.warn("HPCS GREP11 not configured (missing yaml, API key, or blob)");
                return false;
            }

            if (hpcsManager.verifyPrivateKeyAccessible()) {
                LOGGER.info("HPCS key verified via GREP11 (remote sign probe): {}", keyLabel);
                keysInitialized = true;
                return true;
            }

            LOGGER.warn("HPCS private key blob not found for label: {}", keyLabel);
            return false;

        } catch (Exception e) {
            LOGGER.error("Failed to load HPCS key information", e);
            return false;
        }
    }

    /**
     * HSM-based private keys cannot be directly re-encrypted. This method updates the metadata
     * marker file with a fresh timestamp instead.
     *
     * @param password The API key or password.
     * @param dirPath  The directory path.
     */
    @Override
    public void reencryptAndSavePrivateKey(String password, String dirPath) {
        LOGGER.info("HPCS: Private key is in HSM - cannot re-encrypt. Updating IAM API key reference.");

        try {
            String markerFilePath = dirPath + File.separator + "hpcs.marker";
            Properties markerProps = new Properties();

            if (new File(markerFilePath).exists()) {
                try (FileReader reader = new FileReader(markerFilePath)) {
                    markerProps.load(reader);
                }
            }

            markerProps.setProperty("api-key-updated", String.valueOf(System.currentTimeMillis()));

            try (FileWriter writer = new FileWriter(markerFilePath)) {
                markerProps.store(writer, "HPCS Key Marker - Private key protected by HSM");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to update HPCS marker", e);
        }
    }

    /**
     * Verifies if the provided API key (password) is valid and can access the HPCS HSM.
     *
     * @param password The API key to test.
     * @param dirPath  The account directory containing the key label.
     * @return True if authentication succeeds.
     */
    @Override
    public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
        LOGGER.info("Verifying HPCS access with IAM API key");

        try {
            String markerFilePath = dirPath + File.separator + "hpcs.marker";
            if (!new File(markerFilePath).exists()) {
                return false;
            }

            Properties markerProps = new Properties();
            try (FileReader reader = new FileReader(markerFilePath)) {
                markerProps.load(reader);
            }

            String storedKeyLabel = markerProps.getProperty("key-label");
            if (storedKeyLabel == null) {
                return false;
            }

            Account verifyAccount = buildGrep11Account(dirPath, password, storedKeyLabel);

            IBMHPCSKeyManager hpcsManager = new IBMHPCSKeyManager(verifyAccount);

            if (!hpcsManager.isHPCSConfigured()) {
                LOGGER.warn("HPCS GREP11 not configured for password check");
                return false;
            }

            return hpcsManager.verifyPrivateKeyAccessible();

        } catch (Exception e) {
            LOGGER.error("Failed to verify HPCS access", e);
            return false;
        }
    }

    /**
     * Adds specific HPCS key-protection configurations and attributes to the user's properties.
     *
     * @param properties The properties object to enrich.
     * @param account    The account instance.
     * @return The enriched properties.
     */
    @Override
    public Properties enhancePropertiesIfNeeded(Properties properties, Account account) {
        properties.setProperty("metadata-encryption", "RSA");
        properties.setProperty("key-protection", "HPCS");

        if (keyLabel != null) {
            properties.setProperty("hpcs-key-label", keyLabel);
            properties.setProperty("myuser", keyLabel);
            String yaml = resolveConfiguredYamlPath(properties.getProperty("hpcs-yaml-path"));
            if (yaml != null) {
                properties.setProperty("hpcs-yaml-path", yaml);
            }
            if (tempAccount != null) {
                String blobPath = tempAccount.userProps().getProperty("hpcs-priv-key-blob-path");
                if (blobPath != null) {
                    properties.setProperty("hpcs-priv-key-blob-path", blobPath);
                }
            }
        }

        LOGGER.info("Enhanced properties with HPCS configuration");
        return properties;
    }

    /**
     * Builds a transient Account instance configured for IBM Cloud HPCS GREP11 gRPC communication.
     *
     * @param dirPath  The account directory.
     * @param password The IBM Cloud IAM API key.
     * @param keyLabel The key label reference.
     * @return A configured Account instance.
     */
    private Account buildGrep11Account(String dirPath, String password, String keyLabel) {
        Account account = new Account();
        account.userProps().setProperty("myuser", keyLabel);
        account.userProps().setProperty("hpcs-key-label", keyLabel);
        account.userProps().setProperty("key-protection", "HPCS");
        if (password != null && !password.isEmpty()) {
            account.userProps().setProperty("hpcs-user-pin", password);
        }
        String yamlPath = resolveConfiguredYamlPath(null);
        if (yamlPath != null) {
            account.userProps().setProperty("hpcs-yaml-path", yamlPath);
        }
        File blob = new File(dirPath, "hpcs-privkey.blob");
        if (blob.isFile()) {
            account.userProps().setProperty("hpcs-priv-key-blob-path", blob.getAbsolutePath());
        }
        return account;
    }

    /**
     * Resolves the GREP11 YAML configuration file path.
     *
     * @param propertiesYamlPath Fallback properties path.
     * @return The absolute path of the resolved grep11client.yaml file.
     */
    private String resolveConfiguredYamlPath(String propertiesYamlPath) {
        String candidate = grep11YamlPathInput;
        if (candidate == null || candidate.trim().isEmpty()) {
            if (tempAccount != null) {
                candidate = tempAccount.userProps().getProperty("hpcs-yaml-path");
            }
        }
        if (candidate == null || candidate.trim().isEmpty()) {
            candidate = propertiesYamlPath;
        }
        return HpcsGrep11KeyGenerator.resolveYamlPath(candidate);
    }

    /**
     * Instantiates a UserMetadata object configured with HPCS-protected RSA key configurations.
     *
     * @param account The active account.
     * @return A configured UserMetadata.
     */
    @Override
    public UserMetadata createUserMetadata(Account account) {
        UserMetadata userMetadata = new UserMetadata(
            account.MY_USER(),
            "user",
            account.ORGANIZATION()
        );

        userMetadata.metadataEncryption_$eq(scala.Option.apply("RSA"));

        if (publicKeyPEM != null) {
            userMetadata.publicKey_$eq(scala.Option.apply(publicKeyPEM));
        }

        LOGGER.info("Created HPCS UserMetadata - key-protection=HPCS");
        return userMetadata;
    }

    /**
     * Instantiates a Cognito-based UserMetadata object configured with HPCS-protected RSA key configurations.
     */
    @Override
    public UserMetadata createCognitoUserMetadata(String userName, String email, String identityId, Account account) {
        UserMetadata userMetadata = new UserMetadata(
            userName,
            "user",
            account.ORGANIZATION()
        );

        userMetadata.metadataEncryption_$eq(scala.Option.apply("RSA"));

        if (publicKeyPEM != null) {
            userMetadata.publicKey_$eq(scala.Option.apply(publicKeyPEM));
        }

        userMetadata.emailAddress_$eq(scala.Option.apply(email));
        userMetadata.cognitoIdentityId_$eq(scala.Option.apply(identityId));

        return userMetadata;
    }

    /**
     * Returns the RSA public key PEM representation to copy during migrations or exports.
     *
     * @param dirPath The account directory path.
     * @return The public key PEM string.
     * @throws IOException If the key cannot be found or read.
     */
    @Override
    public String publicKeysToCopy(String dirPath) throws IOException {
        if (publicKeyPEM != null) {
            return publicKeyPEM;
        }

        String publicKeyPath = dirPath + File.separator + "public.key";
        if (new File(publicKeyPath).exists()) {
            return new String(Files.readAllBytes(Paths.get(publicKeyPath)));
        }

        throw new IOException("Public key not available");
    }

    /**
     * Returns a placeholder or structural info string since HSM private keys cannot be exported.
     *
     * @param dirPath The account directory path.
     * @return A structural placeholder string.
     * @throws IOException If any error occurs.
     */
    @Override
    public String privateKeysToCopy(String dirPath) throws IOException {
        return "# HPCS PROTECTED KEY\n" +
               "# The private key is stored in IBM Hyper Protect Crypto Services - HPCS\n" +
               "# and cannot be exported. The key never leaves the HSM.\n" +
               "#\n" +
               "# Key Label: " + keyLabel + "\n" +
               "#\n" +
               "# To use this key, ensure:\n" +
               "# 1. hpcs-user-pin is set in your properties file (IAM API key)\n" +
               "# 2. hpcs-key-label=" + keyLabel + "\n" +
               "# 3. key-protection=HPCS\n" +
               "#\n" +
               "# All cryptographic operations (sign, decrypt) happen inside the HSM.\n";
    }
}
