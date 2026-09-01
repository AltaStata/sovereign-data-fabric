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

import com.altastata.crypto.{JwtRs256, LicenseTrustAnchor}

import java.security.{PrivateKey, PublicKey}
import java.time.Instant

case class OrgLicense(
  organizationId: String,
  tier: String,
  features: Set[String],
  seats: Int,
  issuedAt: Instant,
  expiresAt: Instant,
  productVersion: String
) {

  /**
   * Checks if this license includes the specified feature.
   *
   * @param feature the feature key/name
   * @return true if the feature is enabled; false otherwise
   */
  def hasFeature(feature: String): Boolean = features.contains(feature)

  /**
   * Checks if the license is expired at the specified instant.
   *
   * @param at the reference timestamp (defaults to now)
   * @return true if expired; false otherwise
   */
  def isExpired(at: Instant = Instant.now()): Boolean = expiresAt.isBefore(at)
}

object OrgLicense {

  val DefaultIssuer = "https://altastata.com"
  val DefaultProductVersion = "core:1.x"

  /**
   * Serializes and digitally signs an organization license into a secure RS256 JWT string.
   *
   * @param organizationId the target organization identifier
   * @param tier the license subscription/tier level
   * @param features a list of enabled feature entitlement keys
   * @param seats the maximum user seats allowed
   * @param expiresAt the expiration timestamp
   * @param issuerPrivateKey the license authority's private key
   * @param issuedAt the creation timestamp (defaults to now)
   * @param productVersion the product version string details
   * @return the generated and signed JWT string representation
   */
  def sign(
    organizationId: String,
    tier: String,
    features: Seq[String],
    seats: Int,
    expiresAt: Instant,
    issuerPrivateKey: PrivateKey,
    issuedAt: Instant = Instant.now(),
    productVersion: String = DefaultProductVersion
  ): String = {
    val payload = buildPayloadJson(
      organizationId, tier, features, seats, issuedAt, expiresAt, productVersion
    )
    JwtRs256.sign(payload, issuerPrivateKey)
  }

  /**
   * Parses, authenticates, and validates a JWT license string against expected parameters and trust anchors.
   *
   * @param jwt the raw JWT string to verify
   * @param expectedOrganizationId the organization ID expected in the claims
   * @param issuerPublicKey the trusted public key of the licensing authority
   * @param at the current timestamp reference for checking expiration
   * @return the verified and parsed OrgLicense instance
   * @throws SecurityException if signature validation, expiration, issuer, or org matching fails
   */
  def verify(
    jwt: String,
    expectedOrganizationId: String,
    issuerPublicKey: PublicKey = LicenseTrustAnchor.licenseIssuerPublicKey,
    at: Instant = Instant.now()
  ): OrgLicense = {
    val payloadJson = JwtRs256.verifyAndGetPayload(jwt, issuerPublicKey)
    val fields = parsePayloadJson(payloadJson)

    val iss = fields.getOrElse("iss", throw new SecurityException("Missing iss claim"))
    if (iss != DefaultIssuer) {
      throw new SecurityException(s"Unexpected license issuer: $iss")
    }

    val sub = fields.getOrElse("sub", throw new SecurityException("Missing sub claim"))
    if (sub != expectedOrganizationId) {
      throw new SecurityException(s"License org mismatch: expected $expectedOrganizationId, got $sub")
    }

    val tier = fields.getOrElse("tier", throw new SecurityException("Missing tier claim"))
    val features = fields.get("features").map(parseFeatures).getOrElse(Set.empty)
    val seats = fields.get("seats").map(_.toInt).getOrElse(0)
    val iat = Instant.ofEpochSecond(fields.getOrElse("iat", throw new SecurityException("Missing iat")).toLong)
    val exp = Instant.ofEpochSecond(fields.getOrElse("exp", throw new SecurityException("Missing exp")).toLong)
    val ver = fields.getOrElse("ver", DefaultProductVersion)

    if (exp.isBefore(at)) {
      throw new SecurityException("Org license expired")
    }

    OrgLicense(sub, tier, features, seats, iat, exp, ver)
  }

  private def buildPayloadJson(
    organizationId: String,
    tier: String,
    features: Seq[String],
    seats: Int,
    issuedAt: Instant,
    expiresAt: Instant,
    productVersion: String
  ): String = {
    val featuresJson = features.map(f => s""""$f"""").mkString("[", ",", "]")
    s"""{
       |"iss":"$DefaultIssuer",
       |"sub":"$organizationId",
       |"tier":"$tier",
       |"features":$featuresJson,
       |"seats":$seats,
       |"iat":${issuedAt.getEpochSecond},
       |"exp":${expiresAt.getEpochSecond},
       |"ver":"$productVersion"
       |}""".stripMargin.replace('\n', ' ').replaceAll("\\s+", " ").trim
  }

  // Minimal JSON parser for our fixed claim shape (no external dependency).
  private[licensing] def parsePayloadJson(json: String): Map[String, String] = {
    val trimmed = json.trim.stripPrefix("{").stripSuffix("}")
    val pattern = """"([^"]+)"\s*:\s*("([^"]*)"|(\[[^\]]*\])|(-?\d+))""".r
    pattern.findAllMatchIn(trimmed).map { m =>
      val key = m.group(1)
      val value = Option(m.group(3)).orElse(Option(m.group(4))).orElse(Option(m.group(5))).get
      key -> value
    }.toMap
  }

  private def parseFeatures(featuresJson: String): Set[String] = {
    val inner = featuresJson.stripPrefix("[").stripSuffix("]")
    if (inner.trim.isEmpty) Set.empty
    else inner.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty).toSet
  }
}
