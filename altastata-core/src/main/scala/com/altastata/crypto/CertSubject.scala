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

/**
 * X.509 subject (CN) conventions for AltaStata user certificates.
 *
 * User certs: {@code CN=<acccontainer-prefix><userName>} e.g. {@code CN=altastata-myorg-bob}.
 *
 * Org-CA issuer DN: {@code CN=<acccontainer-prefix>} e.g. {@code CN=altastata-myorg-}
 * (Community AltaStata cloud {@code /sign} still uses {@code CN=AltaStata}).
 *
 * CN is inside the CA-signed TBSCertificate and cannot be forged without invalidating the signature.
 */
object CertSubject {

  /** Community cloud {@code /sign} issuer CN (without {@code CN=} prefix). */
  val AltaStataIssuerCn = "AltaStata"

  /**
   * Generates the standard datalake/account container prefix for an organization.
   *
   * @param organization The organization name.
   * @return The formatted prefix string (e.g., "altastata-myorg-").
   */
  def accountContainerPrefix(organization: String): String = {
    require(organization != null && organization.trim.nonEmpty, "organization required")
    "altastata-" + organization.trim + "-"
  }

  /**
   * Generates the organization CA issuer CN name, which matches the datalake prefix.
   *
   * @param organization The organization name.
   * @return The formatted organization CA issuer CN value.
   */
  def issuerForDatalake(organization: String): String = accountContainerPrefix(organization)

  /**
   * Generates the certificate subject CN name for a user within an organization.
   *
   * @param accountContainerPrefix The organization datalake prefix.
   * @param userName               The username.
   * @return The formatted subject CN value (e.g., "altastata-myorg-bob").
   */
  def forUser(accountContainerPrefix: String, userName: String): String = {
    require(accountContainerPrefix != null && accountContainerPrefix.nonEmpty, "accountContainerPrefix required")
    require(userName != null && userName.trim.nonEmpty, "userName required")
    accountContainerPrefix + userName.trim
  }

  /**
   * Checks whether the subject DN string matches the expected organization prefix and username.
   *
   * @param actualSubjectDn        The actual subject DN string from the certificate.
   * @param accountContainerPrefix The organization datalake prefix.
   * @param userName               The username.
   * @return True if the subject CN matches perfectly.
   */
  def matches(actualSubjectDn: String, accountContainerPrefix: String, userName: String): Boolean = {
    val actual = Option(actualSubjectDn).map(_.trim).getOrElse("")
    actual == "CN=" + forUser(accountContainerPrefix, userName)
  }

  /**
   * Asserts that the subject DN string matches the expected organization prefix and username.
   * Throws a SecurityException if there is a mismatch.
   *
   * @param actualSubjectDn        The actual subject DN string from the certificate.
   * @param accountContainerPrefix The organization datalake prefix.
   * @param userName               The username.
   * @throws SecurityException if the subject name does not match.
   */
  def assertMatches(actualSubjectDn: String, accountContainerPrefix: String, userName: String): Unit = {
    if (!matches(actualSubjectDn, accountContainerPrefix, userName)) {
      throw new SecurityException(
        s"Wrong subject name: expected CN=${forUser(accountContainerPrefix, userName)}, " +
          s"but certificate is for: $actualSubjectDn"
      )
    }
  }
}
