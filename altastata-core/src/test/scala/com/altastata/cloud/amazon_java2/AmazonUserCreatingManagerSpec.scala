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

package com.altastata.cloud.amazon_java2

import com.altastata.utils.Account
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.util.{Base64, Properties}

@RunWith(classOf[JUnitRunner])
class AmazonUserCreatingManagerSpec extends AnyFunSuite {

  // encrypt path is not exercised here; reject/leave paths must not call Account.
  implicit val account: Account = null

  test("invalid plaintext access key refuses to leave secret in plaintext") {
    val props = new Properties()
    props.setProperty("metadata-encryption", "RSA")
    props.setProperty("AWSAccessKeyId", "not-an-aws-key")
    props.setProperty("AWSSecretKey", "supersecret")

    val err = intercept[IllegalArgumentException] {
      new AmazonUserCreatingManager(props).enhanceUserPropertiesIfNeeded(null)
    }
    assert(err.getMessage.contains("refusing to leave AWSSecretKey in plaintext"))
    assert(props.getProperty("AWSSecretKey") === "supersecret")
  }

  test("already encrypted access key is left unchanged") {
    val encryptedLooking =
      Base64.getEncoder.encodeToString(Array.fill[Byte](64)(7))
    val props = new Properties()
    props.setProperty("metadata-encryption", "RSA")
    props.setProperty("AWSAccessKeyId", encryptedLooking)
    props.setProperty("AWSSecretKey", encryptedLooking)

    val out = new AmazonUserCreatingManager(props).enhanceUserPropertiesIfNeeded(null)
    assert(out.getProperty("AWSAccessKeyId") === encryptedLooking)
    assert(out.getProperty("AWSSecretKey") === encryptedLooking)
  }
}
