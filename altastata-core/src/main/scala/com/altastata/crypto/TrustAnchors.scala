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

import org.slf4j.LoggerFactory

import java.io.{File, FileReader}
import java.security.PublicKey
import scala.io.Source

/**
 * AltaStata issuer public key embedded in the JAR.
 *
 * Same PEM may be shipped as `org-ca.pem` in eval kits; runtime reads it from the account folder
 * when `license.jwt` is present (see [[CertTrustAnchor]]).
 */
private[crypto] object EmbeddedIssuerKey extends PEMHandler {

  private val EmbeddedResource = "/com/altastata/crypto/altastata-issuer-public.pem"

  lazy val publicKey: PublicKey = {
    val stream = Option(getClass.getResourceAsStream(EmbeddedResource))
      .getOrElse(throw new IllegalStateException(s"Missing embedded issuer key: $EmbeddedResource"))
    val pem = try {
      Source.fromInputStream(stream, "UTF-8").mkString
    } finally {
      stream.close()
    }
    extractRSAPublicKeyFromPEM(pem)
  }

  lazy val pemText: String = {
    val stream = Option(getClass.getResourceAsStream(EmbeddedResource))
      .getOrElse(throw new IllegalStateException(s"Missing embedded issuer key: $EmbeddedResource"))
    try {
      Source.fromInputStream(stream, "UTF-8").mkString.trim
    } finally {
      stream.close()
    }
  }
}

/** Trust anchor for org `license.jwt` verification — embedded AltaStata key only. */
object LicenseTrustAnchor {

  lazy val licenseIssuerPublicKey: PublicKey = EmbeddedIssuerKey.publicKey
}

/**
 * Trust anchor for user identity certificate verification.
 *
 * - **Community** (no valid org license): embedded issuer key in JAR.
 * - **Licensed** (eval / enterprise): `org-ca.pem` in the account folder (required).
 */
object CertTrustAnchor extends PEMHandler {

  val OrgCaPemFileName = "org-ca.pem"

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Retrieves the default public key for the Community edition.
   *
   * @return The RSA public key of the embedded AltaStata certificate authority.
   */
  def communityIssuerPublicKey: PublicKey = EmbeddedIssuerKey.publicKey

  /**
   * Resolves the appropriate certificate trust public key based on whether the account is licensed.
   *
   * @param accountDir The directory path of the user account.
   * @param licensed   True if the account holds an enterprise/licensed organization status.
   * @return The RSA public key to use for validating user certificates.
   * @throws IllegalStateException if a licensed account lacks the required 'org-ca.pem' file.
   */
  def resolveCertTrustPublicKey(accountDir: File, licensed: Boolean): PublicKey = {
    if (licensed) {
      val orgCa = new File(accountDir, OrgCaPemFileName)
      if (!orgCa.isFile) {
        throw new IllegalStateException(
          s"Licensed account requires ${OrgCaPemFileName} in ${accountDir.getAbsolutePath}"
        )
      }
      logger.info(s"Loading user-cert trust anchor from ${orgCa.getAbsolutePath}")
      extractRSAPublicKeyFromPEM(new FileReader(orgCa))
    } else {
      logger.debug("Using embedded AltaStata issuer key for Community user-cert verification")
      EmbeddedIssuerKey.publicKey
    }
  }
}
