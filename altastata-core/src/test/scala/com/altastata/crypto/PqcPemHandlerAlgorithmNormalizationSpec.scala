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

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.io.File
import java.nio.file.{Files, Paths}
import java.security.{KeyPairGenerator, SecureRandom, Signature}

@RunWith(classOf[JUnitRunner])
class PqcPemHandlerAlgorithmNormalizationSpec extends AnyFunSuite {

  private val pem = new PEMHandler {}
  private val keys = AsymmetricKeysGenerator

  test("PEMHandler restores Kyber keys for multiple algorithm casings") {
    PqcAlgorithms.ensureProviderRegistered()

    val kyberKpg = KeyPairGenerator.getInstance(PqcAlgorithms.KemAlgorithm, PqcAlgorithms.Provider)
    kyberKpg.initialize(PqcAlgorithms.KemParameterSpec, new SecureRandom())
    val kyberPair = kyberKpg.generateKeyPair()

    val priv = kyberPair.getPrivate.getEncoded
    val pub = kyberPair.getPublic.getEncoded

    assert(pem.getPQCPrivateKeyFromEncoded("KYBER", priv).nonEmpty)
    assert(pem.getPQCPrivateKeyFromEncoded("Kyber", priv).nonEmpty)
    assert(pem.getPQCPrivateKeyFromEncoded(" kyber ", priv).nonEmpty)

    assert(pem.getPQCPublicKeyFromEncoded("KYBER", pub).nonEmpty)
    assert(pem.getPQCPublicKeyFromEncoded("Kyber", pub).nonEmpty)
    assert(pem.getPQCPublicKeyFromEncoded(" kyber ", pub).nonEmpty)
  }

  test("PEMHandler restores Dilithium keys for multiple algorithm casings") {
    PqcAlgorithms.ensureProviderRegistered()

    val dilithiumKpg = KeyPairGenerator.getInstance(PqcAlgorithms.SignatureAlgorithm, PqcAlgorithms.Provider)
    dilithiumKpg.initialize(PqcAlgorithms.SignatureParameterSpec, new SecureRandom())
    val dilithiumPair = dilithiumKpg.generateKeyPair()

    val priv = dilithiumPair.getPrivate.getEncoded
    val pub = dilithiumPair.getPublic.getEncoded

    assert(pem.getPQCPrivateKeyFromEncoded("DILITHIUM", priv).nonEmpty)
    assert(pem.getPQCPrivateKeyFromEncoded("Dilithium", priv).nonEmpty)
    assert(pem.getPQCPrivateKeyFromEncoded(" dilithium ", priv).nonEmpty)

    assert(pem.getPQCPublicKeyFromEncoded("DILITHIUM", pub).nonEmpty)
    assert(pem.getPQCPublicKeyFromEncoded("Dilithium", pub).nonEmpty)
    assert(pem.getPQCPublicKeyFromEncoded(" dilithium ", pub).nonEmpty)
  }

  test("generateAndSavePQCKeys writes keys that round-trip through PEM load, KEM, and signature") {
    PqcAlgorithms.ensureProviderRegistered()

    val dir = Files.createTempDirectory("pqc-keys-").toFile
    try {
      val password = "test-pqc-password".toCharArray
      val (kyberPair, dilithiumPair) = keys.generateAndSavePQCKeys(dir.getAbsolutePath, password)

      val expectedFiles = Seq(
        "kyber_public.key",
        "dilithium_public.key",
        "kyber_private.key",
        "dilithium_private.key"
      )
      expectedFiles.foreach { name =>
        val file = new File(dir, name)
        assert(file.exists(), s"Expected key file missing: $name")
        assert(file.length() > 0, s"Expected non-empty key file: $name")
      }

      val kyberPublicPem = new String(Files.readAllBytes(Paths.get(dir.getAbsolutePath, "kyber_public.key")))
      val dilithiumPublicPem = new String(Files.readAllBytes(Paths.get(dir.getAbsolutePath, "dilithium_public.key")))
      val kyberPrivatePem = new String(Files.readAllBytes(Paths.get(dir.getAbsolutePath, "kyber_private.key")))
      val dilithiumPrivatePem = new String(Files.readAllBytes(Paths.get(dir.getAbsolutePath, "dilithium_private.key")))

      val loadedKyberPublic = keys.loadPQCPublicKey("KYBER", kyberPublicPem)
      val loadedDilithiumPublic = keys.loadPQCPublicKey("DILITHIUM", dilithiumPublicPem)
      val loadedKyberPrivate = keys.loadPQCPrivateKeyFromString("KYBER", password, kyberPrivatePem)
      val loadedDilithiumPrivate = keys.loadPQCPrivateKeyFromString("DILITHIUM", password, dilithiumPrivatePem)

      assert(loadedKyberPublic.getEncoded.sameElements(kyberPair.getPublic.getEncoded))
      assert(loadedDilithiumPublic.getEncoded.sameElements(dilithiumPair.getPublic.getEncoded))
      assert(loadedKyberPrivate.getEncoded.sameElements(kyberPair.getPrivate.getEncoded))
      assert(loadedDilithiumPrivate.getEncoded.sameElements(dilithiumPair.getPrivate.getEncoded))

      val plaintext = "pqc round-trip payload".getBytes("UTF-8")
      val (ciphertext, encapsulation) = keys.encryptKyber(loadedKyberPublic, plaintext)
      val decrypted = keys.decryptKyber(ciphertext, encapsulation, loadedKyberPrivate)(null)
      assert(decrypted.sameElements(plaintext))

      val signature = Signature.getInstance(PqcAlgorithms.SignatureAlgorithm, PqcAlgorithms.Provider)
      signature.initSign(loadedDilithiumPrivate)
      signature.update(plaintext)
      val sigBytes = signature.sign()

      assert(keys.verifySignatureWithDilithium(loadedDilithiumPublic, plaintext, sigBytes))
    } finally {
      expectedFilesSafeDelete(dir)
    }
  }

  private def expectedFilesSafeDelete(dir: File): Unit = {
    Option(dir.listFiles()).foreach(_.foreach(_.delete()))
    dir.delete()
  }
}
