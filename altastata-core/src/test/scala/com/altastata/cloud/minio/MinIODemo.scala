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

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.s3.presigner.model.{GetObjectPresignRequest, PutObjectPresignRequest}

import java.nio.ByteBuffer
import java.time.Duration
import scala.collection.JavaConverters._
import scala.util.{Random, Success, Failure, Try}

/**
 * MinIO Demo Application
 * 
 * This application demonstrates how to use AWS SDK v2 with MinIO S3-compatible object storage.
 * 
 * Usage:
 * 1. Start MinIO server locally or use existing MinIO instance
 * 2. Set environment variables or use default credentials (minioadmin/minioadmin)
 * 3. Run the application to see MinIO operations in action
 */
object MinIODemo {

  // MinIO Configuration
  private val endpoint = sys.env.getOrElse("MINIO_ENDPOINT", "http://localhost:9000")
  private val region = Region.US_EAST_1
  
  // Get credentials from environment or use defaults
  private val (accessKey, secretKey) = MinIOClient.getDefaultCredentials()
  
  // Create MinIO configuration
  private val minioConfig = MinIOClient.MinIOConfig(
    endpoint = endpoint,
    region = region,
    accessKey = accessKey,
    secretKey = secretKey,
    secure = endpoint.startsWith("https://")
  )
  
  // Generate unique bucket name for demo
  private val demoBucketName = MinIOClient.generateUniqueBucketName("altastata-minio-demo")

  // Control whether to delete the demo bucket after completion
  private val deleteBucket = sys.env.getOrElse("DELETE_DEMO_BUCKET", "false").toLowerCase == "true"

