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

import com.altastata.cloud.s3compatible.S3CompatibleStorageManager
import com.altastata.utils.Account
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.utils.AttributeMap

import java.net.URI

case class FusionContainerNotFoundException(intent: String)
  extends RuntimeException(s"${intent}")

/**
 * IBM Fusion (OpenShift Data Foundation) Object Storage Manager for AltaStata.
 *
 * Extends [[S3CompatibleStorageManager]] with Fusion-specific configuration.
 * IBM Fusion is based on OpenShift Data Foundation (Ceph / NooBaa MCG) and
 * provides an S3-compatible API; this manager connects to the Fusion S3
 * Gateway running on OpenShift.
 *
 * Production Fusion path: just falls through to `super.init()` — same
 * Apache HTTP client and same SSL stack IBM COS / MinIO use, expecting a
 * real CA-signed cert (OpenShift Service CA, customer-supplied, or
 * cert-manager) whose hostname matches the route.
 *
 * Dev-only path: when the user properties carry
 * `fusion-disable-ssl-verification=true`, we still use the same
 * `ApacheHttpClient` as the production path (for connection pooling,
 * keep-alive, and parity with IBM COS / MinIO), but build it with
 * `TRUST_ALL_CERTIFICATES=true` so the SDK installs a trust-all
 * `X509TrustManager` AND swaps in `NoopHostnameVerifier` — both checks
 * off, scoped to THIS client only (no JVM-wide side effects). This lets
 * the AWS S3 SDK talk to a kind / GKE NooBaa whose operator-generated
 * cert is self-issued (CN=`selfsigned.noobaa.io`, no chain to a real
 * CA, SAN that doesn't match a port-forward / LoadBalancer hostname).
 *
 * The bypass is fully contained in this subclass; the generic
 * S3CompatibleStorageManager is not modified, so IBM COS and MinIO are
 * unaffected.
 */
class FusionCloudObjectStorageManager(implicit account: Account)
    extends S3CompatibleStorageManager {

  private val fusionLogger = LoggerFactory.getLogger(getClass)

  /** Property key that, when "true", enables the dev-only TLS bypass below. */
  private val DISABLE_SSL_PROP = "fusion-disable-ssl-verification"

  override protected def providerName: String = "Fusion"
  override protected def endpointProperty: String = "fusion-endpoint"
  override protected def accessKeyProperty: String = "fusion-access-key"
  override protected def secretKeyProperty: String = "fusion-secret-key"

  /**
   * Fusion uses encrypted credentials (no silent fallback to plain text for non-admin users).
   * Admin uses plain text credentials, regular users use encrypted.
   */
  override protected def getAccessKey: String = {
    if (account.MY_USER == "admin") {
      account.getProperty(accessKeyProperty)
    } else {
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

  /**
   * Initialise the S3 client. Production path delegates to the parent
   * (Apache client + standard JSSE). Dev path (NooBaa self-signed cert)
   * builds a Fusion-specific client with TLS verification turned off.
   */
  override def init(): Unit = {
    if (!sslVerificationDisabled) {
      super.init()
      return
    }

    fusionLogger.warn(
      s"$DISABLE_SSL_PROP=true: TLS cert chain AND hostname verification " +
        "disabled. DEV ONLY — never use against a production Fusion endpoint.")

    val endpoint = account.getProperty(endpointProperty)
    val accessKey = getAccessKey
    val secretKey = getSecretKey

    fusionLogger.info(s"Initializing Fusion S3 Client (TLS-bypass mode)...")
    fusionLogger.info(s"Endpoint: $endpoint")

    val credentials = AwsBasicCredentials.create(accessKey, secretKey)

    // Same ApacheHttpClient as AWS / IBM COS / MinIO. TRUST_ALL_CERTIFICATES is
    // scoped to these clients only (trust-all X509 + NoopHostnameVerifier).
    val trustAll = AttributeMap.builder()
      .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, java.lang.Boolean.TRUE)
      .build()

    val chunksHttpClient = ApacheHttpClient.builder()
      .connectionTimeout(connectionTimeout)
      .socketTimeout(socketTimeout)
      .maxConnections(maxChunkConnections)
      .buildWithDefaults(trustAll)

    val systemHttpClient = ApacheHttpClient.builder()
      .connectionTimeout(connectionTimeout)
      .socketTimeout(socketTimeout)
      .maxConnections(maxSystemConnections)
      .expectContinueEnabled(java.lang.Boolean.FALSE)
      .buildWithDefaults(trustAll)

    val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
      .apiCallTimeout(apiCallTimeout)
      .apiCallAttemptTimeout(apiCallAttemptTimeout)
      .retryPolicy(RetryPolicy.builder().numRetries(numRetries).build())
      .build()

    val s3Configuration = S3Configuration.builder()
      .pathStyleAccessEnabled(true)
      .build()

    chunksS3Client = S3Client.builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .serviceConfiguration(s3Configuration)
      .httpClient(chunksHttpClient)
      .overrideConfiguration(clientOverrideConfiguration)
      .build()

    systemS3Client = S3Client.builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .serviceConfiguration(s3Configuration)
      .httpClient(systemHttpClient)
      .overrideConfiguration(clientOverrideConfiguration)
      .build()

    fusionLogger.info("Fusion S3 Client initialized successfully (TLS-bypass mode)")
  }

  private def sslVerificationDisabled: Boolean = {
    val raw = account.getProperty(DISABLE_SSL_PROP)
    raw != null && raw.equalsIgnoreCase("true")
  }
}
