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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CephS3Test {
    private static final Logger logger = LoggerFactory.getLogger(CephS3Test.class);
    
    private static final String ENDPOINT = "http://127.0.0.1:8081";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String BUCKET_NAME = "test-bucket";

    public static void main(String[] args) {

        // Configure S3 to use path-style access and disable chunked encoding
        S3Configuration serviceConfiguration = S3Configuration.builder()
            .chunkedEncodingEnabled(false)
            .build();

        // Configure the client with more specific settings
        S3Client s3Client = S3Client.builder()
            .endpointOverride(URI.create(ENDPOINT))
            .region(Region.US_EAST_1) // Required but not used by Ceph
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
                )
            )
            .serviceConfiguration(serviceConfiguration)
            .forcePathStyle(true)
            .build();

        try {
            // Test bucket operations
            testBucketOperations(s3Client);
            
            // Test object operations
            testObjectOperations(s3Client);
            
        } catch (Exception e) {
            logger.error("Error during S3 operations", e);
        } finally {
            s3Client.close();
        }
    }

    private static void testBucketOperations(S3Client s3Client) {
        try {
            // Create bucket
            CreateBucketRequest createBucketRequest = CreateBucketRequest.builder()
                .bucket(BUCKET_NAME)
                .build();
            s3Client.createBucket(createBucketRequest);
            logger.info("Created bucket: {}", BUCKET_NAME);

            // List buckets
            ListBucketsResponse listBucketsResponse = s3Client.listBuckets();
            List<Bucket> buckets = listBucketsResponse.buckets();
            logger.info("Available buckets:");
            buckets.forEach(bucket -> logger.info("- {}", bucket.name()));

        } catch (Exception e) {
            logger.error("Error during bucket operations", e);
        }
    }

    private static void testObjectOperations(S3Client s3Client) {
        try {
            String objectKey = "test-object";
            String content = "Hello, Ceph S3!";

            // Put object
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(objectKey)
                .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(content));
            logger.info("Put object: {}", objectKey);

            // Get object
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(objectKey)
                .build();

            String retrievedContent = s3Client.getObjectAsBytes(getObjectRequest)
                .asString(StandardCharsets.UTF_8);
            logger.info("Retrieved object content: {}", retrievedContent);

        } catch (Exception e) {
            logger.error("Error during object operations", e);
        }
    }
} 
