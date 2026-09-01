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

package com.altastata.cloud.localfs

import com.altastata.utils.Account
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class LocalFSManagerPathSpec extends AnyFunSuite {

  private def withManager(f: (LocalFSManager, java.nio.file.Path) => Unit): Unit = {
    val root = Files.createTempDirectory("altastata-localfs")
    implicit val account: Account = new Account()
    account.userProps.setProperty("root-prefix", root.toAbsolutePath.toString)
    val manager = new LocalFSManager()
    manager.init()
    try f(manager, root)
    finally {
      // best-effort cleanup
      try {
        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      } catch {
        case _: Exception =>
      }
    }
  }

  test("store and retrieve stay under root-prefix") {
    withManager { (manager, root) =>
      Await.result(manager.storeInLocalFS("hello".getBytes, "bucket", "dir/file.txt", 5), 10.seconds)
      val bytes = Await.result(manager.retrieveFromLocalFS("bucket", "dir/file.txt"), 10.seconds)
      assert(new String(bytes) === "hello")
      assert(Files.exists(root.resolve("bucket").resolve("dir").resolve("file.txt")))
    }
  }

  test("blobName with parent segments cannot escape root-prefix") {
    withManager { (manager, root) =>
      val escaped = intercept[Exception] {
        Await.result(manager.storeInLocalFS("pwn".getBytes, "bucket", "../../outside.txt", 3), 10.seconds)
      }
      assert(escaped.getMessage.contains("escapes root-prefix")
        || Option(escaped.getCause).exists(_.getMessage.contains("escapes root-prefix")))
      assert(!Files.exists(root.getParent.resolve("outside.txt")))
    }
  }
}
