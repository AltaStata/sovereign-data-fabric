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

package com.altastata.s3gateway.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.s3gateway.util.ObjectTaggingXml;

/**
 * Mock S3 service for testing without AltaStata backend
 * Stores everything in memory with support for S3 user metadata
 */
public class MockS3ServiceSimple extends S3Service {
    
    private static final Logger logger = LoggerFactory.getLogger(MockS3ServiceSimple.class);
    
    // Object data storage
    public static class ObjectData {
        private final byte[] content;
        private final Map<String, String> metadata;
        private final long actualSize; // Store actual size for large files
        
        /**
          * Constructs a new ObjectData instance.
          * @param content object content
          * @param metadata object metadata
          */
        public ObjectData(byte[] content, Map<String, String> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
            this.actualSize = content.length; // For regular files, size is content length
        }
        
        /**
          * Constructs a new ObjectData instance.
          * @param content object content
          * @param metadata object metadata
          * @param actualSize actual size
          */
        public ObjectData(byte[] content, Map<String, String> metadata, long actualSize) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
            this.actualSize = actualSize; // For large files, store the actual size
        }
        
        /**
          * Gets the content.
          * @return content value
          */
        public byte[] getContent() {
            return content;
        }
        
        /**
          * Gets the metadata.
          * @return metadata value
          */
        public Map<String, String> getMetadata() {
            return new HashMap<>(metadata);
        }
        
