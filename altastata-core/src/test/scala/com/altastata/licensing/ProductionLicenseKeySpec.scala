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

import com.altastata.crypto.{LicenseTrustAnchor, PEMHandler}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.io.FileReader
import java.security.KeyPair
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Verifies local {@code altastata_private.key} matches embedded license issuer (if key file present). */
@RunWith(classOf[JUnitRunner])
class ProductionLicenseKeySpec extends AnyFunSuite with PEMHandler {

  private val issuerKeyFile = findIssuerKey()

  private def findIssuerKey(): java.io.File = {
    Option(System.getenv("ALTASTATA_ISSUER_PRIVATE_KEY")).filter(_.nonEmpty).map(new java.io.File(_)).find(_.isFile).getOrElse {
      var dir = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile
      while (dir != null) {
        val hit = Option(dir.listFiles()).getOrElse(Array.empty).find { child =>
          child.isDirectory && new java.io.File(child, "altastata_private.key").isFile
        }
        if (hit.isDefined) return new java.io.File(hit.get, "altastata_private.key")
        dir = dir.getParentFile
      }
      new java.io.File("altastata_private.key")
    }
  }

  test("altastata_private.key signs JWT verifiable by embedded LicenseTrustAnchor") {
    assume(issuerKeyFile.isFile, s"skip: ${issuerKeyFile.getAbsolutePath} not found")

    val keyPair: KeyPair = getKeyPairFromRSAPrivateKey(new FileReader(issuerKeyFile), null)
    val jwt = OrgLicense.sign(
      organizationId = "altastata-myorgrsa444",
      tier = "eval",
      features = Seq("core", "pqc", "hsm"),
      seats = 10,
      expiresAt = Instant.now().plus(90, ChronoUnit.DAYS),
      issuerPrivateKey = keyPair.getPrivate
    )
    val license = OrgLicense.verify(jwt, "altastata-myorgrsa444", LicenseTrustAnchor.licenseIssuerPublicKey)
    assert(license.tier === "eval")
    assert(license.hasFeature("pqc"))
  }
}