  def main(args: Array[String]): Unit = {
    println("=== MinIO Demo Application ===")
    println(s"Endpoint: $endpoint")
    println(s"Demo Bucket: $demoBucketName")
    println(s"Access Key: ${accessKey.take(8)}...")
    if (deleteBucket) println("🔒 Demo bucket will be deleted after completion (DELETE_DEMO_BUCKET=true)")
    println()
    
    // Check if using default credentials
    if (accessKey == "minioadmin" && secretKey == "minioadmin") {
      println("⚠️  Using default MinIO credentials. Set MINIO_ACCESS_KEY and MINIO_SECRET_KEY for custom credentials.")
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
      
      // Cleanup
      if (bucketCreated && deleteBucket) {
        cleanupDemoBucket()
      } else if (bucketCreated && !deleteBucket) {
        println(s"🔒 Demo bucket '$demoBucketName' kept for your inspection.")
        println(s"   To delete it later, run: aws s3 rb s3://${demoBucketName} --force --endpoint-url $endpoint")
        println(s"   Or use MinIO Console to delete it manually.")
      }
      
      println("=== Demo completed successfully! ===")
      
    } catch {
      case ex: Exception =>
        println(s"Demo completed with errors: ${ex.getMessage}")
        
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
   * Demonstrate S3 client configuration for MinIO
   */
  private def demonstrateClientConfiguration(): Unit = {
    println("📋 Client Configuration Demo")
    println("-" * 40)
    
    val client = MinIOClient.createS3Client(minioConfig)
    
    println(s"✅ S3 Client created successfully")
    println(s"   Endpoint: $endpoint")
    println(s"   Region: $region")
    println(s"   Path Style Access: enabled")
    println(s"   Secure: ${minioConfig.secure}")
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
    
    val client = MinIOClient.createS3Client(minioConfig)
    
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
    
    val client = MinIOClient.createS3Client(minioConfig)
    
    try {
      val demoFileName = "demo-file.txt"
      val demoContent = "Hello World from MinIO Demo!"
      
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
      println()
      
      // List objects in the bucket
      println("📋 Listing objects in bucket...")
      val listRequest = ListObjectsV2Request.builder()
        .bucket(demoBucketName)
        .build()
      
      val listResponse = client.listObjectsV2(listRequest)
      val objects = listResponse.contents().asScala
      
      if (objects.nonEmpty) {
        println(s"Found ${objects.size} objects:")
        objects.foreach { obj =>
          println(s"   📄 ${obj.key()} (size: ${obj.size()} bytes, modified: ${obj.lastModified()})")
        }
      }
      println()
      
      // Download and read the object
      println(s"⬇️  Downloading object: $demoFileName")
      val getRequest = GetObjectRequest.builder()
        .bucket(demoBucketName)
        .key(demoFileName)
        .build()
      
      val getResponse = client.getObject(getRequest)
      val downloadedContent = scala.io.Source.fromInputStream(getResponse).mkString
      println(s"✅ Object downloaded successfully")
      println(s"   Content: '$downloadedContent'")
      println()
      
      client.close()
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Object operations failed: ${ex.getMessage}")
        client.close()
    }
  }

  /**
   * Demonstrate advanced MinIO features
   */
  private def demonstrateAdvancedFeatures(): Unit = {
    println("🚀 Advanced Features Demo")
    println("-" * 40)
    
    val client = MinIOClient.createS3Client(minioConfig)
    val presigner = MinIOClient.createS3Presigner(minioConfig)
    
    try {
      // Generate presigned URL for upload
      println("🔗 Generating presigned URL for upload...")
      val putPresignRequest = PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofMinutes(15))
        .putObjectRequest(PutObjectRequest.builder()
          .bucket(demoBucketName)
          .key("presigned-upload.txt")
          .contentType("text/plain")
          .build())
        .build()
      
      val putPresignedUrl = presigner.presignPutObject(putPresignRequest)
      println(s"✅ Presigned upload URL generated")
      println(s"   URL: ${putPresignedUrl.url()}")
      println()
      
      // Generate presigned URL for download
      println("🔗 Generating presigned URL for download...")
      val getPresignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(Duration.ofMinutes(15))
        .getObjectRequest(GetObjectRequest.builder()
          .bucket(demoBucketName)
          .key("demo-file.txt")
          .build())
        .build()
      
      val getPresignedUrl = presigner.presignGetObject(getPresignRequest)
      println(s"✅ Presigned download URL generated")
      println(s"   URL: ${getPresignedUrl.url()}")
      println()
      
      // Demonstrate bucket policy (if supported)
      println("📜 Bucket Policy Demo")
      try {
        val policyRequest = GetBucketPolicyRequest.builder()
          .bucket(demoBucketName)
          .build()
        
        val policyResponse = client.getBucketPolicy(policyRequest)
        println(s"✅ Current bucket policy: ${policyResponse.policy()}")
      } catch {
        case _: Exception =>
          println("ℹ️  No bucket policy set (this is normal for new buckets)")
      }
      println()
      
      client.close()
      presigner.close()
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Advanced features demo failed: ${ex.getMessage}")
        client.close()
        presigner.close()
    }
  }

  /**
   * Demonstrate configuration options
   */
  private def demonstrateConfiguration(): Unit = {
    println("⚙️  Configuration Demo")
    println("-" * 40)
    
    println("Available configuration options:")
    println("   Environment Variables:")
    println("     MINIO_ENDPOINT - MinIO server endpoint (default: http://localhost:9000)")
    println("     MINIO_ACCESS_KEY - Access key (default: minioadmin)")
    println("     MINIO_SECRET_KEY - Secret key (default: minioadmin)")
    println("     DELETE_DEMO_BUCKET - Set to 'true' to delete demo bucket after completion")
    println()
    
    println("   Common MinIO endpoints:")
    println("     Local MinIO: http://localhost:9000")
    println("     Docker MinIO: http://localhost:9000")
    println("     Secure MinIO: https://localhost:9000")
    println("     Remote MinIO: https://your-minio-server.com")
    println()
    
    println("   Default credentials (for local development):")
    println("     Access Key: minioadmin")
    println("     Secret Key: minioadmin")
    println()
  }

  /**
   * Clean up the demo bucket
   */
  private def cleanupDemoBucket(): Unit = {
    println("🧹 Cleanup Demo")
    println("-" * 40)
    
    val client = MinIOClient.createS3Client(minioConfig)
    
    try {
      // Delete all objects in the bucket
      println(s"🗑️  Deleting objects in bucket: $demoBucketName")
      val listRequest = ListObjectsV2Request.builder()
        .bucket(demoBucketName)
        .build()
      
      val listResponse = client.listObjectsV2(listRequest)
      val objects = listResponse.contents().asScala
      
      objects.foreach { obj =>
        val deleteRequest = DeleteObjectRequest.builder()
          .bucket(demoBucketName)
          .key(obj.key())
          .build()
        
        client.deleteObject(deleteRequest)
        println(s"   🗑️  Deleted: ${obj.key()}")
      }
      
      // Delete the bucket
      println(s"🗑️  Deleting bucket: $demoBucketName")
      val deleteBucketRequest = DeleteBucketRequest.builder()
        .bucket(demoBucketName)
        .build()
      
      client.deleteBucket(deleteBucketRequest)
      println(s"✅ Demo bucket deleted successfully")
      println()
      
      client.close()
      
    } catch {
      case ex: Exception =>
        println(s"⚠️  Cleanup failed: ${ex.getMessage}")
        client.close()
    }
  }
} 
