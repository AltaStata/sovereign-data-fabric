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
import scala.Tuple2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Properties;

/**
 * Post-Quantum Cryptography (PQC) implementation of the {@link UserAccountSetupHandlerInterface}.
 * 
 * Manages dual keypairs for Kyber (Key Encapsulation Mechanism) and Dilithium (Digital Signature Algorithm)
 * to facilitate quantum-resistant encryption/decryption and cryptographic signing.
 */
public class PQCUserAccountSetupHandler implements UserAccountSetupHandlerInterface {

    static AsymmetricKeysGenerator$ asymmetricKeysGenerator = AsymmetricKeysGenerator$.MODULE$;
    private static Logger LOGGER = LoggerFactory.getLogger(PQCUserAccountSetupHandler.class);

    private KeyPair kyberKeyPair = null;
    private KeyPair dilithiumKeyPair = null;

    /**
     * Resolves the path of the public key file for the given algorithm.
     */
    private String getPublicKeyPath(String dirPath, String algorithm) {
        return dirPath + File.separator + algorithm + "_public.key";
    }

    /**
     * Resolves the path of the private key file for the given algorithm.
     */
    private String getPrivateKeyPath(String dirPath, String algorithm) {
        return dirPath + File.separator + algorithm + "_private.key";
    }

    /**
     * Checks if both Kyber and Dilithium private key files exist in the specified directory.
     */
    @Override
    public boolean ifKeysFilesExist(String dirPath) {
        String kyberPrivateKeyFilePath = getPrivateKeyPath(dirPath, "kyber");
        String dilithiumPrivateKeyFilePath = getPrivateKeyPath(dirPath, "dilithium");

        return new File(kyberPrivateKeyFilePath).exists() && new File(dilithiumPrivateKeyFilePath).exists();
    }

    /**
     * Generates a new Kyber-1024 KEM keypair and a Dilithium-5 signature keypair,
     * and saves them securely to files in the given directory.
     */
    @Override
    public void generateAndSaveKeys(String dirPath, String password) {
        Tuple2<KeyPair, KeyPair> keys = asymmetricKeysGenerator.generateAndSavePQCKeys(dirPath, password.toCharArray());

        kyberKeyPair = keys._1;
        dilithiumKeyPair = keys._2;
    }

    /**
     * Indicates whether both keypairs are initialized and ready.
     */
    @Override
    public boolean ifKeysInitialized() {
        return kyberKeyPair != null && dilithiumKeyPair != null;
    }

    /**
     * Loads the PQC keypairs (public and private keys) from the directory using the provided password.
     */
    @Override
    public boolean extractKeysFromFiles(String dirPath, String password)  {
        String kyberPrivateKeyFilePath = getPrivateKeyPath(dirPath, "kyber");
        String dilithiumPrivateKeyFilePath = getPrivateKeyPath(dirPath, "dilithium");

        String kyberPublicKeyFilePath = getPublicKeyPath(dirPath, "kyber");
        String dilithiumPublicFilePath = getPublicKeyPath(dirPath, "dilithium");

        LOGGER.info("okPasswordPropertiesButton extract: " + kyberPrivateKeyFilePath + " and " + dilithiumPrivateKeyFilePath);

        try {
            PublicKey kyberPublicKey = asymmetricKeysGenerator.loadPQCPublicKey("KYBER", new String(Files.readAllBytes(Paths.get(kyberPublicKeyFilePath))));
            PublicKey dilithiumPublicKey = asymmetricKeysGenerator.loadPQCPublicKey("DILITHIUM", new String(Files.readAllBytes(Paths.get(dilithiumPublicFilePath))));

            PrivateKey kyberPrivateKey =
                    asymmetricKeysGenerator.loadPQCPrivateKeyFromString("KYBER", password.toCharArray(), new String(Files.readAllBytes(Paths.get(kyberPrivateKeyFilePath))));

            PrivateKey dilithiumPrivateKey =
                    asymmetricKeysGenerator.loadPQCPrivateKeyFromString("DILITHIUM", password.toCharArray(), new String(Files.readAllBytes(Paths.get(dilithiumPrivateKeyFilePath))));

            kyberKeyPair = new KeyPair(kyberPublicKey, kyberPrivateKey);
            dilithiumKeyPair = new KeyPair(dilithiumPublicKey, dilithiumPrivateKey);

            return true;
        } catch (FileNotFoundException e) {
            LOGGER.error("accountConfig Cannot read Key from the file", e);
            return false;
        } catch (IOException e) {
            LOGGER.error("accountConfig Cannot read Key from the file", e);
            return false;
        }
    }

