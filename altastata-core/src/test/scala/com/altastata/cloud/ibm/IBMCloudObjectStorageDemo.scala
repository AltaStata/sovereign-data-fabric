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

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest

import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import scala.collection.JavaConverters._
import scala.util.{Random, Success, Failure, Try}

/**
 * IBM Cloud Object Storage Demo Application
 * 
 * This application demonstrates how to use AWS SDK v2 with IBM Cloud Object Storage (Ceph-based).
 * Based on Ceph S3 Java examples: https://docs.ceph.com/en/latest/radosgw/s3/java/
 * 
 * Usage:
 * 1. Replace demoAccessKey and demoSecretKey with actual HMAC credentials
 * 2. Run the application to see IBM COS operations in action
 */
object IBMCloudObjectStorageDemo {

  // IBM Cloud Object Storage Configuration
  private val endpoint = "https://s3.us.cloud-object-storage.appdomain.cloud"
  private val region = Region.US_EAST_1
  
  // IBM Service Credentials - Use environment variables for security
  private val apiKey = sys.env.getOrElse("IBM_CLOUD_API_KEY", "demo-api-key-placeholder")
  private val serviceInstanceId = sys.env.getOrElse("IBM_CLOUD_SERVICE_INSTANCE_ID", "demo-service-instance-id-placeholder")
  
  // Placeholder credentials for demo - replace with real HMAC credentials
  private val demoAccessKey = sys.env.getOrElse("IBM_COS_ACCESS_KEY", "demo-access-key")
  private val demoSecretKey = sys.env.getOrElse("IBM_COS_SECRET_KEY", "demo-secret-key")
  
  // Create IBM COS configuration
  private val cosConfig = IBMCloudObjectStorageClient.IBMCOSConfig(
    endpoint = endpoint,
    region = region,
    accessKey = demoAccessKey,
    secretKey = demoSecretKey
  )
  
  // Generate globally unique bucket name using utility
  private val demoBucketName = IBMCloudObjectStorageClient.generateUniqueBucketName("altastata-demo")

  // Control whether to delete the demo bucket after completion (default: keep for inspection)
  private val deleteBucket = sys.env.getOrElse("DELETE_DEMO_BUCKET", "false").toLowerCase == "true"

  def main(args: Array[String]): Unit = {
    println("=== IBM Cloud Object Storage Demo ===")
    println(s"Endpoint: $endpoint")
    println(s"Demo Bucket: $demoBucketName")
    println(s"Service Instance: ${serviceInstanceId.take(50)}...")
    if (deleteBucket) println("🔒 Demo bucket will be deleted after completion (DELETE_DEMO_BUCKET=true)")
    println()
    
    // Check if real credentials are provided
    if (demoAccessKey == "demo-access-key") {
      println("⚠️  Using placeholder credentials. Set IBM_COS_ACCESS_KEY and IBM_COS_SECRET_KEY environment variables for real operations.")
      println()
    }

    var bucketCreated = false

    try {
      // Run demonstrations in proper order
      demonstrateClientConfiguration()
      bucketCreated = demonstrateBucketCreation()
      
      if (bucketCreated) {
      demonstrateObjectOperations()
      demonstrateAdvancedFeatures()
      }
      
      demonstrateConfiguration()
      
      // Cleanup (unless user wants to delete the bucket)
      if (bucketCreated && deleteBucket) {
        cleanupDemoBucket()
      } else if (bucketCreated && !deleteBucket) {
        println(s"🔒 Demo bucket '$demoBucketName' kept for your inspection.")
        println(s"   To delete it later, run: aws s3 rb s3://${demoBucketName} --force --profile ibm-cos --endpoint-url $endpoint")
        println(s"   Or use IBM Cloud Console to delete it manually.")
      }
      
      println("=== Demo completed successfully! ===")
      
    } catch {
      case ex: Exception =>
        println(s"Demo completed with errors (expected with placeholder credentials): ${ex.getMessage}")
        
        // Attempt cleanup even if there were errors
        if (bucketCreated && deleteBucket) {
          try {
            cleanupDemoBucket()
          } catch {
            case cleanupEx: Exception =>
              println(s"⚠️  Cleanup failed: ${cleanupEx.getMessage}")
          }
        }
        
        println("=== Demo finished ===")
    }
  }

