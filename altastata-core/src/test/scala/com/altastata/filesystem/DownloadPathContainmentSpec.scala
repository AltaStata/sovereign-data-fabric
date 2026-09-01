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

package com.altastata.filesystem

import com.altastata.filesystem.common.FileSystemHandler
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DownloadPathContainmentSpec extends AnyFunSuite {

  test("cloud download targets stay inside output directory") {
    val outputDir = java.nio.file.Files.createTempDirectory("as-download-").toString
    val root = java.nio.file.Paths.get(outputDir).toAbsolutePath.normalize()

    val target = FileSystemHandler.resolveDownloadPathInsideOutputDir(outputDir, "folder/file.txt")
    assert(java.nio.file.Paths.get(target).startsWith(root))

    intercept[SecurityException] {
      FileSystemHandler.resolveDownloadPathInsideOutputDir(outputDir, "../escape.txt")
    }
    intercept[SecurityException] {
      FileSystemHandler.resolveDownloadPathInsideOutputDir(outputDir, "folder/../../escape.txt")
    }
  }
}
