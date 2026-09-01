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

package com.altastata.cloud.azure_v12

import com.altastata.filesystem.securecloud.OpsExecutors
import com.azure.core.http.HttpClient
import com.azure.core.util.HttpClientOptions

import java.time.Duration

object AzureHttpClients {

  /**
   * Split Netty pools so 8 MiB chunk transfers cannot occupy the connections
   * that catalog / dataattributes / changes need (THREAD_POOL_CONGESTION_DESIGN.md).
   * Chunks also get a longer transfer timeout; the old 60s default aborted large stores.
   */
  private def client(maxConnections: Int, transferTimeout: Duration): HttpClient = {
    val options = new HttpClientOptions()
      .setMaximumConnectionPoolSize(Integer.valueOf(maxConnections))
      .setConnectTimeout(Duration.ofSeconds(15))
      .setConnectionIdleTimeout(Duration.ofSeconds(60))
      .setReadTimeout(transferTimeout)
      .setResponseTimeout(transferTimeout)
      .setWriteTimeout(transferTimeout)
    HttpClient.createDefault(options)
  }

  lazy val chunksHttpClient: HttpClient =
    client(OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_CHUNKS, Duration.ofSeconds(180))

  lazy val systemHttpClient: HttpClient =
    client(OpsExecutors.CLOUD_HTTP_MAX_CONNECTIONS_SYSTEM, Duration.ofSeconds(60))
}
