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
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

@RunWith(classOf[JUnitRunner])
class TestKeysSpec extends AnyFunSuite {
  test("Check Alice Keys") {
    val accountDir = new java.io.File(
      Option(System.getenv("ALTASTATA_ACCOUNT_DIR")).filter(_.nonEmpty)
        .getOrElse(System.getProperty("user.home") + "/.altastata/accounts/fusion.rsa.alice222"))
    val credentialsFile = Option(System.getenv("FUSION_CREDENTIALS_FILE")).filter(_.nonEmpty).map(Paths.get(_))
    assume(sys.env.get("RUN_FUSION_IT").contains("1"), "skip: set RUN_FUSION_IT=1 to run live Fusion checks")
    assume(accountDir.isDirectory, s"skip: account dir not found (${accountDir.getAbsolutePath})")
    assume(credentialsFile.exists(_.toFile.isFile), "skip: set FUSION_CREDENTIALS_FILE to the Fusion credentials properties file")

    implicit val account = new Account()
    val method = account.getClass.getDeclaredMethod("readAccountConfigurationFromDirectory", classOf[String], classOf[scala.collection.mutable.ListBuffer[String]])
    method.setAccessible(true)
    val errors = scala.collection.mutable.ListBuffer[String]()
    method.invoke(account, accountDir.getAbsolutePath, errors)
    
    val accessKey = account.getAndDecryptProperty("fusion-access-key")
    val secretKey = account.getAndDecryptProperty("fusion-secret-key")
    println("Alice Decrypted Access Key: '" + accessKey + "'")
    
    val rawKeys = Files.readAllLines(credentialsFile.get, StandardCharsets.UTF_8)
    val plainAccessKey = rawKeys.toArray.find(_.toString.startsWith("alice222.access_key=")).get.toString.split("=")(1)
    
    println("Alice Plain Access Key    : '" + plainAccessKey + "'")
    println("Match? " + (accessKey == plainAccessKey))
  }
}
