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

package com.altastata.crypto

import com.altastata.crypto.X509.{extractPEMsFromCertificate, signPEMsWithCertificate}
import com.altastata.utils.Account

import java.io.File
import java.security.{KeyPairGenerator, PrivateKey, PublicKey}

object X508Test {

  def main(args: Array[String]): Unit = {

    PqcAlgorithms.ensureProviderRegistered()

    // Generate a 1024-bit RSA key pair
    val keyGen = KeyPairGenerator.getInstance("RSA")

    keyGen.initialize(1024)

    val keypair = keyGen.genKeyPair()

    val issuerPrivateKey: PrivateKey = keypair.getPrivate

    val kyberKeys = KyberEncryptionExample.generateKeyPair()
    val dilithiumKeys = DilithiumSignatureExample.generateKeyPair()

    val pemPublicKyber = AsymmetricKeysGenerator.byteArrayToPEM(kyberKeys.getPublic.getEncoded, "PUBLIC KYBER")

    val pemPublicDilithium = AsymmetricKeysGenerator.byteArrayToPEM(dilithiumKeys.getPublic.getEncoded, "PUBLIC DILITHIUM")

    val signedCertificatePEM = signPEMsWithCertificate(pemPublicKyber, pemPublicDilithium, issuerPrivateKey, "TestSubject")
    println(s"Signed Certificate PEM:\n$signedCertificatePEM")

    val issuerPublicKey: PublicKey = keypair.getPublic
    val extractedPEMs = extractPEMsFromCertificate(signedCertificatePEM.get("certificate").get, issuerPublicKey, "TestSubject")

    println(s"Extracted PEM 1: ${extractedPEMs._1}")
    println(s"Extracted PEM 2: ${extractedPEMs._2}")

    // Generate a 1024-bit RSA key pair
    keyGen.initialize(1024)
    val rsaKeypair = keyGen.genKeyPair()

    val rsaCertificatePEM = X509.signPublicKeyWithCertificate(rsaKeypair.getPublic, issuerPrivateKey, "Test", 1).get("certificate").get

    println("rsaCertificate: " + rsaCertificatePEM)

    val (restoredRsaPublicKey, _) = X509.extractPublicKeyFromCertificate(rsaCertificatePEM, issuerPublicKey, "Test", false)

    val account: Account = new Account()

    account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.pqc.bob456")
    account.setPassword("123".toCharArray())

    // PEM for Kyber Private Key
    val encryptedPEMForKyberPrivateKey = AES256WithPassword.encrypt(kyberKeys.getPrivate.getEncoded, "123")
    account.encryptedKyberPrivateKeyPEM = AsymmetricKeysGenerator.byteArrayToPEM(encryptedPEMForKyberPrivateKey, "PRIVATE KYBER")

    // Original message to encrypt
    val originalText = "This is a secret message."
    println(s"\nOriginal Text: $originalText")

    // Restrore Kyber Public Key from Certificate
    val restoredKyberPublicKey = AsymmetricKeysGenerator.getPQCPublicKeyFromEncoded("KYBER", AsymmetricKeysGenerator.pemToByteArray(extractedPEMs._1)).get
    val (encryptedData, encapsulation) = AsymmetricKeysGenerator.encryptKyber(restoredKyberPublicKey, originalText.getBytes())

    // TODO: set restoredKyberPrivateKey

    val decryptedText = new String(AsymmetricKeysGenerator.decryptKyber(encryptedData, encapsulation)(account))

    println(s"Decrypted Text: $decryptedText\n")
  }

}