    /**
     * Re-encrypts the Dilithium and Kyber private keys with a new password.
     */
    @Override
    public void reencryptAndSavePrivateKey(String password, String dirPath) {
        LOGGER.info("okPasswordPropertiesButton Change password");

        asymmetricKeysGenerator.storePQCPrivateKeys(dirPath, password.toCharArray(), kyberKeyPair.getPrivate(), dilithiumKeyPair.getPrivate());
    }

    /**
     * Verifies if the password successfully decrypts the stored Kyber private key.
     */
    @Override
    public boolean checkPasswordUsingEncryptedPrivateKey(String password, String dirPath) {
        String kyberPrivateKeyFilePath = getPrivateKeyPath(dirPath, "kyber");

        return asymmetricKeysGenerator.checkPasswordUsingEncryptedPQCPrivateKey(kyberPrivateKeyFilePath, password.toCharArray());
    }

    /**
     * Configures the user properties file with specific metadata-encryption settings for PQC.
     */
    @Override
    public Properties enhancePropertiesIfNeeded(Properties properties, Account account) {
        CloudUserCreatingHandler cloudUserCreatingHandler = account.cloudUserCreatingHandler(properties);

        properties = cloudUserCreatingHandler.enhanceUserPropertiesIfNeeded(kyberKeyPair.getPublic());
        return properties;
    }

    /**
     * Builds and returns a UserMetadata instance populated with Kyber and Dilithium public keys in PEM format.
     */
    @Override
    public UserMetadata createUserMetadata(Account account) {
        UserMetadata userMetadata = new UserMetadata(account.MY_USER(),
                "user",
                account.ORGANIZATION());

        userMetadata.metadataEncryption_$eq(scala.Option.apply("PQC"));

        userMetadata.publicKyberKeyPEM_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(kyberKeyPair.getPublic(), null, null)));
        userMetadata.publicDilithiumKeyPEM_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(dilithiumKeyPair.getPublic(), null, null)));

        return userMetadata;
    }

    /**
     * Builds and returns a Cognito UserMetadata instance populated with Kyber and Dilithium public keys.
     */
    @Override
    public UserMetadata createCognitoUserMetadata(String userName, String email, String identityId, Account account) {
        UserMetadata userMetadata = new UserMetadata(userName,
                "user",
                account.ORGANIZATION());

        userMetadata.metadataEncryption_$eq(scala.Option.apply("PQC"));

        userMetadata.publicKyberKeyPEM_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(kyberKeyPair.getPublic(), null, null)));
        userMetadata.publicDilithiumKeyPEM_$eq(scala.Option.apply(asymmetricKeysGenerator.convertObjectToPEM(dilithiumKeyPair.getPublic(), null, null)));

        userMetadata.emailAddress_$eq(scala.Option.apply(email));
        userMetadata.cognitoIdentityId_$eq(scala.Option.apply(identityId));

        return userMetadata;
    }

    /**
     * Reads and concatenates the Kyber and Dilithium public key PEMs.
     */
    @Override
    public String publicKeysToCopy(String dirPath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(getPublicKeyPath(dirPath, "kyber")))) + "\n" +
                new String(Files.readAllBytes(Paths.get(getPublicKeyPath(dirPath, "dilithium"))));
    }

    /**
     * Reads and concatenates the encrypted Kyber and Dilithium private key PEMs.
     */
    @Override
    public String privateKeysToCopy(String dirPath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(getPrivateKeyPath(dirPath, "kyber")))) + "\n" +
                new String(Files.readAllBytes(Paths.get(getPrivateKeyPath(dirPath, "dilithium"))));
    }
}
