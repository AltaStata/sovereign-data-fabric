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
import org.bouncycastle.pqc.jcajce.spec.FalconParameterSpec
import java.security.{KeyPair, KeyPairGenerator, Security, Signature}

object FalconSignatureExample {
  // Add Bouncy Castle Provider
  Security.addProvider(new BouncyCastlePQCProvider())

  def main(args: Array[String]): Unit = {
    // Generate a Falcon key pair
    val keyPairGenerator = KeyPairGenerator.getInstance("Falcon", "BCPQC")
    // Initialize with appropriate parameter spec (e.g., Falcon512)
    keyPairGenerator.initialize(FalconParameterSpec.falcon_512) // You can change this to Falcon1024
    val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

    val privateKey = keyPair.getPrivate
    val publicKey = keyPair.getPublic

    // Message to sign
    val message = "Secure message".getBytes("UTF-8")

    // Create a Signature instance for Falcon
    val signatureInstance = Signature.getInstance("Falcon", "BCPQC")

    // Sign the message
    signatureInstance.initSign(privateKey)
    signatureInstance.update(message)
    val signature = signatureInstance.sign()

    println("Signature length: " + signature.length)
    println("Signature: " + signature.map("%02X".format(_)).mkString)

    // Verify the signature
    val verifyInstance = Signature.getInstance("Falcon", "BCPQC")
    verifyInstance.initVerify(publicKey)
    verifyInstance.update(message)
    val isValid = verifyInstance.verify(signature)

    println(s"Signature valid: $isValid")
  }
}
