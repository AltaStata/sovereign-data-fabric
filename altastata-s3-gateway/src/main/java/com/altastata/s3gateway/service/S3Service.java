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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.s3gateway.util.AwsChunkedDecodingChecksumInputStream;
import com.altastata.s3gateway.util.AwsUnsignedChunkedDecodingChecksumInputStream;

/**
 * Abstract base class for S3 service implementations
 * Defines the common interface for all S3 operations with simplified authentication
 * and includes business logic functionality extracted from S3Controller
 */
public abstract class S3Service {

    private static final Logger logger = LoggerFactory.getLogger(S3Service.class);

    // Authorization header pattern
    private static final Pattern AUTH_PATTERN = Pattern.compile(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/([^/]+)/([^/]+)/([^/]+)/aws4_request, SignedHeaders=([^,]+), Signature=(.+)"
    );

    // ==================== INNER CLASSES ====================

    /**
     * Authorization components extracted from the Authorization header
     */
    private static class AuthorizationComponents {
        String accessKey;
        String date;
        String region;
        String service;
        String signedHeaders;
        String signature;

        AuthorizationComponents(String accessKey, String date, String region, String service,
                                String signedHeaders, String signature) {
            this.accessKey = accessKey;
            this.date = date;
            this.region = region;
            this.service = service;
            this.signedHeaders = signedHeaders;
            this.signature = signature;
        }
    }

    /**
     * Copy source information
     */
    public static class CopySource {
        private final String sourceBucket;
        private final String sourceKey;

        /**
          * Constructs a new CopySource instance.
          * @param sourceBucket source bucket name
          * @param sourceKey source object key
          */
        public CopySource(String sourceBucket, String sourceKey) {
            this.sourceBucket = sourceBucket;
            this.sourceKey = sourceKey;
        }

        /**
          * getSourceBucket operation.
          */
        public String getSourceBucket() { return sourceBucket; }
        /**
          * getSourceKey operation.
          */
        public String getSourceKey() { return sourceKey; }
    }

    /**
     * Validation error types
     */
    public enum ValidationErrorType {
        AccessDenied,
        InvalidAccessKeyId,
        NoSuchBucket,
        SignatureDoesNotMatch
    }

    /**
     * Validation result
     */
    public static class ValidationResult {
        private final ValidationErrorType errorType;
        private final String message;

        /**
          * Constructs a new ValidationResult instance.
          * @param errorType error type
          * @param message validation message
          */
        public ValidationResult(ValidationErrorType errorType, String message) {
            this.errorType = errorType;
            this.message = message;
        }

        /**
          * getErrorType operation.
          */
        public ValidationErrorType getErrorType() { return errorType; }
        /**
          * getMessage operation.
          */
        public String getMessage() { return message; }
        public boolean isValid() { return errorType == null; }
    }

    /**
     * Parsed range information
     */
    public static class ParsedRange {
        private final long start;
        private Long end; // null if not specified, can be modified

        /**
          * Constructs a new ParsedRange instance.
          * @param start range start
          * @param end range end
          */
        public ParsedRange(long start, Long end) {
            this.start = start;
            this.end = end;
        }

        /**
          * getStart operation.
          */
        public long getStart() { return start; }
        /**
          * getEnd operation.
          */
        public Long getEnd() { return end; }
        public void setEnd(long end) { this.end = end; }
    }

    /**
     * Result of range request with validation
     */
    public static class RangeResult {
        private final byte[] content;
        private final long objectSize;
        private final long start;
        private final long end;

        /**
          * Constructs a new RangeResult instance.
          * @param content range content
          * @param objectSize total object size
          * @param start range start
          * @param end range end
          */
        public RangeResult(byte[] content, long objectSize, long start, long end) {
            this.content = content;
            this.objectSize = objectSize;
            this.start = start;
            this.end = end;
        }

        /**
          * getContent operation.
          */
        public byte[] getContent() { return content; }
        /**
          * getObjectSize operation.
          */
        public long getObjectSize() { return objectSize; }
        /**
          * getStart operation.
          */
        public long getStart() { return start; }
        /**
          * getEnd operation.
          */
        public long getEnd() { return end; }
        /**
          * getLength operation.
          */
        public long getLength() { return end - start + 1; }
    }

