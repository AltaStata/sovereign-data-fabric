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

package com.altastata.crypto;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Arrays;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyGenerator;

public class PqcChrystalsKyberKem {

    private static final String PROVIDER = BouncyCastleProvider.PROVIDER_NAME;

    public static void main(String[] args) {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        String print = run(false);
        System.out.println(print);
    }

    public static String run(boolean truncateKeyOutput) {
        String out = "PQC ML-KEM KEM";
        out += "\n" + "\n************************************\n" +
                "* # # SERIOUS SECURITY WARNING # # *\n" +
                "* This program is a CONCEPT STUDY  *\n" +
                "* for the algorithm                *\n" +
                "* ML-KEM [key exchange mechanism]  *\n" +
                "* The program is using an          *\n" +
                "* parameter set that I cannot      *\n" +
                "* check for the correctness of the *\n" +
                "* output and other details         *\n" +
                "*                                  *\n" +
                "*    DO NOT USE THE PROGRAM IN     *\n" +
                "*    ANY PRODUCTION ENVIRONMENT    *\n" +
                "************************************";

        MLKEMParameterSpec[] mlKemParameterSpecs = {
                MLKEMParameterSpec.ml_kem_512,
                MLKEMParameterSpec.ml_kem_768,
                MLKEMParameterSpec.ml_kem_1024
        };
        String[] kemAlgorithms = {"ML-KEM-512", "ML-KEM-768", "ML-KEM-1024"};

        int nrOfSpecs = mlKemParameterSpecs.length;
        String[] parameterSpecName = new String[nrOfSpecs];
        int[] privateKeyLength = new int[nrOfSpecs];
        int[] publicKeyLength = new int[nrOfSpecs];
        int[] encapsulatedKeyLength = new int[nrOfSpecs];
        int[] sharedSecretLength = new int[nrOfSpecs];
        boolean[] sharedSecretsEqual = new boolean[nrOfSpecs];

        for (int i = 0; i < nrOfSpecs; i++) {
            MLKEMParameterSpec mlKemParameterSpec = mlKemParameterSpecs[i];
            String kemAlgorithm = kemAlgorithms[i];
            parameterSpecName[i] = mlKemParameterSpec.getName();

            KeyPair keyPair = generateMlKemKeyPair(kemAlgorithm, mlKemParameterSpec);
            privateKeyLength[i] = keyPair.getPrivate().getEncoded().length;
            publicKeyLength[i] = keyPair.getPublic().getEncoded().length;

            SecretKeyWithEncapsulation secEnc1 = pqcGenerateMlKemEncryptionKey(kemAlgorithm, keyPair.getPublic());
            encapsulatedKeyLength[i] = secEnc1.getEncapsulation().length;
            sharedSecretLength[i] = secEnc1.getEncoded().length;

            byte[] sharedSecret2 = pqcGenerateMlKemDecryptionKey(kemAlgorithm, keyPair.getPrivate(), secEnc1.getEncapsulation());
            sharedSecretsEqual[i] = Arrays.areEqual(secEnc1.getEncoded(), sharedSecret2);

            if (truncateKeyOutput) {
                out += "\n\nParameter set: " + parameterSpecName[i];
                out += "\nPrivate key length: " + privateKeyLength[i];
                out += "\nPublic key length: " + publicKeyLength[i];
                out += "\nEncapsulated key length: " + encapsulatedKeyLength[i];
                out += "\nShared secret length: " + sharedSecretLength[i];
                out += "\nShared secrets equal: " + sharedSecretsEqual[i];
            }
        }

        out += "\n\n****************************************";
        out += "\n* Summary                              *";
        out += "\n****************************************";
        for (int i = 0; i < nrOfSpecs; i++) {
            out += "\nParameter set: " + parameterSpecName[i];
            out += "\nPrivate key length: " + privateKeyLength[i];
            out += "\nPublic key length: " + publicKeyLength[i];
            out += "\nEncapsulated key length: " + encapsulatedKeyLength[i];
            out += "\nShared secret length: " + sharedSecretLength[i];
            out += "\nShared secrets equal: " + sharedSecretsEqual[i];
            out += "\n";
        }
        out += "****************************************\n";
        return out;
    }

    private static KeyPair generateMlKemKeyPair(String kemAlgorithm, MLKEMParameterSpec mlKemParameterSpec) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(kemAlgorithm, PROVIDER);
            kpg.initialize(mlKemParameterSpec, new SecureRandom());
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }

    public static SecretKeyWithEncapsulation pqcGenerateMlKemEncryptionKey(String kemAlgorithm, PublicKey publicKey) {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(kemAlgorithm, PROVIDER);
            keyGen.init(new KEMGenerateSpec(publicKey, "AES"), new SecureRandom());
            return (SecretKeyWithEncapsulation) keyGen.generateKey();
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] pqcGenerateMlKemDecryptionKey(String kemAlgorithm, PrivateKey privateKey, byte[] encapsulatedKey) {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(kemAlgorithm, PROVIDER);
            keyGen.init(new KEMExtractSpec(privateKey, encapsulatedKey, "AES"), new SecureRandom());
            SecretKeyWithEncapsulation secEnc2 = (SecretKeyWithEncapsulation) keyGen.generateKey();
            return secEnc2.getEncoded();
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }

    private static PrivateKey getMlKemPrivateKeyFromEncoded(String kemAlgorithm, byte[] encodedKey) {
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(encodedKey);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(kemAlgorithm, PROVIDER);
            return keyFactory.generatePrivate(pkcs8EncodedKeySpec);
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }

    private static PublicKey getMlKemPublicKeyFromEncoded(String kemAlgorithm, byte[] encodedKey) {
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(encodedKey);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(kemAlgorithm, PROVIDER);
            return keyFactory.generatePublic(x509EncodedKeySpec);
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
}
