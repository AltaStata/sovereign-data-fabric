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

package com.altastata.cloud.minio

import com.altastata.cloud.s3compatible.S3CompatibleCloudObjectHandler
import com.altastata.utils.Account

/**
 * MinIO Cloud Object Storage Handler for AltaStata
 * 
 * This handler implements the individual bucket pattern where each user has their own set of buckets:
 * - altastata-{org}-catalog-{username}    - User's file catalog (full access)
 * - altastata-{org}-chunks-{username}     - User's file chunks (read/write access)
 * - altastata-{org}-changes-{username}    - User's change logs (full access)
 * - altastata-{org}-dataattributes-{username} - User's data attributes (full access)
 * 
 * Plus shared buckets:
 * - altastata-{org}-users-all              - Shared user metadata (read access for all)
 * 
 * MinIO simplifies access control compared to cloud providers - buckets are accessed via
 * access keys rather than complex IAM policies.
 */
class MinIOCloudObjectHandler(implicit account: Account) extends S3CompatibleCloudObjectHandler {

  private val minioManager = new MinIOCloudObjectStorageManager()
  minioManager.init()

  override protected def storageManager = minioManager
}