    // ==================== ABSTRACT S3 OPERATIONS ====================

    /**
     * Get all buckets (for testing)
     */
    public abstract Set<String> getBuckets();

    /**
     * Create a bucket
     */
    public abstract boolean createBucket(String bucketName);

    /**
     * Store an object with metadata
     */
    public abstract boolean putObject(String bucketName, String key, byte[] content, Map<String, String> metadata);

    /**
     * Store an object from InputStream with metadata (for large files)
     */
    public abstract boolean putObjectStream(String bucketName, String key, InputStream content, long size, Map<String, String> metadata);

    /**
     * Get an object
     */
    public abstract InputStream getObject(String bucketName, String key);

    /**
     * Get object metadata
     */
    public abstract Map<String, String> getObjectMetadata(String bucketName, String key);

    /**
     * Delete an object
     */
    public abstract boolean deleteObject(String bucketName, String key);

    /**
     * Get virtual object tags (owner, readers) for GET ?tagging.
     */
    public abstract ObjectTaggingResult getObjectTagging(String bucketName, String key);

    /**
     * Apply share/unshare via PUT ?tagging XML body.
     */
    public abstract ObjectTaggingResult putObjectTagging(String bucketName, String key, String taggingXml);

    /**
     * List objects in a bucket
     */
    public abstract List<String> listObjects(String bucketName, String prefix);

    /**
     * List objects in a bucket with pagination support
     * @param bucketName The bucket name
     * @param prefix The prefix to filter by
     * @param delimiter The delimiter for hierarchical listing
     * @param maxKeys Maximum number of keys to return (null for no limit)
     * @param continuationToken Token to continue from previous request (null for start)
     * @return PaginatedListResult containing the list of keys and pagination info
     */
    public abstract PaginatedListResult listObjectsPaginated(String bucketName, String prefix, String delimiter, Integer maxKeys, String continuationToken);

    /**
     * Result class for paginated listing
     */
    public static class PaginatedListResult {
        private final List<S3ObjectSummary> objects;
        private final List<String> commonPrefixes;
        private final boolean isTruncated;
        private final String nextContinuationToken;
        private final int maxKeys;

        /**
          * Constructs a new PaginatedListResult instance.
          * @param objects list of objects
          * @param commonPrefixes list of common prefixes
          * @param isTruncated whether the list is truncated
          * @param nextContinuationToken next continuation token
          * @param maxKeys max keys returned
          */
        public PaginatedListResult(List<S3ObjectSummary> objects, List<String> commonPrefixes, boolean isTruncated, String nextContinuationToken, int maxKeys) {
            this.objects = objects;
            this.commonPrefixes = commonPrefixes;
            this.isTruncated = isTruncated;
            this.nextContinuationToken = nextContinuationToken;
            this.maxKeys = maxKeys;
        }

        /**
          * getObjects operation.
          */
        public List<S3ObjectSummary> getObjects() { return objects; }
        /**
          * getCommonPrefixes operation.
          */
        public List<String> getCommonPrefixes() { return commonPrefixes; }
        /**
          * isTruncated operation.
          */
        public boolean isTruncated() { return isTruncated; }
        /**
          * getNextContinuationToken operation.
          */
        public String getNextContinuationToken() { return nextContinuationToken; }
        /**
          * getMaxKeys operation.
          */
        public int getMaxKeys() { return maxKeys; }
    }

    /**
     * Summary of an S3 object containing key and metadata
     */
    public static class S3ObjectSummary {
        private final String key;
        private final long lastModified;
        private final String eTag;
        private final long size;

        /**
          * Constructs a new S3ObjectSummary instance.
          * @param key object key
          * @param lastModified last modified timestamp
          * @param eTag object etag
          * @param size object size
          */
        public S3ObjectSummary(String key, long lastModified, String eTag, long size) {
            this.key = key;
            this.lastModified = lastModified;
            this.eTag = eTag;
            this.size = size;
        }

        /**
          * getKey operation.
          */
        public String getKey() { return key; }
        /**
          * getLastModified operation.
          */
        public long getLastModified() { return lastModified; }
        /**
          * getETag operation.
          */
        public String getETag() { return eTag; }
        /**
          * getSize operation.
          */
        public long getSize() { return size; }
    }

