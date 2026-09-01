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

import com.altastata.crypto.X509.loadPQCPublicKey
import com.altastata.utils.Account

import java.io.File
import java.nio.file.{Files, Paths}
import java.security.{KeyPair, KeyPairGenerator, SecureRandom}
import java.util.Base64

object DilithiumSignatureExample {

  def generateKeyPair(): KeyPair = {
    PqcAlgorithms.ensureProviderRegistered()
    val keyPairGenerator = KeyPairGenerator.getInstance(PqcAlgorithms.SignatureAlgorithm, PqcAlgorithms.Provider)
    keyPairGenerator.initialize(PqcAlgorithms.SignatureParameterSpec, new SecureRandom())
    keyPairGenerator.generateKeyPair()
  }

  def main(args: Array[String]): Unit = {
    PqcAlgorithms.ensureProviderRegistered()

    val account: Account = new Account()

    val accountPath = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "azure.pqc.bob456"

    account.loadAccountProperties(accountPath)
    account.setPassword("123".toCharArray())

    val message = "bob123/08683/03402/273d45d9-4ff2-4534-a40a-899f6f5fb216".getBytes

    val signature = AsymmetricKeysGenerator.signWithDilithium(message)(account)

    println("Signature length: " + signature.length)
    println("Signature: " + signature.map("%02X".format(_)).mkString)

    val encodedSignature = Base64.getEncoder().encodeToString(signature)

    val dilithiumPublicFilePath = accountPath + "/dilithium_public.key"
    val publicKey = loadPQCPublicKey("DILITHIUM", new String(Files.readAllBytes(Paths.get(dilithiumPublicFilePath))))

    val decodedSignature = Base64.getDecoder().decode(encodedSignature)

    val isValid = AsymmetricKeysGenerator.verifySignatureWithDilithium(publicKey, message, decodedSignature)

    println("isValid: " + isValid)
  }
}
