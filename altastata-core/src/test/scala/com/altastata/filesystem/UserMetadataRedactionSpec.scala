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

package com.altastata.filesystem

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UserMetadataRedactionSpec extends AnyFunSuite {

  test("redacted summary never contains credentials, SAS tokens, or key material") {
    val metadata = UserMetadata(
      userName = "alice",
      userType = "user",
      organization = "example",
      metadataEncryption = Some("PQC"),
      publicPQCKeyCertPEM = Some("-----BEGIN CERTIFICATE-----SECRET"),
      readOnlyChunksSAS = Some("sp=r&sig=CHUNKS_SECRET"),
      writeOnlyChangesSAS = Some("sp=w&sig=CHANGES_SECRET"),
      cognitoIdentityId = Some("identity-secret")
    )

    val summary = metadata.redactedSummary

    assert(summary.contains("userName=alice"))
    assert(summary.contains("hasPublicKey=true"))
    assert(summary.contains("hasSas=true"))
    assert(!summary.contains("SECRET"))
    assert(!summary.contains("sig="))
    assert(!summary.contains("identity-secret"))
    assert(!summary.contains("BEGIN CERTIFICATE"))
  }
}
