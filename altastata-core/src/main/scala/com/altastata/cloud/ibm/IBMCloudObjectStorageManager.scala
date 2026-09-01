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

package com.altastata.cloud.ibm

import com.altastata.cloud.s3compatible.S3CompatibleStorageManager
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.time.Duration

/**
 * IBM Cloud Object Storage Manager for AltaStata
 * 
 * Extends the generic S3CompatibleStorageManager with IBM COS-specific configuration:
 * - Tighter timeouts (15s/30s vs 60s/60s)
 * - Admin vs user credential handling
 */
class IBMCloudObjectStorageManager(implicit account: Account) 
    extends S3CompatibleStorageManager {

  private val logger = LoggerFactory.getLogger(getClass)

  // IBM COS configuration
  override protected def providerName: String = "IBM COS"
  override protected def endpointProperty: String = "ibm-cos-endpoint"
  override protected def accessKeyProperty: String = "ibm-cos-hmac-access-key-id"
  override protected def secretKeyProperty: String = "ibm-cos-hmac-secret-access-key"

  // IBM COS uses tighter timeouts
  override protected def connectionTimeout: Duration = Duration.ofSeconds(15)
  override protected def socketTimeout: Duration = Duration.ofSeconds(30)

  /**
   * IBM COS has special admin vs user credential handling.
   * Admin uses plain text credentials, regular users use encrypted.
   */
  override protected def getAccessKey: String = {
    if (account.MY_USER == "admin") {
      logger.info("Using admin HMAC credentials (non-encrypted)")
      account.getProperty(accessKeyProperty)
    } else {
      logger.info("Using user HMAC credentials (encrypted)")
      account.getAndDecryptProperty(accessKeyProperty)
    }
  }

  override protected def getSecretKey: String = {
    if (account.MY_USER == "admin") {
      account.getProperty(secretKeyProperty)
    } else {
      account.getAndDecryptProperty(secretKeyProperty)
    }
  }
}
