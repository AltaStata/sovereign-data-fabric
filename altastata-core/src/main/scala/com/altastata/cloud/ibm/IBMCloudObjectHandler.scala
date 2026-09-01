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

import com.altastata.cloud.s3compatible.S3CompatibleCloudObjectHandler
import com.altastata.utils.Account

/**
 * IBM Cloud Object Storage Handler for AltaStata
 * 
 * This handler implements the individual bucket pattern where each user has their own set of buckets:
 * - altastata-{org}-catalog-{username}    - User's file catalog (full access)
 * - altastata-{org}-chunks-{username}     - User's file chunks (read/write, no list)
 * - altastata-{org}-messages-{username}   - User's messages (read only)  
 * - altastata-{org}-dataattributes-{username} - User's data attributes (full access)
 * 
 * Plus cross-user access via IBM Cloud IAM groups for collaboration.
 */
class IBMCloudObjectHandler(implicit account: Account) extends S3CompatibleCloudObjectHandler {

  private val ibmManager = new IBMCloudObjectStorageManager()
  ibmManager.init()

  override protected def storageManager = ibmManager
}
