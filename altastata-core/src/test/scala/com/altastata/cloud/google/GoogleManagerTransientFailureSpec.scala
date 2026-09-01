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

import java.net.SocketException

import com.google.cloud.storage.StorageException
import org.apache.http.NoHttpResponseException
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class GoogleManagerTransientFailureSpec extends AnyFunSuite {

  test("Socket closed and nested StorageException are transient") {
    assert(GoogleManager.isTransientGcsFailure(new SocketException("Socket closed")))
    assert(GoogleManager.isTransientGcsFailure(
      new StorageException(0, "GoogleStorage ... due to 'Socket closed'", new SocketException("Socket closed"))))
    assert(GoogleManager.isTransientGcsFailure(new StorageException(503, "Service Unavailable")))
    assert(GoogleManager.isTransientGcsFailure(new StorageException(429, "Rate limit")))
  }

  test("NoHttpResponseException stale keep-alive is transient") {
    val noHttp = new NoHttpResponseException("The target server failed to respond")
    assert(GoogleManager.isTransientGcsFailure(noHttp))
    assert(GoogleManager.isTransientGcsFailure(
      new StorageException(0, "The target server failed to respond", noHttp)))
  }

  test("not-found and auth failures are not transient") {
    assert(!GoogleManager.isTransientGcsFailure(new StorageException(404, "Blob not found")))
    assert(!GoogleManager.isTransientGcsFailure(new StorageException(403, "Forbidden")))
    assert(!GoogleManager.isTransientGcsFailure(new IllegalArgumentException("bad key")))
  }
}
