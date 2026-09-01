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

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

import java.net.URI
import java.util.UUID
import scala.util.{Failure, Success, Try}

/**
 * MinIO Client Utility
 * 
 * Provides helper methods for creating and configuring clients for MinIO
 * using AWS SDK v2. This utility simplifies the process of connecting to MinIO
 * which is S3-compatible object storage.
 */
object MinIOClient {

  /**
   * Configuration case class for MinIO
   */
  case class MinIOConfig(
    endpoint: String,
    region: Region = Region.US_EAST_1,
    accessKey: String,
    secretKey: String,
    pathStyleAccess: Boolean = true,
    secure: Boolean = true
  )

  /**
   * Create an S3 client configured for MinIO
   */
  def createS3Client(config: MinIOConfig): S3Client = {
    val credentials = AwsBasicCredentials.create(config.accessKey, config.secretKey)
    
    val builder = S3Client.builder()
      .endpointOverride(URI.create(config.endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .region(config.region)
    
    if (config.pathStyleAccess) {
      builder.serviceConfiguration(_.pathStyleAccessEnabled(true))
    }
    
    builder.build()
  }

  /**
   * Create an S3 client with environment variable credentials
   */
  def createS3ClientFromEnv(): Try[S3Client] = {
    for {
      accessKey <- getEnvVar("MINIO_ACCESS_KEY")
      secretKey <- getEnvVar("MINIO_SECRET_KEY")
      endpoint = sys.env.getOrElse("MINIO_ENDPOINT", "http://localhost:9000")
    } yield {
      val config = MinIOConfig(
        endpoint = endpoint,
        accessKey = accessKey,
        secretKey = secretKey,
        secure = endpoint.startsWith("https://")
      )
      createS3Client(config)
    }
  }

  /**
   * Create an S3 Presigner for generating signed URLs
   */
  def createS3Presigner(config: MinIOConfig): S3Presigner = {
    val credentials = AwsBasicCredentials.create(config.accessKey, config.secretKey)
    
    S3Presigner.builder()
      .endpointOverride(URI.create(config.endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .region(config.region)
      .build()
  }

  /**
   * Create configuration for common MinIO setups
   */
  def createConfigForLocalMinIO(accessKey: String, secretKey: String, port: Int = 9000, secure: Boolean = false): MinIOConfig = {
    val protocol = if (secure) "https" else "http"
    val endpoint = s"$protocol://localhost:$port"
    
    MinIOConfig(
      endpoint = endpoint,
      region = Region.US_EAST_1,
      accessKey = accessKey,
      secretKey = secretKey,
      secure = secure
    )
  }

  /**
   * Create configuration for MinIO running in Docker
   */
  def createConfigForDockerMinIO(accessKey: String, secretKey: String, host: String = "localhost", port: Int = 9000, secure: Boolean = false): MinIOConfig = {
    val protocol = if (secure) "https" else "http"
    val endpoint = s"$protocol://$host:$port"
    
    MinIOConfig(
      endpoint = endpoint,
      region = Region.US_EAST_1,
      accessKey = accessKey,
      secretKey = secretKey,
      secure = secure
    )
  }

  /**
   * Generate bucket names for MinIO
   * 
   * MinIO bucket names follow S3 naming conventions but are typically local to the instance.
   */
  def generateUniqueBucketName(prefix: String = "altastata-demo"): String = {
    val uuid = UUID.randomUUID().toString.take(8).toLowerCase
    val timestamp = System.currentTimeMillis()
    
    s"$prefix-$uuid-$timestamp".toLowerCase
      .replaceAll("[^a-z0-9.-]", "-")  // Replace invalid chars with hyphens
      .replaceAll("-+", "-")           // Remove multiple consecutive hyphens
      .replaceAll("^-|-$", "")         // Remove leading/trailing hyphens
      .take(63)                        // Ensure max length (S3 limit)
  }

  /**
   * Validate MinIO endpoint format
   */
  def validateEndpoint(endpoint: String): Boolean = {
    try {
      val uri = URI.create(endpoint)
      uri.getHost != null && 
      (uri.getScheme == "http" || uri.getScheme == "https") &&
      uri.getPort > 0
    } catch {
      case _: Exception => false
    }
  }

  /**
   * Get default MinIO credentials for local development
   */
  def getDefaultCredentials(): (String, String) = {
    val accessKey = sys.env.getOrElse("MINIO_ACCESS_KEY", "minioadmin")
    val secretKey = sys.env.getOrElse("MINIO_SECRET_KEY", "minioadmin")
    (accessKey, secretKey)
  }

  /**
   * Helper method to get environment variable with error handling
   */
  private def getEnvVar(name: String): Try[String] = {
    sys.env.get(name) match {
      case Some(value) if value.nonEmpty => Success(value)
      case _ => Failure(new IllegalArgumentException(s"Environment variable $name is not set or empty"))
    }
  }
} 