        /**
          * Gets the actualsize.
          * @return actualsize value
          */
        public long getActualSize() {
            return actualSize;
        }
    }
    
    // In-memory storage
    private final Set<String> buckets = ConcurrentHashMap.newKeySet();
    private final Set<String> accessibleBuckets = ConcurrentHashMap.newKeySet();
    private final Map<String, ObjectData> objects = new ConcurrentHashMap<>();
    
    // Multipart upload storage
    private final Map<String, MultipartUploadData> multipartUploads = new ConcurrentHashMap<>();
    
    /**
     * Data structure for multipart uploads
     */
    public static class MultipartUploadData {
        private final String bucket;
        private final String key;
        private final String uploadId;
        private final Map<Integer, PartData> parts;
        private final long initiated;
        
        /**
          * Constructs a new MultipartUploadData instance.
          * @param bucket bucket name
          * @param key object key
          * @param uploadId upload identifier
          */
        public MultipartUploadData(String bucket, String key, String uploadId) {
            this.bucket = bucket;
            this.key = key;
            this.uploadId = uploadId;
            this.parts = new ConcurrentHashMap<>();
            this.initiated = System.currentTimeMillis();
        }
        
        /**
          * getBucket operation.
          */
        public String getBucket() { return bucket; }
        /**
          * getKey operation.
          */
        public String getKey() { return key; }
        /**
          * getUploadId operation.
          */
        public String getUploadId() { return uploadId; }
        /**
          * getParts operation.
          */
        public Map<Integer, PartData> getParts() { return parts; }
        /**
          * getInitiated operation.
          */
        public long getInitiated() { return initiated; }
    }
    
    /**
     * Data structure for multipart upload parts
     */
    public static class PartData {
        private final int partNumber;
        private final byte[] content;
        private final String etag;
        private final long size;
        private final long lastModified;
        
        /**
          * Constructs a new PartData instance.
          * @param partNumber part number
          * @param content part content
          * @param etag part etag
          */
        public PartData(int partNumber, byte[] content, String etag) {
            this.partNumber = partNumber;
            this.content = content;
            this.etag = etag;
            this.size = content.length;
            this.lastModified = System.currentTimeMillis();
        }
        
        /**
          * getPartNumber operation.
          */
        public int getPartNumber() { return partNumber; }
        /**
          * getContent operation.
          */
        public byte[] getContent() { return content; }
        /**
          * getEtag operation.
          */
        public String getEtag() { return etag; }
        /**
          * getSize operation.
          */
        public long getSize() { return size; }
        /**
          * getLastModified operation.
          */
        public long getLastModified() { return lastModified; }
    }
    
    /**
     * Constructs MockS3ServiceSimple for testing with the specified credentials.
     *
     * @param accessKey test access key
     * @param secretKey test secret key
     * @param region test AWS region
     */
    public MockS3ServiceSimple(String accessKey, String secretKey, String region) {
        logger.info("Created mock S3 service for testing with metadata support");
        
        // Pre-create some buckets for testing
        buckets.add("test-bucket");
        buckets.add("not-accessible-bucket"); // This bucket exists but is not accessible
        
        // Set accessibility
        accessibleBuckets.add("test-bucket");
        // Note: "not-accessible-bucket" is NOT added to accessibleBuckets
        // It exists in buckets but is NOT in accessibleBuckets
        
        // Ensure not-accessible-bucket is NOT in accessibleBuckets
        accessibleBuckets.remove("not-accessible-bucket");
    }
    
    /**
     * Mock implementation of virtual bucket creation.
     *
     * @param bucketName target bucket name
     * @return true if successful
     */
    @Override
    public boolean createBucket(String bucketName) {
        logger.info("Mock: Creating bucket: {} (current buckets: {})", bucketName, buckets);
        boolean added = buckets.add(bucketName);
        
        // Special case: not-accessible-bucket should not be added to accessibleBuckets
        if (!"not-accessible-bucket".equals(bucketName)) {
            accessibleBuckets.add(bucketName); // By default, new buckets are accessible
        } else {
            logger.info("Mock: Bucket {} created but NOT added to accessible buckets", bucketName);
        }
        
        logger.info("Mock: Bucket {} created: {} (buckets after: {})", bucketName, added, buckets);
        return true;
    }
    
    /**
     * Mock implementation of storing object payload in-memory.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param content raw payload bytes
     * @param metadata attached S3 metadata
     * @return true if put succeeded
     */
    @Override
    public boolean putObject(String bucketName, String key, byte[] content, Map<String, String> metadata) {
        logger.info("Mock: Putting object: {}/{} ({} bytes) with {} metadata entries", 
                   bucketName, key, content.length, metadata.size());
        
        // Log metadata for debugging
        if (!metadata.isEmpty()) {
            logger.info("Mock: Metadata for {}/{}: {}", bucketName, key, metadata);
        }
        
        // Check if bucket exists before putting object
        if (!buckets.contains(bucketName)) {
            logger.warn("Mock: Cannot put object in non-existent bucket: {}", bucketName);
            return false;
        }
        
        objects.put(bucketName + "/" + key, new ObjectData(content, metadata));
        return true;
    }
    
    /**
     * Mock implementation of storing object from streaming input.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param content stream source
     * @param size total stream size
     * @param metadata attached S3 metadata
     * @return true if put succeeded
     */
    @Override
    public boolean putObjectStream(String bucketName, String key, InputStream content, long size, Map<String, String> metadata) {
        logger.info("Mock: Starting putObjectStream: bucket={}, key={}, expectedSize={}, metadataEntries={}", 
                   bucketName, key, size, metadata.size());
        
        try {
            // Stream directly from InputStream to file storage without loading into memory
            // This prevents OutOfMemoryError for large files
            
            // Create a temporary file to store the content
            File tempFile = File.createTempFile("mock-s3-", ".tmp");
            
            byte[] buffer = new byte[64 * 1024]; // 64KB buffer
            int bytesRead;
            long totalBytesRead = 0;
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                while ((bytesRead = content.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    // Log progress for large files
                    if (totalBytesRead % (100 * 1024 * 1024) == 0) { // Every 100MB
                        logger.info("Mock: Streamed {} MB", totalBytesRead / (1024 * 1024));
                    }
                }
            }
            
            long actualSize = totalBytesRead;
            logger.info("Mock: Completed streaming {} bytes from InputStream to file (expected: {})", actualSize, size);
            
            // For mock service, we need to read the file content for in-memory storage
            // For small files (< 100MB), read into memory; for large files, use placeholder
            byte[] contentBytes;
            if (actualSize < 100 * 1024 * 1024) {
                contentBytes = java.nio.file.Files.readAllBytes(tempFile.toPath());
            } else {
                logger.info("Mock: Large file - storing placeholder (size: {} bytes)", actualSize);
                contentBytes = ("LARGE_FILE_PLACEHOLDER:" + tempFile.getAbsolutePath()).getBytes();
                // Keep the temp file for large files
            }
            
            // Check if bucket exists before putting object
            if (!buckets.contains(bucketName)) {
                logger.warn("Mock: Cannot put object in non-existent bucket: {}", bucketName);
                return false;
            }
            
            objects.put(bucketName + "/" + key, new ObjectData(contentBytes, metadata, actualSize));
            
            // Clean up temp file for small files
            if (actualSize < 100 * 1024 * 1024) {
                tempFile.delete();
            }
            
            logger.info("Mock: Successfully stored object {}/{} with actual size {}", bucketName, key, actualSize);
            return true;
            
        } catch (Exception e) {
            logger.error("Mock: Error storing object from stream {}/{}: {}", bucketName, key, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Mock implementation of getting an object input stream.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return payload stream
     */
    @Override
    public InputStream getObject(String bucketName, String key) {
        logger.info("Mock: Getting object: {}/{}", bucketName, key);
        ObjectData objectData = objects.get(bucketName + "/" + key);
        if (objectData != null) {
            return new ByteArrayInputStream(objectData.getContent());
        }
        return null;
    }
    
    /**
      * Gets object metadata.
      * @param bucketName bucket name
      * @param key object key
      * @return map of metadata
      */
    @Override
    public Map<String, String> getObjectMetadata(String bucketName, String key) {
        logger.info("Mock: Getting metadata for object: {}/{}", bucketName, key);
        ObjectData objectData = objects.get(bucketName + "/" + key);
        if (objectData != null) {
            return objectData.getMetadata();
        }
        return new HashMap<>();
    }
    
    /**
     * Mock implementation of deleting an object from in-memory map.
     *
     * @param bucketName target bucket
     * @param key target key to delete
     * @return true if deleted
     */
    @Override
    public boolean deleteObject(String bucketName, String key) {
        logger.info("Mock: Deleting object: {}/{}", bucketName, key);
        virtualReaders.keySet().removeIf(k -> k.startsWith(bucketName + "/" + key));
        virtualOwners.keySet().removeIf(k -> k.startsWith(bucketName + "/" + key));
        return objects.remove(bucketName + "/" + key) != null;
    }

    private final Map<String, String> virtualOwners = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> virtualReaders = new ConcurrentHashMap<>();

    /**
     * Helper to build fully qualified object key.
     *
     * @param bucketName bucket name
     * @param key relative key path
     * @return full key string
     */
    private String objectKey(String bucketName, String key) {
        return bucketName + "/" + key;
    }

    /**
     * Mock implementation of getting object tags.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return tagging operation result representation
     */
    @Override
    public ObjectTaggingResult getObjectTagging(String bucketName, String key) {
        logger.info("Mock: Get object tagging: {}/{}", bucketName, key);
        if (key.endsWith("/")) {
            return ObjectTaggingResult.noSuchKey();
        }
        if (!objectExists(bucketName, key)) {
            return ObjectTaggingResult.noSuchKey();
        }
        String storageKey = objectKey(bucketName, key);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(ObjectTaggingXml.TAG_OWNER, virtualOwners.getOrDefault(storageKey, "mock-owner"));
        Set<String> readers = virtualReaders.getOrDefault(storageKey, new LinkedHashSet<>());
        tags.put(ObjectTaggingXml.TAG_READERS, String.join(" ", readers));
        return ObjectTaggingResult.success(tags);
    }

    /**
     * Mock implementation of putObjectTagging.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param taggingXml S3 format tagging xml body
     * @return operation result metadata
     */
    @Override
    public ObjectTaggingResult putObjectTagging(String bucketName, String key, String taggingXml) {
        logger.info("Mock: Put object tagging: {}/{}", bucketName, key);
        ObjectTaggingXml.ParsedPutTagging parsed = ObjectTaggingXml.parsePutTagging(taggingXml);
        if (!parsed.isOk()) {
            return parsed.getError();
        }

        boolean isPrefix = key.endsWith("/");
        List<String> targetKeys = new ArrayList<>();
        if (isPrefix) {
            String prefix = bucketName + "/" + key;
            for (String storageKey : objects.keySet()) {
                if (storageKey.startsWith(prefix)) {
                    targetKeys.add(storageKey.substring((bucketName + "/").length()));
                }
            }
            if (targetKeys.isEmpty()) {
                return ObjectTaggingResult.noSuchKey();
            }
        } else {
            if (!objectExists(bucketName, key)) {
                return ObjectTaggingResult.noSuchKey();
            }
            targetKeys.add(key);
        }

        for (String targetKey : targetKeys) {
            String storageKey = objectKey(bucketName, targetKey);
            virtualOwners.putIfAbsent(storageKey, "mock-owner");
            Set<String> readers = virtualReaders.computeIfAbsent(storageKey, k -> new LinkedHashSet<>());
            if (ObjectTaggingXml.TAG_READERS_TO_ADD.equals(parsed.getActionKey())) {
                Collections.addAll(readers, parsed.getPrincipals());
            } else {
                for (String principal : parsed.getPrincipals()) {
                    readers.remove(principal);
                }
            }
        }
        return ObjectTaggingResult.success();
    }
    
    /**
     * Mock implementation of listObjects.
     *
     * @param bucketName target bucket name
     * @param prefix filter prefix
     * @return sorted list of matching object keys
     */
    @Override
    public List<String> listObjects(String bucketName, String prefix) {
        logger.info("Mock: Listing objects in bucket: {} with prefix: {}", bucketName, prefix);
        
        List<String> result = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (key.startsWith(bucketName + "/")) {
                String objectKey = key.substring((bucketName + "/").length());
                if (prefix == null || prefix.isEmpty() || objectKey.startsWith(prefix)) {
                    result.add(objectKey);
                }
            }
        }
        
        Collections.sort(result);
        logger.info("Mock: Found {} objects in bucket {} with prefix {}", result.size(), bucketName, prefix);
        return result;
    }

    /**
     * Mock implementation of listObjectsPaginated.
     *
     * @param bucketName target bucket name
     * @param prefix filter prefix
     * @param delimiter hierarchy folder delimiter
     * @param maxKeys max keys page size
     * @param continuationToken pagination marker token
     * @return structured paginated S3 listing results
     */
    @Override
    public PaginatedListResult listObjectsPaginated(String bucketName, String prefix, String delimiter, Integer maxKeys, String continuationToken) {
        logger.info("Mock: Listing objects paginated in bucket: {} with prefix: {}, delimiter: {}, maxKeys: {}, continuationToken: {}", 
                   bucketName, prefix, delimiter, maxKeys, continuationToken);
        
        int effectiveMaxKeys = (maxKeys != null) ? maxKeys : 1000;
        
        List<String> allKeys = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (key.startsWith(bucketName + "/")) {
                String objectKey = key.substring((bucketName + "/").length());
                if (prefix == null || prefix.isEmpty() || objectKey.startsWith(prefix)) {
                    allKeys.add(objectKey);
                }
            }
        }
        
        Collections.sort(allKeys);
        
        boolean foundContinuationToken = (continuationToken == null || continuationToken.isEmpty());
        List<S3ObjectSummary> objectSummaries = new ArrayList<>();
        Set<String> commonPrefixes = new HashSet<>();
        boolean isTruncated = false;
        String nextContinuationToken = null;
        
        for (String key : allKeys) {
            if (!foundContinuationToken) {
                if (key.equals(continuationToken)) {
                    foundContinuationToken = true;
                }
                continue;
            }
            
            if ((objectSummaries.size() + commonPrefixes.size()) >= effectiveMaxKeys) {
                isTruncated = true;
                nextContinuationToken = key;
                break;
            }
            
            if (delimiter != null && !delimiter.isEmpty() && key.length() > prefix.length() && key.substring(prefix.length()).contains(delimiter)) {
                int delimiterIndex = key.indexOf(delimiter, prefix.length());
                commonPrefixes.add(key.substring(0, delimiterIndex + 1));
            } else {
                // long size = getObjectSize(bucketName, key); // Stubbed for performance testing
                long size = 0; // Stub value
                long lastModified = getObjectLastModified(bucketName, key);
                // String eTag = getObjectETag(bucketName, key); // Stubbed for performance testing
                String eTag = "\"d41d8cd98f00b204e9800998ecf8427e\""; // Stub value
                objectSummaries.add(new S3ObjectSummary(key, lastModified, eTag, size));
            }
        }
        
        logger.info("Mock: Found {} objects and {} common prefixes in bucket {} with prefix {} (truncated: {})", 
                   objectSummaries.size(), commonPrefixes.size(), bucketName, prefix, isTruncated);
        
        return new PaginatedListResult(objectSummaries, new ArrayList<>(commonPrefixes), isTruncated, nextContinuationToken, effectiveMaxKeys);
    }
    
    /**
     * Mock implementation of getObjectSize.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return object size in bytes
     */
    @Override
    public long getObjectSize(String bucketName, String key) {
        ObjectData objectData = objects.get(bucketName + "/" + key);
        if (objectData != null) {
            return objectData.getActualSize();
        } else {
            return 0L;
        }
    }
    
    /**
     * Mock implementation of checking if a bucket exists.
     *
     * @param bucketName target bucket name
     * @return true if bucket exists
     */
    @Override
    public boolean bucketExists(String bucketName) {
        boolean exists = buckets.contains(bucketName);
        logger.info("Mock: Bucket {} exists: {} (all buckets: {})", bucketName, exists, buckets);
        return exists;
    }
    
    /**
     * Mock implementation of checking if a bucket is accessible.
     *
     * @param bucketName target bucket name
     * @return true if bucket is accessible
     */
    @Override
    public boolean isBucketAccessible(String bucketName) {
        boolean accessible = accessibleBuckets.contains(bucketName);
        logger.info("Mock: Bucket {} accessible: {} (accessible buckets: {})", bucketName, accessible, accessibleBuckets);
        return accessible;
    }
    
    /**
     * Alias for bucketExists.
     *
     * @param bucketName target bucket name
     * @return true if bucket exists
     */
    @Override
    public boolean doesBucketExist(String bucketName) {
        boolean exists = buckets.contains(bucketName);
        logger.info("Mock: Bucket {} exists: {} (all buckets: {})", bucketName, exists, buckets);
        return exists;
    }
    
    /**
     * Mock implementation of checking if an object exists.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return true if object exists
     */
    @Override
    public boolean objectExists(String bucketName, String key) {
        return objects.containsKey(bucketName + "/" + key);
    }
    
    /**
     * Mock implementation of getting an object last modified timestamp.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return modification timestamp in epoch milliseconds
     */
    @Override
    public long getObjectLastModified(String bucketName, String key) {
        // In a real implementation, store and return the actual last modified time
        return System.currentTimeMillis();
    }

    /**
     * Mock implementation of getting an object ETag.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @return object ETag string
     */
    @Override
    public String getObjectETag(String bucketName, String key) {
        // In a real implementation, return the MD5 hash of the content
        ObjectData objectData = objects.get(bucketName + "/" + key);
        if (objectData != null) {
            // Simple hash for demonstration (not real MD5)
            return Integer.toHexString(java.util.Arrays.hashCode(objectData.getContent()));
        }
        return "d41d8cd98f00b204e9800998ecf8427e"; // MD5 of empty string
    }
    
    /**
     * Mock implementation of getting all buckets.
     *
     * @return set of bucket name strings
     */
    @Override
    public Set<String> getBuckets() {
        return new HashSet<>(buckets);
    }
    
    /**
     * Mock implementation of downloading an object range.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param start start range byte index
     * @param end end range byte index
     * @return range content bytes
     */
    @Override
    public byte[] getObjectRange(String bucketName, String key, long start, long end) {
        logger.info("Mock: Getting object range for {}/{}: bytes {}-{}", bucketName, key, start, end);
        ObjectData objectData = objects.get(bucketName + "/" + key);
        if (objectData != null) {
            byte[] content = objectData.getContent();
            
            // Validate range
            if (start < 0 || end >= content.length || start > end) {
                logger.warn("Mock: Invalid range for {}/{}: {}-{} (content length: {})", 
                           bucketName, key, start, end, content.length);
                return null;
            }
            
            // Extract the requested range
            int rangeLength = (int)(end - start + 1);
            byte[] rangeContent = new byte[rangeLength];
            System.arraycopy(content, (int)start, rangeContent, 0, rangeLength);
            
            logger.info("Mock: Returning {} bytes for range {}-{}", rangeLength, start, end);
            return rangeContent;
        }
        logger.warn("Mock: Object not found for range request: {}/{}", bucketName, key);
        return null;
    }

    /**
     * Mock implementation of shouldReturnInvalidAccessKeyIdError.
     *
     * @param accessKey query access key ID
     * @return true if access key is invalid and should be rejected
     */
    @Override
    public boolean shouldReturnInvalidAccessKeyIdError(String accessKey) {
        // Use allowlist approach - only accept known valid access keys
        return !isValidAccessKey(accessKey);
    }
    
    /**
     * Mock implementation of checking if the access key is valid.
     *
     * @param accessKey query access key ID
     * @return true if access key is valid
     */
    @Override
    public boolean isValidAccessKey(String accessKey) {
        // Mock credentials for testing
        return !accessKey.equals("invalid_access_key_id");
    }

    @Override
    public boolean validateAwsSignature(String method, String uri, String queryString,
                                        Map<String, String> headers, String body) {
        logger.info("=== AWS SIGNATURE VALIDATION (PERMISSIVE) ===");
        logger.info("Method: {}, URI: {}", method, uri);
        // For testing purposes, be very permissive with signature validation
        // In a real implementation, validate the signature properly
        // Here, always return true (accept all signatures)
        return true;
    }
    
    /**
     * Mock implementation of validating credentials from Authorization header.
     *
     * @param authHeader Authorization header string value
     * @param bucket target bucket name
     * @param key target key path
     * @return ValidationResult object representing validity details
     */
    @Override
    public ValidationResult validateCredentials(String authHeader, String bucket, String key) {
        logger.info("Mock: Validating credentials for bucket={}, key={}", bucket, key);
        
        // Extract access key from Authorization header
        String accessKey = null;
        if (authHeader.startsWith("AWS ")) {
            // Simple AWS format: AWS accessKey:secretKey
            String credentials = authHeader.substring(4);
            int colonIndex = credentials.indexOf(':');
            if (colonIndex > 0) {
                accessKey = credentials.substring(0, colonIndex);
            }
        } else if (authHeader.contains("Credential=")) {
            // AWS4-HMAC-SHA256 format: extract from Credential parameter
            String credentialPart = authHeader.substring(authHeader.indexOf("Credential=") + 11);
            accessKey = credentialPart.substring(0, credentialPart.indexOf("/"));
        }
        
        // Check if access key is valid (only allow known good credentials)
        if (accessKey == null || !isValidAccessKey(accessKey)) {
            logger.info("Mock: Rejecting invalid access key: {}", accessKey);
            return new ValidationResult(ValidationErrorType.InvalidAccessKeyId, 
                "The AWS Access Key Id you provided does not exist in our records.");
        }
        
        // For all other cases, allow the request to proceed
        logger.info("Mock: Credentials validation passed");
        return new ValidationResult(null, null); // Valid
    }
    
    // ==================== MULTIPART UPLOAD OPERATIONS ====================
    
    /**
      * Initiates a multipart upload.
      * @param bucketName bucket name
      * @param key object key
      * @param metadata object metadata
      * @return upload identifier
      */
    @Override
    public String initiateMultipartUpload(String bucketName, String key, Map<String, String> metadata) {
        logger.info("Mock: Initiating multipart upload: bucket={}, key={}", bucketName, key);
        
        try {
            // Generate unique upload ID
            String uploadId = java.util.UUID.randomUUID().toString();
            
            // Create multipart upload metadata
            MultipartUploadData upload = new MultipartUploadData(bucketName, key, uploadId);
            multipartUploads.put(uploadId, upload);
            
            logger.info("Mock: Successfully initiated multipart upload: bucket={}, key={}, uploadId={}", 
                       bucketName, key, uploadId);
            
            return uploadId;
        } catch (Exception e) {
            logger.error("Mock: Error initiating multipart upload: bucket={}, key={}", bucketName, key, e);
            throw new RuntimeException("Failed to initiate multipart upload", e);
        }
    }

    /**
     * Mock implementation of listing active multipart uploads.
     *
     * @param bucketName target S3 bucket name
     * @param prefix filter prefix key path
     * @return list of matching multipart upload summaries
     */
    @Override
    public List<MultipartUploadSummary> listMultipartUploads(String bucketName, String prefix) {
        List<MultipartUploadSummary> summaries = new ArrayList<>();
        for (MultipartUploadData m : multipartUploads.values()) {
            if (!m.getBucket().equals(bucketName)) continue;
            if (prefix != null && !prefix.isEmpty() && !m.getKey().startsWith(prefix)) continue;
            summaries.add(new MultipartUploadSummary(m.getKey(), m.getUploadId(), m.getInitiated()));
        }
        summaries.sort((a, b) -> {
            int cmp = a.getKey().compareTo(b.getKey());
            if (cmp != 0) return cmp;
            return Long.compare(a.getInitiated(), b.getInitiated());
        });
        return summaries;
    }
    
    @Override
    public String putMultipartPart(String bucketName, String key, String uploadId, 
                                  int partNumber, InputStream content, long contentLength, 
                                  Map<String, String> metadata) {
        logger.info("Mock: Storing multipart part: bucket={}, key={}, uploadId={}, partNumber={}", 
                   bucketName, key, uploadId, partNumber);
        
        try {
            // Get or create multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                upload = new MultipartUploadData(bucketName, key, uploadId);
                multipartUploads.put(uploadId, upload);
            }
            
            // Validate bucket and key match
            if (!upload.getBucket().equals(bucketName) || !upload.getKey().equals(key)) {
                throw new IllegalArgumentException("Bucket or key mismatch for upload: " + uploadId);
            }
            
            // Read part content using streaming approach (same as putObject)
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            
            while ((bytesRead = content.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }
            
            byte[] partContent = baos.toByteArray();
            
            // Verify we read the expected amount
            if (totalBytesRead != contentLength) {
                logger.warn("Mock: Expected {} bytes but read {} bytes for part {}", contentLength, totalBytesRead, partNumber);
            }
            
            // Calculate ETag (simplified - in production this would be MD5)
            String etag = "\"" + java.util.UUID.randomUUID().toString().replace("-", "") + "\"";
            
            // Store part
            PartData partData = new PartData(partNumber, partContent, etag);
            upload.getParts().put(partNumber, partData);
            
            logger.info("Mock: Successfully stored multipart part: uploadId={}, partNumber={}, size={}, etag={}", 
                       uploadId, partNumber, contentLength, etag);
            
            return etag;
            
        } catch (Exception e) {
            logger.error("Mock: Error storing multipart part: bucket={}, key={}, uploadId={}, partNumber={}", 
                        bucketName, key, uploadId, partNumber, e);
            throw new RuntimeException("Failed to store multipart part", e);
        }
    }
    
    @Override
    public String completeMultipartUpload(String bucketName, String key, String uploadId,
                                        List<CompletedPartInfo> parts) {
        logger.info("Mock: Completing multipart upload: bucket={}, key={}, uploadId={}, parts={}", 
                   bucketName, key, uploadId, parts.size());
        
        try {
            // Get multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                throw new IllegalArgumentException("Multipart upload not found: " + uploadId);
            }
            
            // Validate bucket and key match
            if (!upload.getBucket().equals(bucketName) || !upload.getKey().equals(key)) {
                throw new IllegalArgumentException("Bucket or key mismatch for upload: " + uploadId);
            }
            
            // Validate parts
            Map<Integer, PartData> storedParts = upload.getParts();
            if (storedParts.isEmpty()) {
                throw new IllegalArgumentException("No parts uploaded for multipart upload: " + uploadId);
            }
            
            // Validate completed parts match stored parts
            for (CompletedPartInfo completedPart : parts) {
                PartData storedPart = storedParts.get(completedPart.getPartNumber());
                if (storedPart == null) {
                    throw new IllegalArgumentException("Part not found: " + completedPart.getPartNumber());
                }
                if (!storedPart.getEtag().equals(completedPart.getEtag())) {
                    throw new IllegalArgumentException("ETag mismatch for part: " + completedPart.getPartNumber());
                }
            }
            
            // Combine all parts into final object
            java.io.ByteArrayOutputStream combinedContent = new java.io.ByteArrayOutputStream();
            long totalSize = 0;
            
            // Sort parts by part number
            List<Integer> sortedPartNumbers = new ArrayList<>(storedParts.keySet());
            Collections.sort(sortedPartNumbers);
            
            for (Integer partNumber : sortedPartNumbers) {
                PartData partData = storedParts.get(partNumber);
                combinedContent.write(partData.getContent());
                totalSize += partData.getSize();
            }
            
            // Create final object
            byte[] finalContent = combinedContent.toByteArray();
            String objectKey = bucketName + "/" + key;
            ObjectData objectData = new ObjectData(finalContent, new HashMap<>(), totalSize);
            objects.put(objectKey, objectData);
            
            // Calculate final ETag (simplified - in production this would be MD5 of concatenated ETags)
            String finalEtag = "\"" + java.util.UUID.randomUUID().toString().replace("-", "") + "-" + parts.size() + "\"";
            
            // Clean up multipart upload
            multipartUploads.remove(uploadId);
            
            logger.info("Mock: Successfully completed multipart upload: bucket={}, key={}, uploadId={}, finalSize={}, finalEtag={}", 
                       bucketName, key, uploadId, totalSize, finalEtag);
            
            return finalEtag;
            
        } catch (Exception e) {
            logger.error("Mock: Error completing multipart upload: bucket={}, key={}, uploadId={}", 
                        bucketName, key, uploadId, e);
            throw new RuntimeException("Failed to complete multipart upload", e);
        }
    }
    
    /**
     * Mock implementation of aborting a multipart upload.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param uploadId target upload ID
     */
    @Override
    public void abortMultipartUpload(String bucketName, String key, String uploadId) {
        logger.info("Mock: Aborting multipart upload: bucket={}, key={}, uploadId={}", bucketName, key, uploadId);
        
        try {
            // Get multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                throw new IllegalArgumentException("Multipart upload not found: " + uploadId);
            }
            
            // Validate bucket and key match
            if (!upload.getBucket().equals(bucketName) || !upload.getKey().equals(key)) {
                throw new IllegalArgumentException("Bucket or key mismatch for upload: " + uploadId);
            }
            
            // Clean up multipart upload
            multipartUploads.remove(uploadId);
            
            logger.info("Mock: Successfully aborted multipart upload: bucket={}, key={}, uploadId={}", 
                       bucketName, key, uploadId);
            
        } catch (Exception e) {
            logger.error("Mock: Error aborting multipart upload: bucket={}, key={}, uploadId={}", 
                        bucketName, key, uploadId, e);
            throw new RuntimeException("Failed to abort multipart upload", e);
        }
    }
    
    /**
     * Mock implementation of getting a single part details.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param uploadId target upload ID
     * @param partNumber target part sequence number
     * @return part details or null if not found
     */
    @Override
    public PartInfo getMultipartPart(String bucketName, String key, String uploadId, int partNumber) {
        logger.info("Mock: Getting multipart part: bucket={}, key={}, uploadId={}, partNumber={}", 
                   bucketName, key, uploadId, partNumber);
        
        try {
            // Get multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                return null;
            }
            
            // Validate bucket and key match
            if (!upload.getBucket().equals(bucketName) || !upload.getKey().equals(key)) {
                return null;
            }
            
            // Get part
            PartData partData = upload.getParts().get(partNumber);
            if (partData == null) {
                return null;
            }
            
            return new PartInfo(partData.getPartNumber(), partData.getEtag(), 
                              partData.getSize(), partData.getLastModified());
            
        } catch (Exception e) {
            logger.error("Mock: Error getting multipart part: bucket={}, key={}, uploadId={}, partNumber={}", 
                        bucketName, key, uploadId, partNumber, e);
            return null;
        }
    }
    
    /**
     * Mock implementation of listing uploaded parts.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param uploadId target upload ID
     * @return list of written parts
     */
    @Override
    public List<PartInfo> listMultipartParts(String bucketName, String key, String uploadId) {
        logger.info("Mock: Listing multipart parts: bucket={}, key={}, uploadId={}", bucketName, key, uploadId);
        
        try {
            // Get multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                return new ArrayList<>();
            }
            
            // Validate bucket and key match
            if (!upload.getBucket().equals(bucketName) || !upload.getKey().equals(key)) {
                return new ArrayList<>();
            }
            
            // Convert to PartInfo list
            List<PartInfo> parts = new ArrayList<>();
            for (PartData partData : upload.getParts().values()) {
                parts.add(new PartInfo(partData.getPartNumber(), partData.getEtag(), 
                                     partData.getSize(), partData.getLastModified()));
            }
            
            // Sort by part number
            parts.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
            
            return parts;
            
        } catch (Exception e) {
            logger.error("Mock: Error listing multipart parts: bucket={}, key={}, uploadId={}", 
                        bucketName, key, uploadId, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Mock implementation of checking if a multipart upload session exists.
     *
     * @param bucketName target bucket name
     * @param key target object key
     * @param uploadId target upload ID
     * @return true if multipart upload exists
     */
    @Override
    public boolean multipartUploadExists(String bucketName, String key, String uploadId) {
        logger.info("Mock: Checking if multipart upload exists: bucket={}, key={}, uploadId={}", 
                   bucketName, key, uploadId);
        
        try {
            // Get multipart upload
            MultipartUploadData upload = multipartUploads.get(uploadId);
            if (upload == null) {
                return false;
            }
            
            // Validate bucket and key match
            return upload.getBucket().equals(bucketName) && upload.getKey().equals(key);
            
        } catch (Exception e) {
            logger.error("Mock: Error checking multipart upload existence: bucket={}, key={}, uploadId={}", 
                        bucketName, key, uploadId, e);
            return false;
        }
    }
} 