  /**
   * Demonstrate S3 client configuration following Ceph documentation
   */
  private def demonstrateClientConfiguration(): Unit = {
    println("📋 Client Configuration Demo")
    println("-" * 40)
    
    val client = IBMCloudObjectStorageClient.createS3Client(cosConfig)
    
    println(s"✅ S3 Client created successfully")
    println(s"   Endpoint: $endpoint")
    println(s"   Region: $region")
    println(s"   Path Style Access: enabled")
    println()
    
    client.close()
  }

  /**
   * Demonstrate bucket creation and listing
   * @return true if bucket was created successfully
   */
  private def demonstrateBucketCreation(): Boolean = {
    println("🪣 Bucket Operations Demo")
    println("-" * 40)
    
    val client = IBMCloudObjectStorageClient.createS3Client(cosConfig)
    
    try {
      // List existing buckets
      println("📝 Listing existing buckets...")
      val listResponse = client.listBuckets()
      val buckets = listResponse.buckets().asScala
      
      if (buckets.nonEmpty) {
        println(s"Found ${buckets.size} existing buckets:")
        buckets.foreach { bucket =>
          println(s"   📁 ${bucket.name()} (created: ${bucket.creationDate()})")
        }
      } else {
        println("   No existing buckets found")
      }
      println()
      
      // Create a new bucket for the demo
      println(s"🆕 Creating demo bucket: $demoBucketName")
      val createRequest = CreateBucketRequest.builder()
        .bucket(demoBucketName)
        .build()
      
      val createResponse = client.createBucket(createRequest)
      println(s"✅ Demo bucket created successfully")
      if (createResponse.location() != null) {
        println(s"   Location: ${createResponse.location()}")
      }
      
      println()
      client.close()
      return true
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Bucket creation failed: ${ex.getMessage}")
        client.close()
        return false
    }
  }

  /**
   * Demonstrate object operations using the created bucket
   */
  private def demonstrateObjectOperations(): Unit = {
    println("📄 Object Operations Demo")
    println("-" * 40)
    
    val client = IBMCloudObjectStorageClient.createS3Client(cosConfig)
    
    try {
      val demoFileName = "demo-file.txt"
      val demoContent = "Hello World from IBM Cloud Object Storage Demo!"
      
      // Upload an object
      println(s"⬆️  Uploading object: $demoFileName")
      val input = ByteBuffer.wrap(demoContent.getBytes("UTF-8"))
      val putRequest = PutObjectRequest.builder()
        .bucket(demoBucketName)
        .key(demoFileName)
        .contentType("text/plain")
        .contentLength(demoContent.length.toLong)
        .build()
      
      client.putObject(putRequest, RequestBody.fromByteBuffer(input))
      println(s"✅ Object uploaded successfully")
      
      // List objects in bucket
      println(s"📝 Listing objects in bucket: $demoBucketName")
      val listRequest = ListObjectsRequest.builder()
        .bucket(demoBucketName)
        .build()
      
      val listResponse = client.listObjects(listRequest)
      val objects = listResponse.contents().asScala
      
      if (objects.nonEmpty) {
        println(s"Found ${objects.size} objects:")
        objects.foreach { obj =>
          println(s"   📄 ${obj.key()} (${obj.size()} bytes, modified: ${obj.lastModified()})")
        }
      } else {
        println("   No objects found")
      }
      
      // Download and verify object
      println(s"⬇️  Downloading object: $demoFileName")
      val getRequest = GetObjectRequest.builder()
        .bucket(demoBucketName)
        .key(demoFileName)
        .build()
      
      val getResponse = client.getObject(getRequest)
      val downloadedContent = scala.io.Source.fromInputStream(getResponse).mkString
      getResponse.close()
      println(s"✅ Object downloaded successfully")
      println(s"   Content: '$downloadedContent'")
      
      // Set object ACL (if supported)
      try {
      println(s"🔒 Setting ACL for object: $demoFileName")
      val aclRequest = PutObjectAclRequest.builder()
        .bucket(demoBucketName)
        .key(demoFileName)
        .acl(ObjectCannedACL.PUBLIC_READ)
        .build()
      
      client.putObjectAcl(aclRequest)
      println(s"✅ ACL set to PUBLIC_READ")
      } catch {
        case aclEx: Exception =>
          println(s"⚠️  ACL operation not supported or failed: ${aclEx.getMessage}")
      }
      
      println()
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Object operations failed: ${ex.getMessage}")
    }
    
    client.close()
  }