    /**
     * Get object size
     */
    public abstract long getObjectSize(String bucketName, String key);

    /**
     * Check if bucket exists
     */
    public abstract boolean bucketExists(String bucketName);

    /**
     * Check if object exists
     */
    public abstract boolean objectExists(String bucketName, String key);

    /**
     * Get object last modified time
     */
    public abstract long getObjectLastModified(String bucketName, String key);

    /**
     * Get object ETag
     */
    public abstract String getObjectETag(String bucketName, String key);

    /**
     * Get object with range support (partial content)
     */
    public abstract byte[] getObjectRange(String bucketName, String key, long start, long end);
    
    // ==================== MULTIPART UPLOAD OPERATIONS ====================
    
    /**
     * Initiate a multipart upload
     * @param bucketName The bucket name
     * @param key The object key
     * @param metadata Additional metadata for the upload
     * @return Upload ID for the multipart upload
     */
    public abstract String initiateMultipartUpload(String bucketName, String key, Map<String, String> metadata);
    
    /**
     * Store a multipart upload part
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     * @param partNumber The part number
     * @param content The part content
     * @param metadata Additional metadata for the part
     * @return ETag of the uploaded part
     */
    public abstract String putMultipartPart(String bucketName, String key, String uploadId, 
                                           int partNumber, InputStream content, long contentLength, 
                                           Map<String, String> metadata);
    
    /**
     * Complete a multipart upload by combining all parts
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     * @param parts List of completed parts with their ETags
     * @return Final ETag of the completed object
     */
    public abstract String completeMultipartUpload(String bucketName, String key, String uploadId,
                                                  List<CompletedPartInfo> parts);
    
    /**
     * Abort a multipart upload and clean up all parts
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     */
    public abstract void abortMultipartUpload(String bucketName, String key, String uploadId);
    
    /**
     * Get information about a multipart upload part
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     * @param partNumber The part number
     * @return Part information or null if not found
     */
    public abstract PartInfo getMultipartPart(String bucketName, String key, String uploadId, int partNumber);
    
    /**
     * List all parts of a multipart upload
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     * @return List of part information
     */
    public abstract List<PartInfo> listMultipartParts(String bucketName, String key, String uploadId);
    
    /**
     * Check if a multipart upload exists
     * @param bucketName The bucket name
     * @param key The object key
     * @param uploadId The upload ID
     * @return true if the upload exists
     */
    public abstract boolean multipartUploadExists(String bucketName, String key, String uploadId);
    
    /**
     * Summary of an active multipart upload
     * TODO: API present; behavior covered in unit tests for manager but not full E2E controller tests yet
     */
    public static class MultipartUploadSummary {
        private final String key;
        private final String uploadId;
        private final long initiated;

        /**
          * Constructs a new MultipartUploadSummary instance.
          * @param key object key
          * @param uploadId upload identifier
          * @param initiated initiation timestamp
          */
        public MultipartUploadSummary(String key, String uploadId, long initiated) {
            this.key = key;
            this.uploadId = uploadId;
            this.initiated = initiated;
        }

        /**
          * getKey operation.
          */
        public String getKey() { return key; }
        /**
          * getUploadId operation.
          */
        public String getUploadId() { return uploadId; }
        /**
          * getInitiated operation.
          */
        public long getInitiated() { return initiated; }
    }

    /**
     * List active multipart uploads for a bucket, optionally filtered by prefix
     * @param bucketName The bucket name
     * @param prefix Optional key prefix filter (nullable)
     * @return List of multipart upload summaries
     */
    public abstract List<MultipartUploadSummary> listMultipartUploads(String bucketName, String prefix);
    
    /**
     * Information about a completed part in multipart upload
     */
    public static class CompletedPartInfo {
        private final int partNumber;
        private final String etag;
        
        /**
          * Constructs a new CompletedPartInfo instance.
          * @param partNumber part number
          * @param etag part etag
          */
        public CompletedPartInfo(int partNumber, String etag) {
            this.partNumber = partNumber;
            this.etag = etag;
        }
        
