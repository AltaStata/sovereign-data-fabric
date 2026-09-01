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

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AltaStataEnterpriseOidsSpec extends AnyFunSuite {

  test("PQC hybrid cert extension OIDs are under the AltaStata enterprise arc") {
    val pen = AltaStataEnterpriseOids.PrivateEnterpriseNumber
    val root = s"1.3.6.1.4.1.$pen"

    assert(AltaStataEnterpriseOids.KemPublicKeyPemExtensionOid === s"$root.1.1")
    assert(AltaStataEnterpriseOids.SignaturePublicKeyPemExtensionOid === s"$root.1.2")

    // Sanity: valid ASN.1 OIDs (Bouncy Castle parser accepts them).
    new ASN1ObjectIdentifier(AltaStataEnterpriseOids.KemPublicKeyPemExtensionOid)
    new ASN1ObjectIdentifier(AltaStataEnterpriseOids.SignaturePublicKeyPemExtensionOid)
  }
}
