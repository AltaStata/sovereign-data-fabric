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

import java.io.File

import com.altastata.utils.Account
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class TestGoogleHsmSpec extends AnyFunSuite {
  test("HSM upload and download with separate KMS encrypt/sign keys") {
    val accountDir = Option(System.getenv("ALTASTATA_ACCOUNT_DIR")).filter(_.nonEmpty)
      .getOrElse("")
    assume(accountDir.nonEmpty && new File(accountDir).isDirectory,
      "skip: set ALTASTATA_ACCOUNT_DIR to a Google HSM account directory")

    val account = new Account()
    val errors = account.loadAccountProperties(accountDir)
    assert(errors.isEmpty, s"loadAccountProperties errors: ${errors.mkString("; ")}")

    assert(account.getProperty("accounttype") == "google-secure")
    assert(account.getProperty("metadata-encryption") == "HSM")

    // HSM: no local private key verification; initialize handlers
    account.setPassword(Array.emptyCharArray)

    val content = "Secret Google HSM Data".getBytes("UTF-8")
    val objectName = "hsm-test-file"

    println("Uploading test file to chunks bucket...")
    val putRes = account.cloudObjectHandler.storeObjectToCloud(
      content, account.CHUNKS_BUCKET, account.MY_USER, objectName)
    assert(putRes.isSuccess, s"Upload failed: ${putRes.failed.toOption.map(_.getMessage)}")

    println("Downloading test file...")
    val downloaded = account.cloudObjectHandler.retrieveObjectFromCloud(
      account.CHUNKS_BUCKET, account.MY_USER, objectName)
    assert(downloaded.isSuccess, s"Download failed: ${downloaded.failed.toOption.map(_.getMessage)}")
    assert(new String(downloaded.get, "UTF-8") == "Secret Google HSM Data")

    account.cloudObjectHandler.deleteObjectFromCloud(
      account.CHUNKS_BUCKET, account.MY_USER, objectName)
    println("Success! Upload and download works using Google Cloud KMS separated keys and SA credentials!")
  }
}
