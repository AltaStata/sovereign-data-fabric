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

import java.nio.file.Files
import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}

@RunWith(classOf[JUnitRunner])
class FileSecuritySpec extends AnyFunSuite {

  test("restrictToOwner applies POSIX 0600 when supported") {
    val path = Files.createTempFile("as-secret-", ".tmp")
    val view = Files.getFileAttributeView(path, classOf[PosixFileAttributeView])
    assume(view != null, "POSIX permissions are unsupported")

    view.setPermissions(PosixFilePermissions.fromString("rw-r--r--"))
    FileSecurity.restrictToOwner(path)

    assert(view.readAttributes().permissions() === PosixFilePermissions.fromString("rw-------"))
  }
}
