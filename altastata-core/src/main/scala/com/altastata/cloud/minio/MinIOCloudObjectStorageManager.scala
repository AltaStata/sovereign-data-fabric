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

import com.altastata.cloud.s3compatible.S3CompatibleStorageManager
import com.altastata.utils.Account

/**
 * MinIO Object Storage Manager for AltaStata
 * 
 * Extends the generic S3CompatibleStorageManager with MinIO-specific configuration.
 * 
 * MinIO is S3-compatible, so we use the AWS SDK with MinIO-specific configuration:
 * - Path-style access (required for MinIO)
 * - Custom endpoint configuration
 * - Simplified credential management (no IAM complexity)
 */
class MinIOCloudObjectStorageManager(implicit account: Account) 
    extends S3CompatibleStorageManager {

  // MinIO configuration
  override protected def providerName: String = "MinIO"
  override protected def endpointProperty: String = "minio-endpoint"
  override protected def accessKeyProperty: String = "minio-access-key"
  override protected def secretKeyProperty: String = "minio-secret-key"

  /**
   * MinIO always uses encrypted credentials (no fallback to plain text).
   * This matches the original implementation behavior.
   */
  override protected def getAccessKey: String = account.getAndDecryptProperty(accessKeyProperty)
  override protected def getSecretKey: String = account.getAndDecryptProperty(secretKeyProperty)
}
