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
 * AltaStata private-enterprise OID arc for PQC hybrid user identity certificates.
 *
 * Arc layout under IANA PEN prefix {@code 1.3.6.1.4.1}:
 *
 * {{{
 *   1.3.6.1.4.1.{pen}.1.1  — ML-KEM (Kyber) public key PEM in X.509 extension
 *   1.3.6.1.4.1.{pen}.1.2  — ML-DSA (Dilithium) public key PEM in X.509 extension
 * }}}
 *
 * {@link PrivateEnterpriseNumber} is provisional until AltaStata Inc. receives an
 * official IANA Private Enterprise Number:
 * https://www.iana.org/assignments/enterprise-numbers/
 */
object AltaStataEnterpriseOids {

  /**
   * Provisional IANA Private Enterprise Number for AltaStata Inc.
   * Update this single constant when the official PEN is assigned.
   */
  val PrivateEnterpriseNumber: Long = 66133L

  private val EnterpriseRoot = s"1.3.6.1.4.1.$PrivateEnterpriseNumber"

  /** Submodule for PQC hybrid identity certificate custom extensions. */
  private val PqcHybridUserCertExtensions = s"$EnterpriseRoot.1"

  /** UTF-8 PEM of the user's ML-KEM-1024 public key. */
  val KemPublicKeyPemExtensionOid: String = s"$PqcHybridUserCertExtensions.1"

  /** UTF-8 PEM of the user's ML-DSA-87 public key. */
  val SignaturePublicKeyPemExtensionOid: String = s"$PqcHybridUserCertExtensions.2"
}
