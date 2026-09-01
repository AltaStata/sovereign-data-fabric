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

package com.altastata.cloud.fusion

import com.altastata.utils.Account
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestDecryptionSpec extends AnyFunSuite {
  test("Check decryption") {
    assume(sys.env.get("RUN_FUSION_IT").contains("1"), "skip: set RUN_FUSION_IT=1 to run live Fusion checks")
    val accountDir = new java.io.File(
      Option(System.getenv("ALTASTATA_ACCOUNT_DIR")).filter(_.nonEmpty)
        .getOrElse(System.getProperty("user.home") + "/.altastata/accounts/fusion.rsa.bob123"))
    assume(accountDir.isDirectory, s"skip: Fusion account dir not found (${accountDir.getAbsolutePath})")
    implicit val account = new Account()
    val method = account.getClass.getDeclaredMethod("readAccountConfigurationFromDirectory", classOf[String], classOf[scala.collection.mutable.ListBuffer[String]])
    method.setAccessible(true)
    val errors = scala.collection.mutable.ListBuffer[String]()
    method.invoke(account, accountDir.getAbsolutePath, errors)
    
    val accessKey = account.getAndDecryptProperty("fusion-access-key")
    val secretKey = account.getAndDecryptProperty("fusion-secret-key")
    println("Decrypted Access Key: '" + accessKey + "'")
    println("Decrypted Secret Key: '" + secretKey + "'")
  }
}
