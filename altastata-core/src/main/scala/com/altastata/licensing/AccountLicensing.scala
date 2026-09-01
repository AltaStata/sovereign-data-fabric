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

import com.altastata.crypto.CertTrustAnchor
import com.altastata.utils.Account

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.PublicKey

/**
 * Resolves org entitlement (`license.jwt`) and user-cert trust anchor per account folder.
 * See docs/licensing/RUNTIME_DESIGN.md §7b.
 */
object AccountLicensing {

  val LicenseJwtFileName = "license.jwt"

  /**
   * Resets the licensing configuration for an account back to standard un-licensed/community settings.
   *
   * @param account the target account to reset
   */
  def reset(account: Account): Unit = {
    account.setRuntimeLicensing(None, CertTrustAnchor.communityIssuerPublicKey, licensedIdentity = false)
  }

  /**
   * Load `license.jwt` (when account dir is known), enforce login gates, install cert trust key.
   * Must run whenever account properties are loaded — not only from `setPassword` (HSM has no local key).
   */
  def refresh(account: Account): Unit = {
    if (account.MY_USER == "admin") {
      reset(account)
      return
    }

    val accountDirOpt = Option(account.getAccountDir())
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(new File(_))
      .filter(_.isDirectory)

    val orgId = organizationId(account)
    val licenseOpt = accountDirOpt.flatMap(dir => loadOrgLicense(dir, orgId))

    enforceLoginGate(account, licenseOpt)

    val licensed = licenseOpt.isDefined
    val certTrustKey = accountDirOpt match {
      case Some(dir) => CertTrustAnchor.resolveCertTrustPublicKey(dir, licensed)
      case None =>
        // Text-only load (no account folder): Community RSA only; paid features already rejected above.
        CertTrustAnchor.communityIssuerPublicKey
    }
    account.setRuntimeLicensing(licenseOpt, certTrustKey, licensedIdentity = licensed)
  }

  /**
   * Resolves the organization ID from the account user properties or defaults to the container prefix.
   *
   * @param account the target account context
   * @return the resolved organization ID string
   */
  def organizationId(account: Account): String = {
    Option(account.userProps.getProperty("license-org-id"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(account.ACCOUNT_CONTAINER_PREFIX.stripSuffix("-"))
  }

  private[licensing] def loadOrgLicense(accountDir: File, organizationId: String): Option[OrgLicense] = {
    val licenseFile = new File(accountDir, LicenseJwtFileName)
    if (!licenseFile.isFile) None
    else {
      val jwt = new String(Files.readAllBytes(licenseFile.toPath), StandardCharsets.UTF_8).trim
      Some(OrgLicense.verify(jwt, organizationId))
    }
  }

  private def enforceLoginGate(account: Account, licenseOpt: Option[OrgLicense]): Unit = {
    val metadataEncryption = account.userProps.getProperty("metadata-encryption")
    val keyProtection = account.userProps.getProperty("key-protection")

    // Paid crypto paths require license.jwt at property load (caller-independent).
    // Community RSA is allowed without JWT; AltaStata-signed user cert is checked in verifyUserIdentity.
    metadataEncryption match {
      case "HSM" =>
        val license = licenseOpt.getOrElse(throw gateError("HSM accounts require license.jwt in the account folder"))
        FeatureGate.requireFeature(license, "hsm")
      case "PQC" =>
        val license = licenseOpt.getOrElse(throw gateError("PQC requires license.jwt with feature pqc"))
        FeatureGate.requireFeature(license, "pqc")
      case _ =>
    }

    if (keyProtection == "HPCS") {
      val license = licenseOpt.getOrElse(throw gateError("HPCS requires license.jwt with feature hpcs"))
      if (!license.hasFeature("hpcs") && !license.hasFeature("hsm")) {
        throw gateError("HPCS requires license feature hpcs or hsm")
      }
    }

    if (account.ACCOUNT_TYPE == "multicloud-secure") {
      val license = licenseOpt.getOrElse(throw gateError("Multicloud requires license.jwt with feature multicloud"))
      FeatureGate.requireFeature(license, "multicloud")
    }

    if (account.isCustodianMode) {
      val license = licenseOpt.getOrElse(throw gateError("Custodian mode requires license.jwt with feature custodian"))
      FeatureGate.requireFeature(license, "custodian")
    }
  }

  private def gateError(message: String): SecurityException =
    new SecurityException(message)
}
