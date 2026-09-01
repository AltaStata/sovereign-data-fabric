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

package com.altastata.licensing

import com.altastata.crypto.{CertTrustAnchor, LicenseTrustAnchor}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.io.{File, FileWriter}
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(classOf[JUnitRunner])
class OrgLicenseSpec extends AnyFunSuite {

  private def generateRsaKeyPair() = {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  }

  test("sign and verify round-trip") {
    val keyPair = generateRsaKeyPair()
    val expiresAt = Instant.now().plus(90, ChronoUnit.DAYS)
    val jwt = OrgLicense.sign(
      organizationId = "myorgrsa",
      tier = "enterprise",
      features = Seq("core", "pqc", "hsm"),
      seats = 100,
      expiresAt = expiresAt,
      issuerPrivateKey = keyPair.getPrivate
    )
    val license = OrgLicense.verify(jwt, "myorgrsa", keyPair.getPublic)
    assert(license.tier === "enterprise")
    assert(license.features === Set("core", "pqc", "hsm"))
    assert(license.seats === 100)
    assert(license.hasFeature("pqc"))
  }

  test("reject org mismatch") {
    val keyPair = generateRsaKeyPair()
    val jwt = OrgLicense.sign(
      "myorgrsa", "standard", Seq("core"), 5,
      Instant.now().plus(30, ChronoUnit.DAYS), keyPair.getPrivate
    )
    intercept[SecurityException] {
      OrgLicense.verify(jwt, "otherorg", keyPair.getPublic)
    }
  }

  test("embedded license trust anchor rejects customer self-signed JWT") {
    val customerKey = generateRsaKeyPair()
    val altaStataKey = generateRsaKeyPair()
    val selfSignedJwt = OrgLicense.sign(
      "myorgrsa", "enterprise", Seq("core", "custodian"), 1000,
      Instant.now().plus(365, ChronoUnit.DAYS), customerKey.getPrivate
    )
    intercept[SecurityException] {
      OrgLicense.verify(selfSignedJwt, "myorgrsa", altaStataKey.getPublic)
    }
  }

  test("CommunityGrant defaults") {
    val grant = CommunityGrant.defaultForOrganization("myorgrsa")
    assert(grant.tier === CommunityGrant.Tier)
    assert(grant.seats === CommunityGrant.MaxSeats)
    assert(grant.features === CommunityGrant.Features)
    assert(!grant.hasFeature("pqc"))
    assert(CommunityGrant.MaxCustodians === 1)
    assert(CommunityGrant.isCustodianUserName("orgcustodian"))
    assert(CommunityGrant.isCustodianUserName("BobCustodian"))
    assert(!CommunityGrant.isCustodianUserName("bob"))
  }

  test("embedded certificate and license trust anchors use the same RSA-4096 key") {
    val certificateKey = CertTrustAnchor.communityIssuerPublicKey
    val licenseKey = LicenseTrustAnchor.licenseIssuerPublicKey

    assert(certificateKey.getEncoded sameElements licenseKey.getEncoded)
    assert(certificateKey.asInstanceOf[RSAPublicKey].getModulus.bitLength() === 4096)
  }

  test("CertTrustAnchor loads org-ca.pem from account folder when licensed") {
    val tmp = Files.createTempDirectory("altastata-lic").toFile
    try {
      val orgCa = new File(tmp, CertTrustAnchor.OrgCaPemFileName)
      val pemText = scala.io.Source.fromResource("com/altastata/crypto/altastata-issuer-public.pem").mkString.trim
      val writer = new FileWriter(orgCa)
      try writer.write(pemText) finally writer.close()

      val fromAccount = CertTrustAnchor.resolveCertTrustPublicKey(tmp, licensed = true)
      val community = CertTrustAnchor.resolveCertTrustPublicKey(tmp, licensed = false)
      assert(fromAccount.getEncoded sameElements CertTrustAnchor.communityIssuerPublicKey.getEncoded)
      assert(community.getEncoded sameElements CertTrustAnchor.communityIssuerPublicKey.getEncoded)
    } finally {
      orgCaDeleteRecursive(tmp)
    }
  }

  test("CertTrustAnchor requires org-ca.pem when licensed") {
    val tmp = Files.createTempDirectory("altastata-lic-missing").toFile
    try {
      intercept[IllegalStateException] {
        CertTrustAnchor.resolveCertTrustPublicKey(tmp, licensed = true)
      }
    } finally {
      orgCaDeleteRecursive(tmp)
    }
  }

  private def orgCaDeleteRecursive(file: File): Unit = {
    if (file.isDirectory) file.listFiles().foreach(orgCaDeleteRecursive)
    file.delete()
  }
}
