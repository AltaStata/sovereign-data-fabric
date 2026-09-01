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

import com.altastata.crypto.AsymmetricKeysGenerator$;
import com.altastata.filesystem.UserMetadata;
import com.altastata.filesystem.securecloud.CloudUserCreatingHandler;
import com.altastata.utils.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Properties;

/**
 * RSA-based implementation of the {@link UserAccountSetupHandlerInterface}.
 * 
 * Manages RSA keypair generation, PEM serialization/deserialization, local key storage,
 * and user metadata packaging for traditional RSA encryption/decryption environments.
 */
public class RSAUserAccountSetupHandler implements UserAccountSetupHandlerInterface {

    static AsymmetricKeysGenerator$ asymmetricKeysGenerator = AsymmetricKeysGenerator$.MODULE$;
    private static Logger LOGGER = LoggerFactory.getLogger(RSAUserAccountSetupHandler.class);

    private KeyPair keyPair = null;

    /**
     * Resolves the path of the public key file.
     */
    private String getPublicKeyPath(String dirPath) {
        return dirPath + File.separator + "public.key";
    }

    /**
     * Resolves the path of the private key file.
     */
    private String getPrivateKeyPath(String dirPath) {
        return dirPath + File.separator + "private.key";
    }

    /**
     * Checks if the RSA private key file exists in the specified directory.
     */
    @Override
    public boolean ifKeysFilesExist(String dirPath) {
        String privateKeyFilePath = getPrivateKeyPath(dirPath);

        return new File(privateKeyFilePath).exists();
    }

    /**
     * Generates a new RSA 4096-bit keypair and saves it to files in the directory.
     */
    @Override
    public void generateAndSaveKeys(String dirPath, String password) {
        keyPair = asymmetricKeysGenerator.generateAndSaveRSAKeys(dirPath, "AES-256-CBC", password.toCharArray());
    }

    /**
     * Indicates whether the RSA keypair is initialized and ready.
     */
    @Override
    public boolean ifKeysInitialized() {
        return keyPair != null;
    }

    /**
     * Loads the RSA keypair (public and private keys) from the directory using the provided password.
     */
    @Override
    public boolean extractKeysFromFiles(String dirPath, String password)  {
        String privateKeyFilePath = getPrivateKeyPath(dirPath);

        LOGGER.info("okPasswordPropertiesButton Create: " + privateKeyFilePath);

        try {
            keyPair = asymmetricKeysGenerator.getKeyPairFromRSAPrivateKey(
                    new FileReader(privateKeyFilePath), password.toCharArray());

            return true;
        } catch (FileNotFoundException e) {
            LOGGER.error("accountConfig Cannot read Key Pair from the file: " + privateKeyFilePath, e);
            return false;
        }
    }

    /**
     * Re-encrypts the RSA private key with a new password and saves it to files.
     */
    @Override
    public void reencryptAndSavePrivateKey(String password, String dirPath) {
        String privateKeyFilePath = getPrivateKeyPath(dirPath);

        LOGGER.info("okPasswordPropertiesButton Change password at: " + privateKeyFilePath);

        String privateKeyPEM = asymmetricKeysGenerator.convertObjectToPEM(
                keyPair.getPrivate(), "AES-256-CBC", password.toCharArray());

        try {
            asymmetricKeysGenerator.storePEM(privateKeyPEM, privateKeyFilePath);
        } catch (Exception e1) {
            LOGGER.error("okPasswordPropertiesButton accountConfig Cannot write to the file: "
                    + privateKeyFilePath, e1);
        }
    }

    /**
     * Verifies if the password successfully decrypts the stored RSA private key.
     */
    @Override
    public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
        String privateKeyFilePath = getPrivateKeyPath(dirPath);

        return asymmetricKeysGenerator.checkPasswordUsingEncryptedRSAPrivateKey(privateKeyFilePath, password.toCharArray());
    }

    /**
     * Enhances the user properties with specific settings for RSA-based encryption.
     */
    @Override
    public Properties enhancePropertiesIfNeeded(Properties properties, Account account) {
        CloudUserCreatingHandler cloudUserCreatingHandler = account.cloudUserCreatingHandler(properties);

        properties = cloudUserCreatingHandler.enhanceUserPropertiesIfNeeded(keyPair.getPublic());
        return properties;
    }

    /**
     * Builds and returns a UserMetadata instance populated with the RSA public key in PEM format.
     */
    @Override
    public UserMetadata createUserMetadata(Account account) {
        UserMetadata userMetadata = new UserMetadata(account.MY_USER(),
                "user",
                account.ORGANIZATION());

        userMetadata.metadataEncryption_$eq(scala.Option.apply("RSA"));

        userMetadata.publicKey_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(keyPair.getPublic(), null, null)));

        return userMetadata;
    }

    /**
     * Builds and returns a Cognito-based UserMetadata instance populated with the RSA public key.
     */
    @Override
    public UserMetadata createCognitoUserMetadata(String userName, String email, String identityId, Account account) {
        UserMetadata userMetadata = new UserMetadata(userName,
                "user",
                account.ORGANIZATION());

        userMetadata.metadataEncryption_$eq(scala.Option.apply("RSA"));

        userMetadata.publicKey_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(keyPair.getPublic(), null, null)));

        userMetadata.emailAddress_$eq(scala.Option.apply(email));
        userMetadata.cognitoIdentityId_$eq(scala.Option.apply(identityId));

        return userMetadata;
    }

    /**
     * Exports the RSA public key PEM.
     */
    @Override
    public String publicKeysToCopy(String dirPath) throws IOException {
        return asymmetricKeysGenerator.convertObjectToPEM(keyPair.getPublic(), null, null);
    }

    /**
     * Exports the encrypted RSA private key PEM.
     */
    @Override
    public String privateKeysToCopy(String dirPath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(getPrivateKeyPath(dirPath))));
    }

}
