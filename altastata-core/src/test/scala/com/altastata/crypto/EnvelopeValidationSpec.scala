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

import com.altastata.utils.{Account, Constants}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Malformed-envelope rejection: decrypt entry points must fail with a clean
 * SecurityException before any key material or HSM handler is touched.
 */
@RunWith(classOf[JUnitRunner])
class EnvelopeValidationSpec extends AnyFunSuite {

  private object utils extends SecurityUtils

  // Validation happens before the account is used, so a null account proves the
  // functions fail fast instead of dereferencing key material for a bad envelope.
  private implicit val noAccount: Account = null

  test("Kyber envelope shorter than IV + key + encapsulation is rejected") {
    val tooShort = new Array[Byte](Constants.AES_GCM_IV_SIZE + 48 + 1568 - 1)
    val e = intercept[SecurityException] {
      utils.decryptArrayWithKyber(tooShort)
    }
    assert(e.getMessage.contains("Malformed Kyber envelope"))
  }

  test("HSM envelope shorter than length field + IV is rejected") {
    val tooShort = new Array[Byte](4 + Constants.AES_GCM_IV_SIZE - 1)
    val e = intercept[SecurityException] {
      utils.decryptArrayWithHSM(tooShort, "key")
    }
    assert(e.getMessage.contains("Malformed HSM envelope"))
  }

  test("HSM envelope with negative declared key length is rejected") {
    val envelope = new Array[Byte](4 + Constants.AES_GCM_IV_SIZE + 64)
    envelope(0) = 0xFF.toByte // negative 32-bit length
    envelope(1) = 0xFF.toByte
    envelope(2) = 0xFF.toByte
    envelope(3) = 0xFF.toByte
    val e = intercept[SecurityException] {
      utils.decryptArrayWithHSM(envelope, "key")
    }
    assert(e.getMessage.contains("does not fit"))
  }

  test("HSM envelope with declared key length beyond the buffer is rejected") {
    val envelope = new Array[Byte](4 + Constants.AES_GCM_IV_SIZE + 64)
    envelope(3) = 0x7F.toByte // length 127 > remaining 64 bytes
    val e = intercept[SecurityException] {
      utils.decryptArrayWithHSM(envelope, "key")
    }
    assert(e.getMessage.contains("does not fit"))
  }
}