        /**
          * getPartNumber operation.
          */
        public int getPartNumber() { return partNumber; }
        /**
          * getEtag operation.
          */
        public String getEtag() { return etag; }
    }
    
    /**
     * Information about a part in multipart upload
     */
    public static class PartInfo {
        private final int partNumber;
        private final String etag;
        private final long size;
        private final long lastModified;
        
        /**
          * Constructs a new PartInfo instance.
          * @param partNumber part number
          * @param etag part etag
          * @param size part size
          * @param lastModified last modified timestamp
          */
        public PartInfo(int partNumber, String etag, long size, long lastModified) {
            this.partNumber = partNumber;
            this.etag = etag;
            this.size = size;
            this.lastModified = lastModified;
        }
        
        /**
          * getPartNumber operation.
          */
        public int getPartNumber() { return partNumber; }
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
     * Copy object using streaming approach to support large files
     * @param sourceBucket Source bucket name
     * @param sourceKey Source object key
     * @param destBucket Destination bucket name
     * @param destKey Destination object key
     * @return true if copy was successful
     */
    public boolean copyObjectStreaming(String sourceBucket, String sourceKey, String destBucket, String destKey) {
        try {
            logger.info("Starting streaming copy from {}/{} to {}/{}", sourceBucket, sourceKey, destBucket, destKey);
            
            // Get source object as stream
            InputStream sourceStream = getObject(sourceBucket, sourceKey);
            if (sourceStream == null) {
                logger.error("Source object not found: {}/{}", sourceBucket, sourceKey);
                return false;
            }
            
            // Get source object metadata to preserve it
            Map<String, String> sourceMetadata = getObjectMetadata(sourceBucket, sourceKey);
            long sourceSize = getObjectSize(sourceBucket, sourceKey);
            
            logger.info("Copying object with size {} bytes and {} metadata entries", sourceSize, sourceMetadata.size());
            
            // Stream directly to destination without loading into memory
            boolean success = putObjectStream(destBucket, destKey, sourceStream, sourceSize, sourceMetadata);
            
            // Close source stream
            try {
                sourceStream.close();
            } catch (Exception e) {
                logger.warn("Error closing source stream: {}", e.getMessage());
            }
            
            if (success) {
                logger.info("Successfully completed streaming copy from {}/{} to {}/{}", 
                           sourceBucket, sourceKey, destBucket, destKey);
            } else {
                logger.error("Failed streaming copy from {}/{} to {}/{}", 
                           sourceBucket, sourceKey, destBucket, destKey);
            }
            
            return success;
            
        } catch (Exception e) {
            logger.error("Error during streaming copy from {}/{} to {}/{}: {}", 
                        sourceBucket, sourceKey, destBucket, destKey, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Get object range with ParsedRange validation and resolution
     * @param bucketName The bucket name
     * @param key The object key
     * @param parsedRange The parsed range (will be modified if end is null)
     * @return Object range content and object size
     * @throws IllegalArgumentException if range is invalid
     * @throws RuntimeException if object doesn't exist
     */
    public RangeResult getObjectRangeWithValidation(String bucketName, String key, ParsedRange parsedRange) {
        // Validate and resolve range
        long objectSize = validateAndResolveRange(bucketName, key, parsedRange);
        
        // Get the actual range content
        byte[] content = getObjectRange(bucketName, key, parsedRange.getStart(), parsedRange.getEnd());
        if (content == null) {
            throw new RuntimeException("Failed to retrieve object range: " + bucketName + "/" + key);
        }
        
        return new RangeResult(content, objectSize, parsedRange.getStart(), parsedRange.getEnd());
    }
    
    /**
     * Validate and resolve range, modifying ParsedRange in place if needed
     * @param bucket The bucket name
     * @param key The object key
     * @param parsedRange The parsed range from header (will be modified if end is null)
     * @return The object size (useful for Content-Range header)
     * @throws IllegalArgumentException if range is invalid
     * @throws RuntimeException if object doesn't exist or can't get object size
     */
    public long validateAndResolveRange(String bucket, String key, ParsedRange parsedRange) {
        if (parsedRange == null) {
            throw new IllegalArgumentException("Parsed range cannot be null");
        }
        
        long start = parsedRange.getStart();
        
        // Get object size for validation and to resolve open-ended ranges
        long objectSize = getObjectSize(bucket, key);
        if (objectSize <= 0) {
            throw new RuntimeException("Object not found or has invalid size: " + bucket + "/" + key);
        }
        
        // Resolve end position if not specified
        if (parsedRange.getEnd() == null) {
            // Open-ended range like "bytes=1000-" - resolve to end of object
            parsedRange.setEnd(objectSize - 1);
        }
        
        long end = parsedRange.getEnd();
        
        // Validate range
        if (start < 0 || start >= objectSize || end >= objectSize || start > end) {
            throw new IllegalArgumentException("Invalid range " + start + "-" + end + " for object size " + objectSize);
        }
        
        return objectSize;
    }

    // ==================== AUTHENTICATION AND VALIDATION ====================

    /**
     * Validate if the access key is valid (known)
     */
    public abstract boolean isValidAccessKey(String accessKey);

    /**
     * Determine if an access key should return InvalidAccessKeyId error
     */
    public abstract boolean shouldReturnInvalidAccessKeyIdError(String accessKey);

    /**
     * Validate AWS credentials - delegates invalid key logic to implementation
     */
    public ValidationResult validateCredentials(String authHeader, String bucket, String key) {
        if (authHeader == null || authHeader.trim().isEmpty()) {
            return new ValidationResult(ValidationErrorType.AccessDenied, "Access Denied");
        }

        String accessKey = extractAccessKey(authHeader);
        if (accessKey == null || accessKey.trim().isEmpty()) {
            return new ValidationResult(ValidationErrorType.InvalidAccessKeyId,
                "The AWS Access Key Id you provided does not exist in our records.");
        }

        if (shouldReturnInvalidAccessKeyIdError(accessKey)) {
            return new ValidationResult(ValidationErrorType.InvalidAccessKeyId,
                "The AWS Access Key Id you provided does not exist in our records.");
        }

        if (!doesBucketExist(bucket)) {
            return new ValidationResult(ValidationErrorType.NoSuchBucket,
                "The specified bucket does not exist.");
        }

        if (!isBucketAccessible(bucket)) {
            return new ValidationResult(ValidationErrorType.AccessDenied, "Access Denied");
        }

        return null; // Valid
    }

    /**
     * Check if bucket exists and is accessible
     */
    public abstract boolean isBucketAccessible(String bucket);

    /**
     * Check if bucket exists (different from accessible - some buckets exist but aren't accessible)
     */
    public abstract boolean doesBucketExist(String bucket);

    /**
     * Validate AWS signature
     */
    public abstract boolean validateAwsSignature(String method, String uri, String queryString,
                                        Map<String, String> headers, String body);

    // ==================== CONTENT PROCESSING ====================

    /**
     * Process and store object with streaming to S3
     * This eliminates the need for interim files and memory buffering
     */
    public String processAndStoreObject(String bucket, String key, InputStream inputStream,
                                        String decodedContentLength, boolean isChunked,
                                        Map<String, String> userMetadata, long expectedSize) {
        logger.info("Processing and storing object: bucket={}, key={}, expectedSize={}", bucket, key, expectedSize);
        
        try {
            // Only use chunked processing if we actually have chunked content
            if (isChunked) {
                logger.info("Using chunked processing path: isChunked={}, decodedContentLength={}, expectedSize={}", isChunked, decodedContentLength, expectedSize);
                // Handle chunked content - decode and stream directly to S3
                return processAwsChunkedAndStore(bucket, key, inputStream, decodedContentLength, isChunked, userMetadata, expectedSize);
            } else {
                logger.info("Using regular processing path: isChunked={}, decodedContentLength={}, expectedSize={}", isChunked, decodedContentLength, expectedSize);
                // Handle regular content - stream directly to S3
                return processRegularAndStore(bucket, key, inputStream, userMetadata, expectedSize);
            }
        } catch (Exception e) {
            logger.error("Error processing and storing object: bucket={}, key={}", bucket, key, e);
            throw e;
        }
    }

    /**
     * Process AWS chunked content and write directly to S3
     */
    private String processAwsChunkedAndStore(String bucket, String key, InputStream inputStream,
                                             String decodedContentLength, boolean isChunked,
                                             Map<String, String> userMetadata, long expectedSize) {
        logger.info("Starting processAwsChunkedAndStore: bucket={}, key={}, expectedSize={}", bucket, key, expectedSize);
        
        try {
            // Use the expectedSize parameter instead of parsing again
            long decodedLength = expectedSize >= 0 ? expectedSize : (decodedContentLength != null ? Long.parseLong(decodedContentLength) : -1);
            logger.info("Decoded content length: {}", decodedLength);

            // Create appropriate decoder based on signing
            InputStream decodingStream;
            if (isChunked) {
                logger.info("Creating AwsChunkedDecodingChecksumInputStream for chunked content");
                decodingStream = new AwsChunkedDecodingChecksumInputStream(inputStream, decodedLength);
            } else {
                logger.info("Creating AwsUnsignedChunkedDecodingChecksumInputStream for unsigned chunked content");
                decodingStream = new AwsUnsignedChunkedDecodingChecksumInputStream(inputStream, decodedLength);
            }

            // Calculate MD5 while streaming to S3
            MessageDigest md5Digest = MessageDigest.getInstance("MD5");
            java.security.DigestInputStream digestStream = new java.security.DigestInputStream(decodingStream, md5Digest);

            // Stream directly to S3 without interim file
            long totalDecodedBytes = putObjectStreamWithProgress(bucket, key, digestStream, decodedLength, userMetadata);
            logger.info("AWS chunked streaming completed, total bytes: {}", totalDecodedBytes);

            String calculatedMD5 = bytesToHex(md5Digest.digest());
            logger.info("Successfully processed and stored AWS chunked content: {} bytes, MD5: {}", totalDecodedBytes, calculatedMD5);

            return calculatedMD5;

        } catch (Exception e) {
            logger.error("Error processing AWS chunked content: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to process chunked content", e);
        }
    }

    /**
     * Process regular content and write directly to S3
     */
    private String processRegularAndStore(String bucket, String key, InputStream inputStream,
                                          Map<String, String> userMetadata, long expectedSize) {
        logger.info("Starting processRegularAndStore: bucket={}, key={}, expectedSize={}", bucket, key, expectedSize);
        
        try {
            // Calculate MD5 while streaming to S3
            MessageDigest md5Digest = MessageDigest.getInstance("MD5");
            java.security.DigestInputStream digestStream = new java.security.DigestInputStream(inputStream, md5Digest);
            
            // Debug: Log what we're about to process
            logger.info("=== CONTENT PROCESSING DEBUG ===");
            logger.info("Input stream type: {}", inputStream.getClass().getSimpleName());
            logger.info("User metadata: {}", userMetadata);
            logger.info("=== END CONTENT PROCESSING DEBUG ===");

            // Stream directly to S3
            long totalBytes = putObjectStreamWithProgress(bucket, key, digestStream, expectedSize, userMetadata);
            logger.info("Regular streaming completed, total bytes: {}", totalBytes);

            // Calculate MD5 hash for ETag
            byte[] md5Hash = md5Digest.digest();
            String calculatedMD5 = bytesToHex(md5Hash);

/*
            // Debug: Log the actual content being hashed
            logger.info("=== MD5 CALCULATION DEBUG ===");
            logger.info("Total bytes processed: {}", totalBytes);
            logger.info("MD5 hash (hex): {}", calculatedMD5);
            logger.info("MD5 hash (base64): {}", bytesToBase64(md5Hash));
            
            // Log first few bytes of content for debugging
            if (totalBytes > 0) {
                // We can't access the content directly since it's been streamed
                // But we can log what we know about the stream
                logger.info("Content was streamed via digestStream with MD5 calculation");
                logger.info("Digest stream processed {} bytes", totalBytes);
            }
            logger.info("=== END MD5 CALCULATION DEBUG ===");
            
            logger.info("Successfully processed and stored regular content: {} bytes, MD5: {}", totalBytes, calculatedMD5);
*/

            return calculatedMD5;

        } catch (Exception e) {
            logger.error("Error processing regular content: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to process content", e);
        }
    }

    /**
     * Store implementations return false on failure; propagate as an exception so
     * S3Controller returns 5xx instead of 200 with a bogus ETag.
     */
    private void requirePutSuccess(boolean success, String bucket, String key) {
        if (!success) {
            throw new RuntimeException("Failed to store object: " + bucket + "/" + key);
        }
    }

    /**
     * Stream content to S3 with progress logging
     */
    private long putObjectStreamWithProgress(String bucket, String key, InputStream inputStream,
                                             long expectedSize, Map<String, String> userMetadata) {
        logger.info("Starting putObjectStreamWithProgress: bucket={}, key={}, expectedSize={}", bucket, key, expectedSize);
        
        try {
            // Stream directly to S3 without any temporary files
            // The inputStream is already processed (decoded if chunked, with MD5 calculation)
            // We just need to pass it to the S3 storage implementation

            final AtomicLong totalBytes = new AtomicLong(0);
            final AtomicLong lastLoggedBytes = new AtomicLong(0);
            final long logInterval = 100 * 1024 * 1024; // 100MB - less verbose logging
            
            // Create a counting wrapper to track bytes and log progress
            InputStream countingStream = new InputStream() {
                /**
                  * Reads a byte of data.
                  * @return byte read or -1
                  * @throws IOException on error
                  */
                @Override
                public int read() throws IOException {
                    int b = inputStream.read();
                    if (b != -1) {
                        long currentTotal = totalBytes.incrementAndGet();
                        if (currentTotal - lastLoggedBytes.get() >= logInterval) {
                            logger.info("Upload progress: {} MB processed", currentTotal / (1024 * 1024));
                            lastLoggedBytes.set(currentTotal);
                        }
                    }
                    return b;
                }

                /**
                  * Reads a sequence of bytes.
                  * @param b byte array
                  * @param off offset
                  * @param len length
                  * @return bytes read or -1
                  * @throws IOException on error
                  */
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int bytesRead = inputStream.read(b, off, len);
                    if (bytesRead > 0) {
                        long currentTotal = totalBytes.addAndGet(bytesRead);
                        if (currentTotal - lastLoggedBytes.get() >= logInterval) {
                            logger.info("Upload progress: {} MB processed", currentTotal / (1024 * 1024));
                            lastLoggedBytes.set(currentTotal);
                        }
                    }
                    return bytesRead;
                }

                /**
                  * Closes the resource.
                  * @throws IOException on error
                  */
                @Override
                public void close() throws IOException {
                    inputStream.close();
                }
            };

/*
            // Store directly to S3 using the processed stream
            logger.info("=== STREAMING DEBUG ===");
            logger.info("About to call putObjectStream with countingStream");
            logger.info("Expected size: {}", expectedSize);
            logger.info("User metadata: {}", userMetadata);
            logger.info("=== END STREAMING DEBUG ===");
*/

            if (expectedSize != 0 && expectedSize <= 5 * 8 * 1024 * 1024) {
                logger.info("putObjectStreamWithProgress run putObject: {}", expectedSize);

                byte[] content = inputStreamToByteArray(countingStream);
                requirePutSuccess(putObject(bucket, key, content, userMetadata), bucket, key);
            }
            else {
                logger.info("putObjectStreamWithProgress run putObjectStream: {}", expectedSize);

                requirePutSuccess(putObjectStream(bucket, key, countingStream, expectedSize, userMetadata), bucket, key);
            }

            long finalTotal = totalBytes.get();
            logger.info("putObjectStreamWithProgress completed: {} bytes processed", finalTotal);

            return finalTotal;

        } catch (Exception e) {
            logger.error("Error streaming to S3: bucket={}, key={}, expectedSize={}", bucket, key, expectedSize, e);
            throw new RuntimeException("Failed to stream to S3", e);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Convert InputStream to byte array
     */
    private byte[] inputStreamToByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[8192]; // 8KB buffer
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    /**
     * Convert byte array to hex string
     */
    public String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Convert byte array to base64 string
     */
    public String bytesToBase64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Parse XML to extract object keys for bulk delete operations
     */
    public List<String> parseDeleteKeysFromXml(String xmlBody) {
        List<String> keysToDelete = new ArrayList<>();
        if (xmlBody != null && xmlBody.contains("<Key>")) {
            // Simple regex to extract keys from XML
            Pattern pattern = Pattern.compile("<Key>(.*?)</Key>");
            Matcher matcher = pattern.matcher(xmlBody);
            while (matcher.find()) {
                keysToDelete.add(matcher.group(1));
            }
        }
        return keysToDelete;
    }

    /**
     * Parse copy source header
     */
    public CopySource parseCopySource(String copySourceHeader) {
        String copySource = copySourceHeader.startsWith("/") ? copySourceHeader.substring(1) : copySourceHeader;
        String[] parts = copySource.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid copy source format: " + copySourceHeader);
        }
        return new CopySource(parts[0], parts[1]);
    }

    /**
     * Generate presigned URL that can be accessed without authentication
     */
    public String generatePresignedUrl(String baseUrl, String bucket, String key, Integer expiryTime) {
        // Generate a presigned URL that bypasses authentication
        // This simulates AWS S3 presigned URLs that work without additional auth headers
        String presignedUrl = String.format("%s/%s/%s", baseUrl, bucket, key);

        // Add query parameters to indicate this is a presigned request
        StringBuilder queryParams = new StringBuilder();
        queryParams.append("?X-Amz-Algorithm=AWS4-HMAC-SHA256");
        queryParams.append("&X-Amz-Credential=testkey%2F20240101%2Fus-east-1%2Fs3%2Faws4_request");
        queryParams.append("&X-Amz-Date=20240101T120000Z");
        queryParams.append("&X-Amz-Expires=3600");
        queryParams.append("&X-Amz-SignedHeaders=host");
        queryParams.append("&X-Amz-Signature=test-signature");

        // Add expiry time if specified
        if (expiryTime != null && expiryTime > 0) {
            queryParams.append("&expires=").append(expiryTime);
        }

        presignedUrl += queryParams.toString();
        return presignedUrl;
    }

    /**
     * Parse Range header and return start/end values
     * @param rangeHeader Range header value (e.g., "bytes=0-8")
     * @return ParsedRange with start and end positions, or null if invalid
     */
    public ParsedRange parseRangeHeader(String rangeHeader) {
        try {
            // Parse Range header (e.g., "bytes=0-8" or "bytes=50-")
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                return null;
            }
            
            String rangeSpec = rangeHeader.substring(6); // Remove "bytes="
            
            // Handle open-ended ranges properly
            if (!rangeSpec.contains("-")) {
                return null;
            }
            
            int dashIndex = rangeSpec.indexOf("-");
            String startPart = rangeSpec.substring(0, dashIndex);
            String endPart = rangeSpec.substring(dashIndex + 1);
            
            // Parse start position
            if (startPart.isEmpty()) {
                return null;
            }
            
            long start = Long.parseLong(startPart);
            Long end = endPart.isEmpty() ? null : Long.parseLong(endPart);
            
            // Basic validation
            if (start < 0 || (end != null && start > end)) {
                return null;
            }
            
            return new ParsedRange(start, end);
            
        } catch (Exception e) {
            logger.warn("Error parsing range header '{}': {}", rangeHeader, e.getMessage());
            return null;
        }
    }

    /**
     * Extract a query parameter from a query string
     */
    private String extractQueryParameter(String queryString, String paramName) {
        if (queryString == null || paramName == null) {
            return null;
        }
        
        String[] params = queryString.split("&");
        for (String param : params) {
            if (param.startsWith(paramName + "=")) {
                return param.substring(paramName.length() + 1);
            }
        }
        return null;
    }

    /**
     * Extract access key from authorization header
     */
    private String extractAccessKey(String authHeader) {
        if (authHeader.startsWith("AWS4-HMAC-SHA256")) {
            if (authHeader.contains("Credential=")) {
                String credentialPart = authHeader.substring(authHeader.indexOf("Credential=") + 11);
                return credentialPart.substring(0, credentialPart.indexOf("/"));
            }
        } else if (authHeader.startsWith("AWS ")) {
            String credentials = authHeader.substring(4);
            if (credentials.contains(":")) {
                return credentials.substring(0, credentials.indexOf(":"));
            }
        }
        return null;
    }
}
