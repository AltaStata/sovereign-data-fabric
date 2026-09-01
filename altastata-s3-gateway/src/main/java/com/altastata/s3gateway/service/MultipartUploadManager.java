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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.utils.Constants;

/**
 * Manages the lifecycle of S3 Multipart Uploads.
 * 
 * Maps S3's multi-step upload protocol (Create, Upload Part, Complete/Abort) to AltaStata's
 * append-only file operations.
 * 
 * Features:
 * - Thread-safe state tracking for active uploads via ConcurrentHashMap.
 * - Handles concurrent part uploads with proper ordering and streaming.
 * - Automatically concatenates parts sequentially during the 'Complete' phase using `AltaStataFileSystem`.
 */
public class MultipartUploadManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MultipartUploadManager.class);
    
    // In-memory storage for multipart upload metadata
    private final Map<String, MultipartUploadInfo> multipartUploads = new ConcurrentHashMap<>();
    private final AltaStataFileSystem altaStataFS;
    
    /**
     * Constructs MultipartUploadManager with the given filesystem context.
     *
     * @param altaStataFS backing AltaStata filesystem
     */
    public MultipartUploadManager(AltaStataFileSystem altaStataFS) {
        this.altaStataFS = altaStataFS;
    }
    
    /**
     * Initiates a new multipart upload
     */
    public String initiateMultipartUpload(String bucketName, String key, Map<String, String> metadata) {
        String uploadId = java.util.UUID.randomUUID().toString();
        MultipartUploadInfo upload = new MultipartUploadInfo(bucketName, key, uploadId);
        multipartUploads.put(uploadId, upload);
        
        logger.info("Initiated multipart upload: bucket={}, key={}, uploadId={}", bucketName, key, uploadId);
        return uploadId;
    }
    
    /**
     * Uploads a part with concurrent ordering support
     */
    public String putMultipartPart(String bucketName, String key, String uploadId, 
                                  int partNumber, InputStream content, long contentLength) {
        MultipartUploadInfo upload = getAndValidateUpload(uploadId, bucketName, key);
        // Validate part number to avoid deadlocks and invalid inputs
        if (partNumber < 1 || partNumber > 10000) {
            throw new IllegalArgumentException("Invalid part number: " + partNumber + ". Must be between 1 and 10000.");
        }
        
        // Read part content
        byte[] partContent = readPartContent(content, partNumber, contentLength);
        
        // Calculate ETag
        String etag = "\"" + java.util.UUID.randomUUID().toString().replace("-", "") + "\"";
        
        // Store part info
        upload.addPart(partNumber, etag, contentLength);
        
        // Write part with proper ordering
        writePartWithOrdering(upload, partNumber, partContent);
        
        logger.info("Successfully processed multipart part: uploadId={}, partNumber={}, size={}", 
                   uploadId, partNumber, contentLength);
        return etag;
    }
    
    /**
     * Completes a multipart upload
     */
    public String completeMultipartUpload(String bucketName, String key, String uploadId,
                                        List<S3Service.CompletedPartInfo> parts) {
        MultipartUploadInfo upload = getAndValidateUpload(uploadId, bucketName, key);
        
        // Validate parts
        validateCompletedParts(upload, parts);
        
        // Calculate total size
        long totalSize = calculateTotalSize(upload, parts);
        
        // Finalize file
        finalizeUpload(upload, key, totalSize);
        
        // Calculate final ETag
        String finalEtag = "\"" + java.util.UUID.randomUUID().toString().replace("-", "") + "-" + parts.size() + "\"";
        
        // Store the calculated ETag for the final file
        storeCalculatedETag(key, finalEtag);
        
        // Cleanup
        multipartUploads.remove(uploadId);
        
        logger.info("Successfully completed multipart upload: bucket={}, key={}, uploadId={}, finalSize={}", 
                   bucketName, key, uploadId, totalSize);
        return finalEtag;
    }
    
    /**
     * Aborts a multipart upload
     */
    public void abortMultipartUpload(String bucketName, String key, String uploadId) {
        MultipartUploadInfo upload = getAndValidateUpload(uploadId, bucketName, key);
        
        // Cleanup file and streams
        cleanupUpload(upload, key);
        
        // Remove from storage
        multipartUploads.remove(uploadId);
        
        logger.info("Successfully aborted multipart upload: bucket={}, key={}, uploadId={}", 
                   bucketName, key, uploadId);
    }
    
    /**
     * Lists parts for a multipart upload
     */
    public List<S3Service.PartInfo> listMultipartParts(String bucketName, String key, String uploadId) {
        MultipartUploadInfo upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.matches(bucketName, key)) {
            return new ArrayList<>();
        }
        
        List<S3Service.PartInfo> parts = new ArrayList<>();
        for (AltaStataPartInfo partInfo : upload.getParts().values()) {
            parts.add(new S3Service.PartInfo(partInfo.getPartNumber(), partInfo.getEtag(), 
                                           partInfo.getSize(), upload.getInitiated()));
        }
        
        parts.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
        return parts;
    }
    
    /**
     * Gets a specific part
     */
    public S3Service.PartInfo getMultipartPart(String bucketName, String key, String uploadId, int partNumber) {
        MultipartUploadInfo upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.matches(bucketName, key)) {
            return null;
        }
        
        AltaStataPartInfo partInfo = upload.getParts().get(partNumber);
        if (partInfo == null) {
            return null;
        }
        
        return new S3Service.PartInfo(partInfo.getPartNumber(), partInfo.getEtag(), 
                                    partInfo.getSize(), upload.getInitiated());
    }
    
    /**
     * Checks if multipart upload exists
     */
    public boolean multipartUploadExists(String bucketName, String key, String uploadId) {
        MultipartUploadInfo upload = multipartUploads.get(uploadId);
        return upload != null && upload.matches(bucketName, key);
    }

    /**
     * List active multipart uploads for a bucket, optionally filtered by prefix
     */
    public List<S3Service.MultipartUploadSummary> listMultipartUploads(String bucketName, String prefix) {
        List<S3Service.MultipartUploadSummary> summaries = new ArrayList<>();
        for (MultipartUploadInfo upload : multipartUploads.values()) {
            if (!upload.getBucket().equals(bucketName)) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty() && !upload.getKey().startsWith(prefix)) {
                continue;
            }
            summaries.add(new S3Service.MultipartUploadSummary(upload.getKey(), upload.getUploadId(), upload.getInitiated()));
        }
        // Sort by key then initiation time for stable output
        summaries.sort((a, b) -> {
            int cmp = a.getKey().compareTo(b.getKey());
            if (cmp != 0) return cmp;
            return Long.compare(a.getInitiated(), b.getInitiated());
        });
        return summaries;
    }
    
    // ==================== PRIVATE HELPER METHODS ====================
    
    /**
      * Gets and validates an upload.
      * @param uploadId upload identifier
      * @param bucketName bucket name
      * @param key object key
      * @return upload info
      */
    private MultipartUploadInfo getAndValidateUpload(String uploadId, String bucketName, String key) {
        MultipartUploadInfo upload = multipartUploads.get(uploadId);
        if (upload == null) {
            throw new IllegalArgumentException("Multipart upload not found: " + uploadId);
        }
        if (!upload.matches(bucketName, key)) {
            throw new IllegalArgumentException("Bucket or key mismatch for upload: " + uploadId);
        }
        return upload;
    }
    
    /**
     * Reads part content payload from an input stream.
     *
     * @param content part payload stream source
     * @param partNumber part sequence number
     * @param contentLength total expected content length in bytes
     * @return fully read part bytes
     */
    private byte[] readPartContent(InputStream content, int partNumber, long contentLength) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[Constants.PLAIN_CHUNK_MAX_SIZE()];
            int bytesRead;
            while ((bytesRead = content.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            byte[] partContent = baos.toByteArray();
            logger.debug("Part {} content read successfully ({} bytes)", partNumber, partContent.length);
            return partContent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read part content", e);
        }
    }
    
    /**
     * Writes an uploaded part to the file stream preserving strict sequence ordering.
     *
     * @param upload active multipart session info
     * @param partNumber uploaded part number
     * @param partContent uploaded part payload bytes
     */
    private void writePartWithOrdering(MultipartUploadInfo upload, int partNumber, byte[] partContent) {
        upload.getPartLock().lock();
        try {
            // Wait for our turn
            while (partNumber != upload.getNextExpectedPart()) {
                try {
                    upload.getPartCondition().await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Part upload interrupted", e);
                }
            }
            
            // Write the part
            if (partNumber == 1) {
                initializeAndWriteFirstPart(upload, partContent);
            } else {
                writeSubsequentPart(upload, partNumber, partContent);
            }
            
            // Notify waiting threads
            upload.getPartCondition().signalAll();
            
        } finally {
            upload.getPartLock().unlock();
        }
    }
    
    /**
     * Initializes the target file and writes the first sequential part.
     *
     * @param upload active multipart session info
     * @param partContent first part payload bytes
     */
    private void initializeAndWriteFirstPart(MultipartUploadInfo upload, byte[] partContent) {
        try {
            long timestamp = System.currentTimeMillis();
            upload.setTimestamp(timestamp);
            
            // Create empty file first
            CloudFileOperationStatus status = altaStataFS.createFile(upload.getKey(), new byte[0]);
            if (status.getOperationState().equals(OperationState.ERROR)) {
                throw new RuntimeException("AltaStata createFile operation failed: " + status.getError());
            }
            
            // Create output stream and write first part
            OutputStream finalOutputStream = altaStataFS.getFileOutputStream(upload.getKey(), timestamp, true);
            upload.setFinalOutputStream(finalOutputStream);
            finalOutputStream.write(partContent);
            upload.setNextExpectedPart(2);
        } catch (Exception e) {
            throw new RuntimeException("Error initializing and writing first part", e);
        }
    }
    
    /**
     * Writes any subsequent sequence part payload to the stream.
     *
     * @param upload active multipart session info
     * @param partNumber sequential part sequence number
     * @param partContent part payload bytes
     */
    private void writeSubsequentPart(MultipartUploadInfo upload, int partNumber, byte[] partContent) {
        try {
            OutputStream finalOutputStream = upload.getFinalOutputStream();
            if (finalOutputStream == null) {
                throw new RuntimeException("Final output stream not initialized");
            }
            
            finalOutputStream.write(partContent);
            upload.setNextExpectedPart(partNumber + 1);
        } catch (Exception e) {
            throw new RuntimeException("Error writing subsequent part", e);
        }
    }
    
    /**
     * Validates that all parts requested to be completed have indeed been fully uploaded with correct ETags.
     *
     * @param upload active multipart session info
     * @param parts completed parts summaries list
     */
    private void validateCompletedParts(MultipartUploadInfo upload, List<S3Service.CompletedPartInfo> parts) {
        Map<Integer, AltaStataPartInfo> storedParts = upload.getParts();
        if (storedParts.isEmpty()) {
            throw new IllegalArgumentException("No parts uploaded for multipart upload: " + upload.getUploadId());
        }
        
        for (S3Service.CompletedPartInfo completedPart : parts) {
            AltaStataPartInfo storedPart = storedParts.get(completedPart.getPartNumber());
            if (storedPart == null) {
                throw new IllegalArgumentException("Part not found: " + completedPart.getPartNumber());
            }
            if (!storedPart.getEtag().equals(completedPart.getEtag())) {
                throw new IllegalArgumentException("ETag mismatch for part: " + completedPart.getPartNumber());
            }
        }
    }
    
    /**
     * Calculates total aggregated size across all completed parts.
     *
     * @param upload active multipart session info
     * @param parts completed parts list
     * @return aggregate size in bytes
     */
    private long calculateTotalSize(MultipartUploadInfo upload, List<S3Service.CompletedPartInfo> parts) {
        long totalSize = 0;
        for (S3Service.CompletedPartInfo completedPart : parts) {
            AltaStataPartInfo storedPart = upload.getParts().get(completedPart.getPartNumber());
            totalSize += storedPart.getSize();
        }
        return totalSize;
    }
    
    /**
     * Finalizes and closes the active file stream.
     *
     * @param upload active multipart session info
     * @param key target file key path
     * @param totalSize total aggregated file size in bytes
     */
    private void finalizeUpload(MultipartUploadInfo upload, String key, long totalSize) {
        OutputStream finalOutputStream = upload.getFinalOutputStream();
        if (finalOutputStream != null) {
            try {
                finalOutputStream.flush();
                finalOutputStream.close();
                logger.info("Finalized multipart upload file: key={}, totalSize={}", key, totalSize);
            } catch (Exception e) {
                throw new RuntimeException("Error closing final output stream", e);
            }
        }
    }
    
    /**
     * Cleans up temporary resources and active streams during an abort operation.
     *
     * @param upload active multipart session info
     * @param key target key path
     */
    private void cleanupUpload(MultipartUploadInfo upload, String key) {
        // Close output stream
        OutputStream finalOutputStream = upload.getFinalOutputStream();
        if (finalOutputStream != null) {
            try {
                finalOutputStream.close();
            } catch (Exception e) {
                logger.warn("Error closing final output stream during abort: {}", e.getMessage());
            }
        }
        
        // Delete the final file
        try {
            List<CloudFileOperationStatus> results = altaStataFS.delete(key, true, null, null);
            logger.info("Successfully deleted final file during abort: {}", key);
        } catch (Exception e) {
            logger.warn("Error deleting final file during abort: {}", e.getMessage());
        }
    }
    
    /**
     * Store calculated ETag using AltaStata's attribute system
     */
    private void storeCalculatedETag(String key, String etag) {
        try {
            altaStataFS.setFileAttribute(key, null, "eTag", etag);
            logger.debug("Stored ETag {} for {}", etag, key);
        } catch (Exception e) {
            logger.warn("Failed to store ETag for {}: {}", key, e.getMessage());
        }
    }
    
    // ==================== INNER CLASSES ====================
    
    /**
     * Information about a multipart upload
     */
    private static class MultipartUploadInfo {
        private final String bucket;
        private final String key;
        private final String uploadId;
        private final Map<Integer, AltaStataPartInfo> parts;
        private final long initiated;
        private OutputStream finalOutputStream;
        private long timestamp;
        private volatile int nextExpectedPart = 1;
        private final ReentrantLock partLock = new ReentrantLock();
        private final Condition partCondition = partLock.newCondition();
        
        /**
          * Constructs a new MultipartUploadInfo instance.
          * @param bucket bucket name
          * @param key object key
          * @param uploadId upload identifier
          */
        public MultipartUploadInfo(String bucket, String key, String uploadId) {
            this.bucket = bucket;
            this.key = key;
            this.uploadId = uploadId;
            this.parts = new ConcurrentHashMap<>();
            this.initiated = System.currentTimeMillis();
        }
        
        /**
          * Checks if the upload matches the bucket and key.
          * @param bucket bucket name
          * @param key object key
          * @return true if matches
          */
        public boolean matches(String bucket, String key) {
            return this.bucket.equals(bucket) && this.key.equals(key);
        }
        
        /**
          * Adds a part to the upload.
          * @param partNumber part number
          * @param etag part etag
          * @param size part size
          */
        public void addPart(int partNumber, String etag, long size) {
            parts.put(partNumber, new AltaStataPartInfo(partNumber, etag, size));
        }
        
        // Getters and setters
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
        public Map<Integer, AltaStataPartInfo> getParts() { return parts; }
        /**
          * getInitiated operation.
          */
        public long getInitiated() { return initiated; }
        /**
          * getFinalOutputStream operation.
          */
        public OutputStream getFinalOutputStream() { return finalOutputStream; }
        public void setFinalOutputStream(OutputStream finalOutputStream) { this.finalOutputStream = finalOutputStream; }
        /**
          * getTimestamp operation.
          */
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        /**
          * getNextExpectedPart operation.
          */
        public int getNextExpectedPart() { return nextExpectedPart; }
        public void setNextExpectedPart(int nextExpectedPart) { this.nextExpectedPart = nextExpectedPart; }
        /**
          * getPartLock operation.
          */
        public ReentrantLock getPartLock() { return partLock; }
        /**
          * getPartCondition operation.
          */
        public Condition getPartCondition() { return partCondition; }
    }
    
    /**
     * Information about a part
     */
    private static class AltaStataPartInfo {
        private final int partNumber;
        private final String etag;
        private final long size;
        
        /**
          * Constructs a new AltaStataPartInfo instance.
          * @param partNumber part number
          * @param etag part etag
          * @param size part size
          */
        public AltaStataPartInfo(int partNumber, String etag, long size) {
            this.partNumber = partNumber;
            this.etag = etag;
            this.size = size;
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
    }
}
