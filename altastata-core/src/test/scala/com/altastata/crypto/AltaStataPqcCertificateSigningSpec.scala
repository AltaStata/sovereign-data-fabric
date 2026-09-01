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

import java.io.{File, FileReader}
import java.nio.file.Files
import java.security.Signature

@RunWith(classOf[JUnitRunner])
class AltaStataPqcCertificateSigningSpec extends AnyFunSuite with PEMHandler {

  private val keys = AsymmetricKeysGenerator

  test("AltaStata issuer key matches embedded community trust anchor") {
    PqcAlgorithms.ensureProviderRegistered()

    val issuerKeyFile = findIssuerKey()
    assume(issuerKeyFile.isFile, s"skip: issuer private key not found (set ALTASTATA_ISSUER_PRIVATE_KEY)")

    val issuerKeyPair = getKeyPairFromRSAPrivateKey(new FileReader(issuerKeyFile), null)
    assert(
      issuerKeyPair.getPublic.getEncoded.sameElements(CertTrustAnchor.communityIssuerPublicKey.getEncoded),
      "Local issuer private key does not match embedded AltaStata community public key"
    )
  }

  test("AltaStata-signed PQC certificate embeds ML-KEM/ML-DSA keys and verifies end-to-end") {
    PqcAlgorithms.ensureProviderRegistered()

    val issuerKeyFile = findIssuerKey()
    assume(issuerKeyFile.isFile, s"skip: issuer private key not found (set ALTASTATA_ISSUER_PRIVATE_KEY)")

    val organization = "amazon-pqc"
    val userName = "bob456"
    val subjectName = CertSubject.forUser(CertSubject.accountContainerPrefix(organization), userName)

    val dir = Files.createTempDirectory("pqc-cert-sign-").toFile
    try {
      val password = "cert-sign-test".toCharArray
      val (kyberPair, dilithiumPair) = keys.generateAndSavePQCKeys(dir.getAbsolutePath, password)

      val kyberPublicPem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir.getAbsolutePath, "kyber_public.key")))
      val dilithiumPublicPem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir.getAbsolutePath, "dilithium_public.key")))

      val certMap = createPEMCertificateForPQCPEMS(
        kyberPublicPem,
        dilithiumPublicPem,
        new FileReader(issuerKeyFile),
        subjectName,
        durationInYears = 1
      )

      val certificatePem = certMap.get("certificate")
      assert(certificatePem != null && certificatePem.nonEmpty, "Expected non-empty signed certificate PEM")

      val extracted = X509.extractPEMsFromCertificate(
        certificatePem,
        CertTrustAnchor.communityIssuerPublicKey,
        subjectName,
        checkEndDate = true,
        enforceAltaStataIssuerCn = true
      )

      assert(extracted._1.contains("PUBLIC KYBER"))
      assert(extracted._2.contains("PUBLIC DILITHIUM"))

      val restoredKyberPublic = keys.loadPQCPublicKey("KYBER", extracted._1)
      val restoredDilithiumPublic = keys.loadPQCPublicKey("DILITHIUM", extracted._2)

      assert(restoredKyberPublic.getEncoded.sameElements(kyberPair.getPublic.getEncoded))
      assert(restoredDilithiumPublic.getEncoded.sameElements(dilithiumPair.getPublic.getEncoded))

      val kyberPrivatePem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir.getAbsolutePath, "kyber_private.key")))
      val dilithiumPrivatePem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir.getAbsolutePath, "dilithium_private.key")))
      val kyberPrivate = keys.loadPQCPrivateKeyFromString("KYBER", password, kyberPrivatePem)
      val dilithiumPrivate = keys.loadPQCPrivateKeyFromString("DILITHIUM", password, dilithiumPrivatePem)

      val plaintext = s"AltaStata PQC cert for $subjectName".getBytes("UTF-8")
      val (ciphertext, encapsulation) = keys.encryptKyber(restoredKyberPublic, plaintext)
      val decrypted = keys.decryptKyber(ciphertext, encapsulation, kyberPrivate)(null)
      assert(decrypted.sameElements(plaintext))

      val signature = Signature.getInstance(PqcAlgorithms.SignatureAlgorithm, PqcAlgorithms.Provider)
      signature.initSign(dilithiumPrivate)
      signature.update(plaintext)
      val sigBytes = signature.sign()
      assert(keys.verifySignatureWithDilithium(restoredDilithiumPublic, plaintext, sigBytes))
    } finally {
      Option(dir.listFiles()).foreach(_.foreach(_.delete()))
      dir.delete()
    }
  }

  private def findIssuerKey(): File = {
    Option(System.getenv("ALTASTATA_ISSUER_PRIVATE_KEY")).filter(_.nonEmpty).map(new File(_)).find(_.isFile).getOrElse {
      var dir = new File(System.getProperty("user.dir")).getAbsoluteFile
      while (dir != null) {
        val hit = Option(dir.listFiles()).getOrElse(Array.empty).find { child =>
          child.isDirectory && new File(child, "altastata_private.key").isFile
        }
        if (hit.isDefined) return new File(hit.get, "altastata_private.key")
        dir = dir.getParentFile
      }
      new File("altastata_private.key")
    }
  }
}
