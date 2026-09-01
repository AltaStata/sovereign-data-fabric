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

import org.scalatest.funsuite.AnyFunSuite
import java.io.File
import scala.sys.process._

class TestFusionAlice extends AnyFunSuite {
  test("Can run env.sh and check buckets with awscli") {
    val envSh = Option(System.getenv("FUSION_ENV_SH")).filter(_.nonEmpty)
    val credentialsFile = Option(System.getenv("FUSION_CREDENTIALS_FILE")).filter(_.nonEmpty)
    assume(sys.env.get("RUN_FUSION_IT").contains("1"), "skip: set RUN_FUSION_IT=1 to run live Fusion checks")
    assume(envSh.exists(p => new File(p).isFile), "skip: set FUSION_ENV_SH to the Fusion env.sh")
    assume(credentialsFile.exists(p => new File(p).isFile), "skip: set FUSION_CREDENTIALS_FILE to the Fusion credentials properties file")
    val cmd = Seq("bash", "-c",
      s"source ${envSh.get} && AWS_ACCESS_KEY_ID=$$(grep '^alice222.access_key=' ${credentialsFile.get} | cut -d= -f2) AWS_SECRET_ACCESS_KEY=$$(grep '^alice222.secret_key=' ${credentialsFile.get} | cut -d= -f2) aws --no-verify-ssl --endpoint-url $$S3_ENDPOINT s3 ls s3://altastata-myorgrsa444-catalog-bob123/Public/")
    val result = cmd.!!
    println(result)
  }
}
