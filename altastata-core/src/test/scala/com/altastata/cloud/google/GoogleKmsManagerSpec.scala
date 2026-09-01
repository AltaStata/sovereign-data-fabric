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

package com.altastata.cloud.google

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class GoogleKmsManagerSpec extends AnyFlatSpec with Matchers {

  "GoogleKmsManager.sanitizeKeyId" should "produce valid Cloud KMS ids with room for -encrypt suffix" in {
    val id = GoogleKmsManager.sanitizeKeyId("Alice.User_01")
    id should fullyMatch regex "[a-z0-9_-]+"
    id.length should be <= (63 - "-encrypt".length)
    (id + "-encrypt").length should be <= 63
  }

  it should "handle empty / special-only names" in {
    GoogleKmsManager.sanitizeKeyId("@@@") should startWith("as-")
    GoogleKmsManager.sanitizeKeyId("") should startWith("as-")
  }

  it should "truncate very long names" in {
    val longName = "u" * 200
    val id = GoogleKmsManager.sanitizeKeyId(longName)
    id.length should be <= (63 - "-encrypt".length)
    (id + "-encrypt").length should be <= 63
  }

  it should "not collide for names that sanitize identically" in {
    GoogleKmsManager.sanitizeKeyId("Alice.User") should not equal GoogleKmsManager.sanitizeKeyId("alice-user")
    GoogleKmsManager.sanitizeKeyId("Alice.User") should not equal GoogleKmsManager.sanitizeKeyId("alice.user")
    // truncation must not erase the distinguishing tail
    GoogleKmsManager.sanitizeKeyId("u" * 200) should not equal GoogleKmsManager.sanitizeKeyId("u" * 201)
  }

  it should "be deterministic" in {
    GoogleKmsManager.sanitizeKeyId("bob123") shouldEqual GoogleKmsManager.sanitizeKeyId("bob123")
  }

  "GoogleKmsManager.parseKeyChain" should "split and trim comma-separated keys" in {
    GoogleKmsManager.parseKeyChain("a, b ,c") shouldEqual Seq("a", "b", "c")
    GoogleKmsManager.parseKeyChain("  ") shouldBe empty
  }

  "GoogleKmsManager.encryptChain / decryptChain" should "round-trip nested wrapping in reverse order" in {
    // Mock KMS: prepend key tag so decrypt can verify order
    val encryptFn = (key: String, data: Array[Byte]) => (s"E[$key]:".getBytes("UTF-8") ++ data)
    val decryptFn = (key: String, data: Array[Byte]) => {
      val prefix = s"E[$key]:".getBytes("UTF-8")
      data.take(prefix.length) shouldEqual prefix
      data.drop(prefix.length)
    }

    val plaintext = "hello-altastata".getBytes("UTF-8")
    val keys = Seq("key-a", "key-b")
    val encrypted = GoogleKmsManager.encryptChain(plaintext, keys, encryptFn)
    val decrypted = GoogleKmsManager.decryptChain(encrypted, keys, decryptFn)
    decrypted shouldEqual plaintext
  }

  it should "work with a single key" in {
    val identityWrap = (key: String, data: Array[Byte]) => Array[Byte](key.head.toByte) ++ data
    val unwrap = (key: String, data: Array[Byte]) => {
      data.head shouldBe key.head.toByte
      data.tail
    }
    val plain = Array[Byte](1, 2, 3)
    val enc = GoogleKmsManager.encryptChain(plain, Seq("Z"), identityWrap)
    GoogleKmsManager.decryptChain(enc, Seq("Z"), unwrap) shouldEqual plain
  }
}
