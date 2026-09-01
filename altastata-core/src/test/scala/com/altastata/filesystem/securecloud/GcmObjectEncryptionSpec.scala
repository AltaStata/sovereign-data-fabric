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

package com.altastata.filesystem.securecloud

import com.altastata.crypto.AES256Impl
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.nio.charset.StandardCharsets

@RunWith(classOf[JUnitRunner])
class GcmObjectEncryptionSpec extends AnyFunSuite {

  private object Crypto extends SecureCloudOperations {
    def encrypt(plaintext: Array[Byte], key: Array[Byte]): Array[Byte] = encryptGcmObject(plaintext, key)
    def decrypt(record: Array[Byte], key: Array[Byte]): Array[Byte] = decryptGcmObject(record, key)
  }

  test("each encryption prepends a fresh nonce") {
    val key = new AES256Impl {}.getSecureRandomBytes(32)
    val plaintext = "same plaintext".getBytes(StandardCharsets.UTF_8)

    val first = Crypto.encrypt(plaintext, key)
    val second = Crypto.encrypt(plaintext, key)

    assert(first.length === plaintext.length + 12 + 16)
    assert(!first.take(12).sameElements(second.take(12)))
    assert(Crypto.decrypt(first, key).sameElements(plaintext))
    assert(Crypto.decrypt(second, key).sameElements(plaintext))
  }
}
