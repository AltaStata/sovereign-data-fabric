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

import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import org.bouncycastle.pqc.jcajce.spec.SPHINCSPlusParameterSpec

import java.security.{KeyPair, KeyPairGenerator, Security, Signature}

object SPHINCSPlusSignatureExample {
  // Add the Bouncy Castle Provider
  Security.addProvider(new BouncyCastlePQCProvider())

  def main(args: Array[String]): Unit = {
    // Use a smaller parameter set for smaller key size (e.g., sha2_128s)
    val keyPairGenerator = KeyPairGenerator.getInstance("SPHINCSPlus", "BCPQC")
    keyPairGenerator.initialize(SPHINCSPlusParameterSpec.sha2_128s) // Choose a smaller parameter set
    val keyPair: KeyPair = keyPairGenerator.generateKeyPair()
    
    val privateKey = keyPair.getPrivate
    val publicKey = keyPair.getPublic

    // Message to sign
    val message = "Secure message".getBytes("UTF-8")

    // Create a Signature instance for SPHINCS+
    val signatureInstance = Signature.getInstance("SPHINCSPlus", "BCPQC")

    // Sign the message
    signatureInstance.initSign(privateKey)
    signatureInstance.update(message)
    val signature = signatureInstance.sign()

    println("signature lenght: " + signature.length)
    println("signature: " + signature.map("%02X".format(_)).mkString)

    // Verify the signature
    val verifyInstance = Signature.getInstance("SPHINCSPlus", "BCPQC")
    verifyInstance.initVerify(publicKey)
    verifyInstance.update(message)
    val isValid = verifyInstance.verify(signature)

    println(s"Signature valid: $isValid")
  }
}
