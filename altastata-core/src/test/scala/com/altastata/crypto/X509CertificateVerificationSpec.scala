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

import org.bouncycastle.cert.CertException
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.security.{KeyPair, KeyPairGenerator}

@RunWith(classOf[JUnitRunner])
class X509CertificateVerificationSpec extends AnyFunSuite {

  private def generateRsaKeyPair(): KeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()
  }

  test("PQC certificate signed by an untrusted key is rejected") {
    val trustedIssuer = generateRsaKeyPair()
    val rogueIssuer = generateRsaKeyPair()
    val embeddedKyberKey = generateRsaKeyPair()
    val embeddedDilithiumKey = generateRsaKeyPair()

    val certificate = X509.signPEMsWithCertificate(
      X509.convertObjectToPEM(embeddedKyberKey.getPublic),
      X509.convertObjectToPEM(embeddedDilithiumKey.getPublic),
      rogueIssuer.getPrivate,
      "victim"
    )("certificate")

    assertThrows[CertException] {
      X509.extractPEMsFromCertificate(
        certificate,
        trustedIssuer.getPublic,
        "victim",
        checkEndDate = false,
        enforceAltaStataIssuerCn = true
      )
    }
  }

  test("PQC certificate signed by the trusted key is accepted") {
    val trustedIssuer = generateRsaKeyPair()
    val embeddedKyberKey = generateRsaKeyPair()
    val embeddedDilithiumKey = generateRsaKeyPair()
    val kyberPem = X509.convertObjectToPEM(embeddedKyberKey.getPublic)
    val dilithiumPem = X509.convertObjectToPEM(embeddedDilithiumKey.getPublic)

    val certificate = X509.signPEMsWithCertificate(
      kyberPem,
      dilithiumPem,
      trustedIssuer.getPrivate,
      "victim"
    )("certificate")

    val extracted = X509.extractPEMsFromCertificate(
      certificate,
      trustedIssuer.getPublic,
      "victim",
      checkEndDate = true,
      enforceAltaStataIssuerCn = true
    )

    assert(extracted._1 === kyberPem)
    assert(extracted._2 === dilithiumPem)
  }

  test("RSA certificate signed by an untrusted key is rejected") {
    val trustedIssuer = generateRsaKeyPair()
    val rogueIssuer = generateRsaKeyPair()
    val userKey = generateRsaKeyPair()

    val certificate = X509.signPublicKeyWithCertificate(
      userKey.getPublic,
      rogueIssuer.getPrivate,
      "victim",
      durationInYears = 1
    )("certificate")

    assertThrows[CertException] {
      X509.extractPublicKeyFromCertificate(
        certificate,
        trustedIssuer.getPublic,
        "victim",
        checkEndDate = false,
        enforceAltaStataIssuerCn = true
      )
    }
  }

  test("RSA certificate signed by the trusted key is accepted") {
    val trustedIssuer = generateRsaKeyPair()
    val userKey = generateRsaKeyPair()

    val certificate = X509.signPublicKeyWithCertificate(
      userKey.getPublic,
      trustedIssuer.getPrivate,
      "victim",
      durationInYears = 1
    )("certificate")

    val extracted = X509.extractPublicKeyFromCertificate(
      certificate,
      trustedIssuer.getPublic,
      "victim",
      checkEndDate = true,
      enforceAltaStataIssuerCn = true
    )

    assert(extracted._1 === userKey.getPublic)
  }

  test("trusted signature with a non-community issuer CN is rejected when enforcement is enabled") {
    val trustedIssuer = generateRsaKeyPair()
    val userKey = generateRsaKeyPair()

    val certificate = X509.signPublicKeyWithCertificate(
      userKey.getPublic,
      trustedIssuer.getPrivate,
      "victim",
      durationInYears = 1,
      issuerName = "OtherCA"
    )("certificate")

    assertThrows[CertException] {
      X509.extractPublicKeyFromCertificate(
        certificate,
        trustedIssuer.getPublic,
        "victim",
        checkEndDate = true,
        enforceAltaStataIssuerCn = true
      )
    }
  }
}
