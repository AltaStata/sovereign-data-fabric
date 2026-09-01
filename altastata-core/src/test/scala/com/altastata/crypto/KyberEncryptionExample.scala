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

import com.altastata.crypto.X509.{loadPQCPrivateKey, loadPQCPublicKey}
import com.altastata.utils.Account

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security._
import java.util.Base64

object KyberEncryptionExample {

  def generateKeyPair(): KeyPair = {
    PqcAlgorithms.ensureProviderRegistered()
    val keyPairGenerator = KeyPairGenerator.getInstance(PqcAlgorithms.KemAlgorithm, PqcAlgorithms.Provider)
    keyPairGenerator.initialize(PqcAlgorithms.KemParameterSpec, new SecureRandom())
    keyPairGenerator.generateKeyPair()
  }

  def main(args: Array[String]): Unit = {
    PqcAlgorithms.ensureProviderRegistered()

    val account: Account = new Account()

    val accountPath = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "azure.pqc.bob456"

    account.loadAccountProperties(accountPath)
    account.setPassword("123".toCharArray())

    val kyberPublicFilePath = accountPath + "/kyber_public.key"
    val publicKey = loadPQCPublicKey("KYBER", new String(Files.readAllBytes(Paths.get(kyberPublicFilePath))))

    val originalText = "sig=ATKuIBDbolB7dX%2F9FoQkl2AEaM4auQut%2FjyzSpgtdX0%3D&st=2024-12-10T00%3A50%3A22Z&se=2034-12-10T00%3A50%3A22Z&sv=2018-11-09&sp=rwdl&sr=c"
    println(s"\nOriginal Text: $originalText")

    val (encryptedData, encapsulation) = AsymmetricKeysGenerator.encryptKyber(publicKey, originalText.getBytes())

    val decryptedText = new String(AsymmetricKeysGenerator.decryptKyber(encryptedData, encapsulation)(account))

    println(s"Decrypted Text: $decryptedText\n")

    println(s"Run encryptArrayWithKyber() and decryptArrayWithKyber()")

    object SecurityUtilsTest extends SecurityUtils {
    }

    val cyberText = SecurityUtilsTest.encryptArrayWithKyber(originalText.getBytes(), publicKey)
    val encodedCyberText = Base64.getEncoder().encodeToString(cyberText)

    println("encodedCyberText: " + encodedCyberText)

    val decodedCyberText = Base64.getDecoder().decode(encodedCyberText.getBytes(StandardCharsets.UTF_8))
    val plainText = new String(SecurityUtilsTest.decryptArrayWithKyber(decodedCyberText)(account))

    println("result: " + plainText)
  }
}
