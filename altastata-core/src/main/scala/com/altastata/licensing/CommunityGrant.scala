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

import java.time.Instant

/** BSL Additional Use Grant (c) — no `license.jwt`; RSA-only Community path. */
object CommunityGrant {

  val Tier = "community"
  val MaxSeats = 5
  /** One org custodian identity may be signed in addition to [[MaxSeats]]. */
  val MaxCustodians = 1
  val Features: Set[String] = Set("core", "single-cloud", "rsa")

  /**
   * Community custodian usernames end with {@code custodian} (case-insensitive),
   * matching Admin cloud provisioning conventions.
   *
   * @param userName account / CN user segment
   * @return true when this identity is the org custodian seat exemption
   */
  def isCustodianUserName(userName: String): Boolean =
    userName != null && userName.toLowerCase(java.util.Locale.ROOT).endsWith("custodian")

  /**
   * Generates a default mock OrgLicense matching BSL Community Grant parameters.
   *
   * @param organizationId the organization namespace
   * @return the constructed community OrgLicense
   */
  def defaultForOrganization(organizationId: String): OrgLicense = OrgLicense(
    organizationId = organizationId,
    tier = Tier,
    features = Features,
    seats = MaxSeats,
    issuedAt = Instant.EPOCH,
    expiresAt = Instant.parse("2099-12-31T23:59:59Z"),
    productVersion = OrgLicense.DefaultProductVersion
  )
}
