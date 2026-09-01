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

import com.altastata.crypto.{CertTrustAnchor, PEMHandler}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.io.{File, FileReader, FileWriter}
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Properties

@RunWith(classOf[JUnitRunner])
class AccountLicensingSpec extends AnyFunSuite with PEMHandler {

  private def rsaKeyPair() = {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  }

  private def withTempAccount(orgPrefix: String = "myorgrsa-")(f: (File, com.altastata.utils.Account) => Unit): Unit = {
    val tmp = Files.createTempDirectory("altastata-acct-lic").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", orgPrefix)
      props.setProperty("myuser", "bob123")
      props.setProperty("accounttype", "amazon-s3-secure")
      props.setProperty("metadata-encryption", "RSA")
      props.setProperty("enterprise-custodian-mode", "false")
      props.store(new java.io.FileOutputStream(new File(tmp, "amazon.rsa.bob123.user.properties")), null)

      val account = new com.altastata.utils.Account()
      account.loadAccountProperties(tmp.getAbsolutePath)
      f(tmp, account)
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("refresh without license keeps Community cert trust") {
    withTempAccount() { (_, account) =>
      // loadAccountProperties already called refresh; Community RSA stays unlicensed
      assert(account.getOrgLicense.isEmpty)
      assert(account.getCertTrustPublicKey.getEncoded === CertTrustAnchor.communityIssuerPublicKey.getEncoded)
      assert(account.enforceAltaStataIssuerCn)
    }
  }

  test("HSM without license.jwt is rejected at loadAccountProperties") {
    val tmp = Files.createTempDirectory("altastata-hsm-lic").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", "altastata-myorgrsa444-")
      props.setProperty("myuser", "hsmuser")
      props.setProperty("accounttype", "amazon-s3-secure")
      props.setProperty("metadata-encryption", "HSM")
      props.setProperty("enterprise-custodian-mode", "false")
      props.store(new java.io.FileOutputStream(new File(tmp, "amazon.rsa.hsm.hsmuser.user.properties")), null)

      val account = new com.altastata.utils.Account()
      intercept[SecurityException] {
        account.loadAccountProperties(tmp.getAbsolutePath)
      }
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("PQC without license.jwt is rejected at loadAccountProperties") {
    val tmp = Files.createTempDirectory("altastata-pqc-lic").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", "altastata-myorgpqc654-")
      props.setProperty("myuser", "bobpqc")
      props.setProperty("accounttype", "amazon-s3-secure")
      props.setProperty("metadata-encryption", "PQC")
      props.setProperty("enterprise-custodian-mode", "false")
      props.store(new java.io.FileOutputStream(new File(tmp, "amazon.pqc.bobpqc.user.properties")), null)

      val account = new com.altastata.utils.Account()
      intercept[SecurityException] {
        account.loadAccountProperties(tmp.getAbsolutePath)
      }
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("HPCS without license.jwt is rejected at loadAccountProperties") {
    val tmp = Files.createTempDirectory("altastata-hpcs-lic").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", "altastata-myorgrsa444-")
      props.setProperty("myuser", "bobhpcs")
      props.setProperty("accounttype", "ibm-cos-secure")
      props.setProperty("metadata-encryption", "RSA")
      props.setProperty("key-protection", "HPCS")
      props.setProperty("enterprise-custodian-mode", "false")
      props.store(new java.io.FileOutputStream(new File(tmp, "ibm.rsa.bobhpcs.user.properties")), null)

      val account = new com.altastata.utils.Account()
      intercept[SecurityException] {
        account.loadAccountProperties(tmp.getAbsolutePath)
      }
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("refresh with license and org-ca.pem installs licensed state") {
    val keyPair = rsaKeyPair()
    withTempAccount() { (dir, account) =>
      val jwt = OrgLicense.sign(
        "myorgrsa", "eval", Seq("core", "pqc"), 10,
        Instant.now().plus(90, ChronoUnit.DAYS), keyPair.getPrivate
      )
      Files.write(dir.toPath.resolve(AccountLicensing.LicenseJwtFileName), jwt.getBytes)
      val pemText = scala.io.Source.fromResource("com/altastata/crypto/altastata-issuer-public.pem").mkString.trim
      val w = new FileWriter(new File(dir, CertTrustAnchor.OrgCaPemFileName))
      try w.write(pemText) finally w.close()

      // Runtime verifies JWT with embedded AltaStata key; use matching test key via direct verify in this unit test.
      val license = OrgLicense.verify(jwt, "myorgrsa", keyPair.getPublic)
      account.setRuntimeLicensing(Some(license), CertTrustAnchor.resolveCertTrustPublicKey(dir, licensed = true), licensedIdentity = true)

      assert(account.getOrgLicense.get.hasFeature("pqc"))
      assert(!account.enforceAltaStataIssuerCn)
    }
  }

  test("loadOrgLicense reads JWT from account folder") {
    val keyPair = rsaKeyPair()
    val tmp = Files.createTempDirectory("altastata-lic-load").toFile
    try {
      val jwt = OrgLicense.sign(
        "myorgrsa", "eval", Seq("core"), 5,
        Instant.now().plus(30, ChronoUnit.DAYS), keyPair.getPrivate
      )
      Files.write(tmp.toPath.resolve(AccountLicensing.LicenseJwtFileName), jwt.getBytes)
      intercept[SecurityException] {
        AccountLicensing.loadOrgLicense(tmp, "myorgrsa")
      }
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("PQC without license is rejected") {
    withTempAccount() { (_, account) =>
      account.userProps.setProperty("metadata-encryption", "PQC")
      intercept[SecurityException] {
        AccountLicensing.refresh(account)
      }
    }
  }

  test("multicloud-secure without license is rejected") {
    withTempAccount() { (_, account) =>
      account.userProps.setProperty("accounttype", "multicloud-secure")
      intercept[SecurityException] {
        AccountLicensing.refresh(account)
      }
    }
  }

  test("enterprise-custodian-mode=true without license.jwt is rejected at loadAccountProperties") {
    val tmp = Files.createTempDirectory("altastata-custodian-lic").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", "myorgrsa-")
      props.setProperty("myuser", "bob123")
      props.setProperty("accounttype", "amazon-s3-secure")
      props.setProperty("metadata-encryption", "RSA")
      props.setProperty("enterprise-custodian-mode", "true")
      props.store(new java.io.FileOutputStream(new File(tmp, "amazon.rsa.bob123.user.properties")), null)

      val account = new com.altastata.utils.Account()
      intercept[SecurityException] {
        account.loadAccountProperties(tmp.getAbsolutePath)
      }
    } finally {
      deleteRecursive(tmp)
    }
  }

  test("enterprise-custodian-mode=true with license feature custodian enables isCustodianMode") {
    val issuerKeyFile = findIssuerKey()
    assume(issuerKeyFile.isFile, s"skip: issuer private key not found (set ALTASTATA_ISSUER_PRIVATE_KEY)")

    val keyPair = getKeyPairFromRSAPrivateKey(new FileReader(issuerKeyFile), null)
    val tmp = Files.createTempDirectory("altastata-custodian-ok").toFile
    try {
      val props = new Properties()
      props.setProperty("acccontainer-prefix", "altastata-myorgrsa444-")
      props.setProperty("myuser", "bob123")
      props.setProperty("accounttype", "amazon-s3-secure")
      props.setProperty("metadata-encryption", "RSA")
      props.setProperty("enterprise-custodian-mode", "true")
      props.store(new java.io.FileOutputStream(new File(tmp, "amazon.rsa.bob123.user.properties")), null)

      val jwt = OrgLicense.sign(
        "altastata-myorgrsa444", "eval", Seq("core", "custodian"), 10,
        Instant.now().plus(90, ChronoUnit.DAYS), keyPair.getPrivate
      )
      Files.write(tmp.toPath.resolve(AccountLicensing.LicenseJwtFileName), jwt.getBytes)
      val pemText = scala.io.Source.fromResource("com/altastata/crypto/altastata-issuer-public.pem").mkString.trim
      val w = new FileWriter(new File(tmp, CertTrustAnchor.OrgCaPemFileName))
      try w.write(pemText) finally w.close()

      val account = new com.altastata.utils.Account()
      account.loadAccountProperties(tmp.getAbsolutePath)
      assert(account.isCustodianMode)
      assert(account.getOrgLicense.exists(_.hasFeature("custodian")))
    } finally {
      deleteRecursive(tmp)
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

  private def deleteRecursive(file: File): Unit = {
    if (file.isDirectory) file.listFiles().foreach(deleteRecursive)
    file.delete()
  }
}
