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

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.sts.StsClient

import java.net.URI
import java.util.UUID
import scala.util.{Failure, Success, Try}

/**
 * IBM Cloud Object Storage Client Utility
 * 
 * Provides helper methods for creating and configuring clients for IBM Cloud Object Storage
 * using AWS SDK v2. This utility simplifies the process of connecting to IBM COS
 * which is based on Ceph technology.
 */
object IBMCloudObjectStorageClient {

  /**
   * Configuration case class for IBM Cloud Object Storage
   */
  case class IBMCOSConfig(
    endpoint: String,
    region: Region = Region.US_EAST_1,
    accessKey: String,
    secretKey: String,
    pathStyleAccess: Boolean = true
  )

  /**
   * Create an S3 client configured for IBM Cloud Object Storage
   */
  def createS3Client(config: IBMCOSConfig): S3Client = {
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
      accessKey <- getEnvVar("IBM_COS_ACCESS_KEY")
      secretKey <- getEnvVar("IBM_COS_SECRET_KEY")
      endpoint = sys.env.getOrElse("IBM_COS_ENDPOINT", "https://s3.us.cloud-object-storage.appdomain.cloud")
    } yield {
      val config = IBMCOSConfig(
        endpoint = endpoint,
        accessKey = accessKey,
        secretKey = secretKey
      )
      createS3Client(config)
    }
  }

  /**
   * Create an STS client for IBM Cloud Object Storage
   */
  def createStsClient(config: IBMCOSConfig): StsClient = {
    val credentials = AwsBasicCredentials.create(config.accessKey, config.secretKey)
    
    StsClient.builder()
      .endpointOverride(URI.create(config.endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .region(config.region)
      .build()
  }

  /**
   * Create an S3 Presigner for generating signed URLs
   */
  def createS3Presigner(config: IBMCOSConfig): S3Presigner = {
    val credentials = AwsBasicCredentials.create(config.accessKey, config.secretKey)
    
    S3Presigner.builder()
      .endpointOverride(URI.create(config.endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .region(config.region)
      .build()
  }

  /**
   * Create configuration for common IBM COS regions
   */
  def createConfigForRegion(region: String, accessKey: String, secretKey: String): IBMCOSConfig = {
    val endpoint = region.toLowerCase match {
      case "us" | "us-south" => "https://s3.us.cloud-object-storage.appdomain.cloud"
      case "eu" | "eu-gb" => "https://s3.eu.cloud-object-storage.appdomain.cloud"
      case "ap" | "ap-south" => "https://s3.ap.cloud-object-storage.appdomain.cloud"
      case "jp" | "jp-tok" => "https://s3.jp-tok.cloud-object-storage.appdomain.cloud"
      case "au" | "au-syd" => "https://s3.au-syd.cloud-object-storage.appdomain.cloud"
      case _ => "https://s3.us.cloud-object-storage.appdomain.cloud"
    }
    
    IBMCOSConfig(
      endpoint = endpoint,
      region = ibmRegionToAwsRegion(region),
      accessKey = accessKey,
      secretKey = secretKey
    )
  }

  /**
   * Generate globally unique bucket names for IBM COS
   * 
   * IBM COS bucket names are globally unique across all systems, similar to AWS S3.
   */
  def generateUniqueBucketName(prefix: String = "altastata-demo"): String = {
    val uuid = UUID.randomUUID().toString.take(8).toLowerCase
    val timestamp = System.currentTimeMillis()
    
    s"$prefix-$uuid-$timestamp".toLowerCase
      .replaceAll("[^a-z0-9.-]", "-")  // Replace invalid chars with hyphens
      .replaceAll("-+", "-")           // Remove multiple consecutive hyphens
      .replaceAll("^-|-$", "")         // Remove leading/trailing hyphens
      .take(63)                        // Ensure max length (IBM COS limit)
  }

  /**
   * Convert IBM region string to AWS Region enum
   */
  def ibmRegionToAwsRegion(ibmRegion: String): Region = {
    ibmRegion.toLowerCase match {
      case "us" | "us-south" => Region.US_EAST_1
      case "eu" | "eu-gb" => Region.EU_WEST_2
      case "ap" | "ap-south" => Region.AP_SOUTH_1
      case "jp" | "jp-tok" => Region.AP_NORTHEAST_1
      case "au" | "au-syd" => Region.AP_SOUTHEAST_2
      case _ => Region.US_EAST_1 // Default fallback
    }
  }

  /**
   * Validate IBM Cloud Object Storage endpoint format
   */
  def validateEndpoint(endpoint: String): Boolean = {
    try {
      val uri = URI.create(endpoint)
      uri.getHost != null && 
      uri.getHost.contains("cloud-object-storage.appdomain.cloud") &&
      uri.getScheme == "https"
    } catch {
      case _: Exception => false
    }
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
