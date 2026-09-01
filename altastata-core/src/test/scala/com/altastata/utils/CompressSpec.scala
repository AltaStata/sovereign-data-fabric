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

package com.altastata.utils

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CompressSpec extends AnyFunSuite with Compress {

  test("inflate round-trips deflated data") {
    val plaintext = ("altastata " * 1000).getBytes("UTF-8")
    val inflated = inflate(deflate(plaintext).get).get
    assert(inflated === plaintext)
  }

  test("inflate accepts output up to the chunk limit") {
    val plaintext = new Array[Byte](Constants.PLAIN_CHUNK_MAX_SIZE) // zeros compress well
    assert(inflate(deflate(plaintext).get).get.length === Constants.PLAIN_CHUNK_MAX_SIZE)
  }

  test("inflate rejects output beyond the chunk limit (zip bomb)") {
    val bomb = deflate(new Array[Byte](Constants.PLAIN_CHUNK_MAX_SIZE + 1)).get
    val result = inflate(bomb)
    assert(result.isFailure)
    assert(result.failed.get.getMessage.contains("Refusing to inflate"))
  }
}
