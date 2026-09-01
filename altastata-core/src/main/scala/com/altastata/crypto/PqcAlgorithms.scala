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

import org.bouncycastle.jcajce.spec.{MLDSAParameterSpec, MLKEMParameterSpec}
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.Security

/** NIST PQC algorithm names for bcprov-jdk18on via the standard {@code BC} provider. */
object PqcAlgorithms {

  val Provider: String = BouncyCastleProvider.PROVIDER_NAME

  /** ML-KEM-1024 (Kyber-1024 equivalent). */
  val KemAlgorithm: String = "ML-KEM-1024"
  val KemParameterSpec: MLKEMParameterSpec = MLKEMParameterSpec.ml_kem_1024

  /** ML-DSA-87 (Dilithium5 equivalent). */
  val SignatureAlgorithm: String = "ML-DSA-87"
  val SignatureParameterSpec: MLDSAParameterSpec = MLDSAParameterSpec.ml_dsa_87

  def ensureProviderRegistered(): Unit = {
    if (Security.getProvider(Provider) == null) {
      Security.addProvider(new BouncyCastleProvider())
    }
  }
}
