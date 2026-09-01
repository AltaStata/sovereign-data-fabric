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

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Simulation of multi-key KMS unwrap order for {@code AmazonKmsManager.decryptObjectWithHSM}:
 * encrypt applies keys left-to-right; decrypt reverses and must feed each layer's output forward.
 */
@RunWith(classOf[JUnitRunner])
class AmazonKmsKeyChainSpec extends AnyFunSuite {

  private def unwrap(prefix: String)(ct: Array[Byte]): Array[Byte] = {
    val s = new String(ct, "UTF-8")
    if (s.startsWith(prefix)) s.stripPrefix(prefix).getBytes("UTF-8") else ct
  }

  test("multi-key decrypt chain feeds previous plaintext into the next unwrap") {
    val plain = "secret".getBytes("UTF-8")
    val layer1 = ("L1:" + new String(plain, "UTF-8")).getBytes("UTF-8")
    val layer2 = ("L2:" + new String(layer1, "UTF-8")).getBytes("UTF-8")

    var current = layer2
    for (step <- List("L2:", "L1:")) {
      current = unwrap(step)(current)
    }
    assert(new String(current, "UTF-8") === "secret")

    // Old bug: always unwrap the original outer ciphertext for every key.
    var broken = layer2
    for (step <- List("L2:", "L1:")) {
      broken = unwrap(step)(layer2)
    }
    assert(new String(broken, "UTF-8") !== "secret")
  }
}
