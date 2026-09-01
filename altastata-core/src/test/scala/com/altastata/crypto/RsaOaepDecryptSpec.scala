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

import com.altastata.utils.Constants
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.security.{KeyPairGenerator, PrivateKey, PublicKey}
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.{OAEPParameterSpec, PSource}

@RunWith(classOf[JUnitRunner])
class RsaOaepDecryptSpec extends AnyFunSuite {

  private val oaepSha256 = new OAEPParameterSpec(
    "SHA-256",
    "MGF1",
    MGF1ParameterSpec.SHA256,
    PSource.PSpecified.DEFAULT
  )

  private def generateRsa(): (PublicKey, PrivateKey) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()
    (kp.getPublic, kp.getPrivate)
  }

  private def encrypt(pub: PublicKey, plain: Array[Byte]): Array[Byte] = {
    val cipher = Cipher.getInstance(Constants.RSA_OAEP)
    cipher.init(Cipher.ENCRYPT_MODE, pub, oaepSha256)
    cipher.doFinal(plain)
  }

  private def decrypt(priv: PrivateKey, cipherText: Array[Byte]): Array[Byte] = {
    val cipher = Cipher.getInstance(Constants.RSA_OAEP)
    cipher.init(Cipher.DECRYPT_MODE, priv, oaepSha256)
    cipher.doFinal(cipherText)
  }

  test("OAEP SHA-256 ciphertext round-trips") {
    val (pub, priv) = generateRsa()
    val plain = Array.fill[Byte](32)(7)
    val ct = encrypt(pub, plain)
    assert(decrypt(priv, ct) === plain)
  }
}
