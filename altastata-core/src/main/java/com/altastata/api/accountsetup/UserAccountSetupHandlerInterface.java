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

import com.altastata.filesystem.UserMetadata;
import com.altastata.utils.Account;

import java.io.IOException;
import java.util.Properties;

/**
 * Interface defining the operational lifecycle for cryptographic keys initialization,
 * validation, metadata packaging, and persistence for user account setup.
 * 
 * Implementations cover different asymmetric cryptosystems (e.g. RSA, PQC/Kyber/Dilithium, HSM).
 */
public interface UserAccountSetupHandlerInterface {

    /**
     * Checks if the required key/credential files already exist in the given directory.
     *
     * @param dirPath The absolute path to the directory.
     * @return True if key files are present.
     */
    boolean ifKeysFilesExist(String dirPath);

    /**
     * Generates a new cryptographic keypair and securely saves it to the given directory.
     *
     * @param dirPath  The destination directory.
     * @param password The password/PIN used to encrypt or protect the private keys.
     */
    void generateAndSaveKeys(String dirPath, String password);

    /**
     * Checks if the cryptographic keys have been successfully initialized or loaded.
     */
    boolean ifKeysInitialized();

    /**
     * Extracts and validates keys from the local files in the specified directory.
     *
     * @param dirPath  The directory containing the key files.
     * @param password The password or key protecting the private key.
     * @return True if successful.
     */
    boolean extractKeysFromFiles(String dirPath, String password);

    /**
     * Re-encrypts the private key with a new password and saves the updated ciphertext.
     */
    void reencryptAndSavePrivateKey(String password, String dirPath);

    /**
     * Checks if the provided password can successfully decrypt and unlock the private key.
     */
    boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath);

    /**
     * Integrates or enhances the loaded account properties with handler-specific properties.
     */
    Properties enhancePropertiesIfNeeded(Properties properties, Account account);

    /**
     * Creates a {@link UserMetadata} instance containing the user's identities and public keys.
     */
    UserMetadata createUserMetadata(Account account);

    /**
     * Creates a Cognito-specific {@link UserMetadata} instance.
     */
    UserMetadata createCognitoUserMetadata(String userName, String email, String identityId, Account account);

    /**
     * Serializes public keys to copy/export.
     */
    String publicKeysToCopy(String dirPath) throws IOException;

    /**
     * Serializes private keys (or HSM placeholders) to copy/export.
     */
    String privateKeysToCopy(String dirPath) throws IOException;
}