  /**
   * Demonstrate advanced features
   */
  private def demonstrateAdvancedFeatures(): Unit = {
    println("🚀 Advanced Features Demo")
    println("-" * 40)
    
    // Presigned URLs
    demonstratePresignedUrls()
    
    // STS Operations
    demonstrateStsOperations()
  }

  private def demonstratePresignedUrls(): Unit = {
    val presigner = IBMCloudObjectStorageClient.createS3Presigner(cosConfig)
    
    try {
      println("🔗 Generating presigned URL...")
      val documentKey = "demo-file.txt" // Use existing file
      
      val getObjectRequest = GetObjectRequest.builder()
        .bucket(demoBucketName)
        .key(documentKey)
        .build()
      
      val presignRequest = presigner.presignGetObject { builder =>
        builder
          .getObjectRequest(getObjectRequest)
          .signatureDuration(Duration.ofMinutes(20))
      }
      
      val presignedUrl = presignRequest.url().toString
      println(s"✅ Presigned URL generated:")
      println(s"   URL: ${presignedUrl.take(100)}...")
      println(s"   Valid for: 20 minutes")
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Presigned URL generation failed: ${ex.getMessage}")
    }
    
    presigner.close()
  }

  private def demonstrateStsOperations(): Unit = {
    val stsClient = IBMCloudObjectStorageClient.createStsClient(cosConfig)
    
    try {
      println("🎫 Generating session token...")
      val sessionRequest = GetSessionTokenRequest.builder()
        .durationSeconds(3600)
        .build()
      
      val response = stsClient.getSessionToken(sessionRequest)
      val credentials = response.credentials()
      
      println(s"✅ Session token generated:")
      println(s"   Access Key ID: ${credentials.accessKeyId().take(10)}...")
      println(s"   Expires: ${credentials.expiration()}")
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  STS operation failed: ${ex.getMessage}")
    }
    
    println()
    stsClient.close()
  }

  /**
   * Clean up the demo bucket and its contents
   */
  private def cleanupDemoBucket(): Unit = {
    println("🧹 Cleaning up demo bucket...")
    
    val client = IBMCloudObjectStorageClient.createS3Client(cosConfig)
    
    try {
      // First, delete all objects in the bucket
      val listRequest = ListObjectsRequest.builder()
        .bucket(demoBucketName)
        .build()
      
      val listResponse = client.listObjects(listRequest)
      val objects = listResponse.contents().asScala
      
      if (objects.nonEmpty) {
        println(s"   Deleting ${objects.size} objects...")
        objects.foreach { obj =>
          val deleteRequest = DeleteObjectRequest.builder()
            .bucket(demoBucketName)
            .key(obj.key())
            .build()
          
          client.deleteObject(deleteRequest)
          println(s"   🗑️  Deleted object: ${obj.key()}")
        }
      }
      
      // Then delete the bucket
      println(s"   🗑️  Deleting bucket: $demoBucketName")
      val deleteRequest = DeleteBucketRequest.builder()
        .bucket(demoBucketName)
        .build()
      
      client.deleteBucket(deleteRequest)
      println(s"✅ Demo bucket deleted successfully")
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Cleanup failed: ${ex.getMessage}")
    }
    
    client.close()
  }

  /**
   * Demonstrate configuration validation
   */
  private def demonstrateConfiguration(): Unit = {
    println("⚙️  Configuration Validation")
    println("-" * 40)
    
    // Validate endpoint
    val endpointUri = URI.create(endpoint)
    println(s"Endpoint validation:")
    println(s"   Host: ${endpointUri.getHost}")
    println(s"   Scheme: ${endpointUri.getScheme}")
    println(s"   Valid IBM COS endpoint: ${IBMCloudObjectStorageClient.validateEndpoint(endpoint)}")
    
    // Validate credentials format
    println(s"Credential validation:")
    println(s"   API Key length: ${apiKey.length} characters")
    println(s"   Service Instance ID format: ${serviceInstanceId.startsWith("crn:v1:bluemix:public:cloud-object-storage")}")
    
    // Validate configuration
    println(s"AWS SDK configuration:")
    println(s"   Region: $region")
    println(s"   Path style access: enabled")
    println(s"   Authentication: HMAC credentials")
    
    // Show bucket naming strategy
    println(s"Bucket naming:")
    println(s"   Generated unique bucket: $demoBucketName")
    
    println()
  }
} 
