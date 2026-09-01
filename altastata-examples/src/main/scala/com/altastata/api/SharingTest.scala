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

package com.altastata.api

import com.altastata.api.BobUploadAndShare.args
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.io.File


object SharingTest extends App {

  private val logger = LoggerFactory.getLogger(getClass)

  val userPropertiesAWS: String =
    """AWSSecretKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
      |myuser=catrina777
      |accounttype=amazon-s3-secure
      |AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE
      |region=us-east-1
      |kms-region=us-east-2
      |metadata-encryption=HSM
      |acccontainer-prefix=altastata-myorg321-
      |""".stripMargin

  // Routed through AccountRegistry so this example sits behind the same
  // mandatory entry-point as production callers (ALTASTATA_SERVICES_UBER_DESIGN.md §4).
  // The fact that this file is in com.altastata.api means it *could* still
  // see the package-private constructors, but we intentionally don't use them.
  // AccountRegistry is all-static — no instance to wire up.

  val afsAzure = AccountRegistry.getOrCreateFromDir(
    Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "azure.rsa.alice222")
  afsAzure.setPassword(args(0))

  // HSM: no local PEM — event loop starts during getOrCreate; empty password unlocks hardware.
  val afsAWS = AccountRegistry.getOrCreate(userPropertiesAWS, null)
  afsAWS.setPassword("")

  val statusAzure = afsAzure.share("Public/Documents", true, null, null, Array("bob123"))

  print(statusAzure)

  val statusAWS = afsAWS.share("Applications/results", true, null, null, Array("bob123"))

  print(statusAWS)


}
