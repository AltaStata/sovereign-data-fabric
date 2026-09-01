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

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CertSubjectSpec extends AnyFunSuite {

  private val prefix = "altastata-myorg-"

  test("forUser appends userName to org prefix") {
    assert(CertSubject.forUser(prefix, "bob") === "altastata-myorg-bob")
  }

  test("matches accepts user-qualified CN") {
    assert(CertSubject.matches("CN=altastata-myorg-bob", prefix, "bob"))
  }

  test("matches rejects org-only CN") {
    assert(!CertSubject.matches("CN=altastata-myorg-", prefix, "bob"))
  }

  test("matches rejects another user") {
    assert(!CertSubject.matches("CN=altastata-myorg-alice", prefix, "bob"))
  }
}
