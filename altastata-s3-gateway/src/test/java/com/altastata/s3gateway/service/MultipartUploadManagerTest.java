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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.s3gateway.service.S3Service.CompletedPartInfo;

@ExtendWith(MockitoExtension.class)
@Timeout(30)
class MultipartUploadManagerTest {

    @Mock
    private AltaStataFileSystem altaStataFS;

    private MultipartUploadManager multipartManager;
    private CloudFileOperationStatus successStatus;
    private List<CloudFileOperationStatus> deleteResults;

    @BeforeEach
    void setUp() {
        multipartManager = new MultipartUploadManager(altaStataFS);
        
        // Setup common mock responses
        successStatus = new CloudFileOperationStatus("test-file", OperationState.UPLOADED);
        
        deleteResults = List.of(successStatus);
    }

    @Test
    void testInitiateMultipartUpload() {
        // Given
        String bucketName = "test-bucket";
        String key = "test-file.txt";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("Content-Type", "text/plain");

        // When
        String uploadId = multipartManager.initiateMultipartUpload(bucketName, key, metadata);

        // Then
        assertNotNull(uploadId);
        assertFalse(uploadId.isEmpty());
        assertTrue(multipartManager.multipartUploadExists(bucketName, key, uploadId));
    }

    @Test
    void testPutMultipartPartSinglePart() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        byte[] data = "Hello World".getBytes();
        InputStream inputStream = new ByteArrayInputStream(data);
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        ByteArrayOutputStream mockOutputStream = new ByteArrayOutputStream();
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(mockOutputStream);

        // When
        String etag = multipartManager.putMultipartPart("bucket", "key", uploadId, 1, inputStream, data.length);

        // Then
        assertNotNull(etag);
        assertFalse(etag.isEmpty());
        assertEquals(data.length, mockOutputStream.size());
    }

    @Test
    void testPutMultipartPartsConcurrentlyInOrder() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        int numParts = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numParts);
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        ByteArrayOutputStream mockOutputStream = new ByteArrayOutputStream();
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(mockOutputStream);
        
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            // When - upload parts concurrently in order (1, 2, 3, 4, 5)
            for (int i = 1; i <= numParts; i++) {
                final int partNumber = i;
                byte[] partData = ("Part " + partNumber + " data").getBytes();
                
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        InputStream inputStream = new ByteArrayInputStream(partData);
                        return multipartManager.putMultipartPart("bucket", "key", uploadId, partNumber, inputStream, partData.length);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
                
                futures.add(future);
            }

            // Then - all parts should complete successfully
            List<String> etags = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                String etag = future.get(10, TimeUnit.SECONDS);
                assertNotNull(etag);
                etags.add(etag);
            }

            // Verify all parts were uploaded
            assertEquals(numParts, etags.size());
            
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testPutMultipartPartsConcurrentlyOutOfOrder() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        ByteArrayOutputStream mockOutputStream = new ByteArrayOutputStream();
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(mockOutputStream);
        
        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<Integer> uploadOrder = List.of(3, 1, 2); // Upload out of order

        try {
            // When - upload parts out of order (3, 1, 2)
            for (int partNumber : uploadOrder) {
                byte[] partData = ("Part " + partNumber + " data").getBytes();
                
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Add small delay to ensure parts arrive out of order
                        if (partNumber == 3) Thread.sleep(10);
                        
                        InputStream inputStream = new ByteArrayInputStream(partData);
                        return multipartManager.putMultipartPart("bucket", "key", uploadId, partNumber, inputStream, partData.length);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
                
                futures.add(future);
            }

            // Then - all parts should complete successfully despite out-of-order upload
            for (CompletableFuture<String> future : futures) {
                String etag = future.get(10, TimeUnit.SECONDS);
                assertNotNull(etag);
            }

            // Verify the content was written in the correct order (1, 2, 3)
            String finalContent = mockOutputStream.toString();
            assertTrue(finalContent.contains("Part 1 data"));
            assertTrue(finalContent.contains("Part 2 data"));
            assertTrue(finalContent.contains("Part 3 data"));
            
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testListMultipartParts() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(new ByteArrayOutputStream());
        
        // Upload some parts
        for (int i = 1; i <= 3; i++) {
            byte[] data = ("Part " + i).getBytes();
            InputStream inputStream = new ByteArrayInputStream(data);
            multipartManager.putMultipartPart("bucket", "key", uploadId, i, inputStream, data.length);
        }

        // When
        List<S3Service.PartInfo> parts = multipartManager.listMultipartParts("bucket", "key", uploadId);

        // Then
        assertEquals(3, parts.size());
        
        // Verify parts are sorted by part number
        for (int i = 0; i < parts.size(); i++) {
            assertEquals(i + 1, parts.get(i).getPartNumber());
            assertNotNull(parts.get(i).getEtag());
            assertTrue(parts.get(i).getSize() > 0);
        }
    }

    @Test
    void testCompleteMultipartUpload() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        ByteArrayOutputStream mockOutputStream = new ByteArrayOutputStream();
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(mockOutputStream);
        
        // Upload parts
        List<CompletedPartInfo> completedParts = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            byte[] data = ("Part " + i + " content").getBytes();
            InputStream inputStream = new ByteArrayInputStream(data);
            String etag = multipartManager.putMultipartPart("bucket", "key", uploadId, i, inputStream, data.length);
            completedParts.add(new CompletedPartInfo(i, etag));
        }

        // When
        String finalETag = multipartManager.completeMultipartUpload("bucket", "key", uploadId, completedParts);

        // Then
        assertNotNull(finalETag);
        assertFalse(finalETag.isEmpty());
        assertFalse(multipartManager.multipartUploadExists("bucket", "key", uploadId)); // Upload should be cleaned up
        
        // Verify final content contains all parts in order
        String finalContent = mockOutputStream.toString();
        assertTrue(finalContent.contains("Part 1 content"));
        assertTrue(finalContent.contains("Part 2 content"));
        assertTrue(finalContent.contains("Part 3 content"));
    }

    @Test
    void testAbortMultipartUpload() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(new ByteArrayOutputStream());
        when(altaStataFS.delete(eq("key"), eq(true), isNull(), isNull())).thenReturn(deleteResults);
        
        // Upload a part
        byte[] data = "test data".getBytes();
        InputStream inputStream = new ByteArrayInputStream(data);
        multipartManager.putMultipartPart("bucket", "key", uploadId, 1, inputStream, data.length);

        // When
        multipartManager.abortMultipartUpload("bucket", "key", uploadId);

        // Then
        assertFalse(multipartManager.multipartUploadExists("bucket", "key", uploadId)); // Upload should be cleaned up
        verify(altaStataFS).delete(eq("key"), eq(true), isNull(), isNull()); // File should be deleted
    }

    @Test
    void testGetMultipartPart() throws Exception {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(eq("key"), any(byte[].class))).thenReturn(successStatus);
        when(altaStataFS.getFileOutputStream(eq("key"), anyLong(), eq(true))).thenReturn(new ByteArrayOutputStream());
        
        // Upload a part
        byte[] data = "test part data".getBytes();
        InputStream inputStream = new ByteArrayInputStream(data);
        String expectedETag = multipartManager.putMultipartPart("bucket", "key", uploadId, 1, inputStream, data.length);

        // When
        S3Service.PartInfo partInfo = multipartManager.getMultipartPart("bucket", "key", uploadId, 1);

        // Then
        assertNotNull(partInfo);
        assertEquals(1, partInfo.getPartNumber());
        assertEquals(expectedETag, partInfo.getEtag());
        assertEquals(data.length, partInfo.getSize());
    }

    @Test
    void testMultipartUploadNotFound() {
        // Given
        String nonExistentUploadId = "non-existent-upload-id";

        // put should throw because upload doesn't exist
        assertThrows(IllegalArgumentException.class, () ->
            multipartManager.putMultipartPart("bucket", "key", nonExistentUploadId, 1,
                new ByteArrayInputStream("data".getBytes()), 4)
        );

        // list should return empty list for unknown upload
        assertTrue(multipartManager.listMultipartParts("bucket", "key", nonExistentUploadId).isEmpty());

        // complete should throw because upload doesn't exist
        assertThrows(IllegalArgumentException.class, () ->
            multipartManager.completeMultipartUpload("bucket", "key", nonExistentUploadId, Collections.emptyList())
        );
    }

    @Test
    void testConcurrentMultipleUploads() throws Exception {
        // Given
        int numUploads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numUploads * 2);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Mock AltaStata filesystem behavior
        when(altaStataFS.createFile(anyString(), any(byte[].class))).thenReturn(successStatus);
        when(altaStataFS.getFileOutputStream(anyString(), anyLong(), eq(true))).thenAnswer(invocation -> new ByteArrayOutputStream());

        try {
            // When - start multiple concurrent multipart uploads
            for (int uploadNum = 1; uploadNum <= numUploads; uploadNum++) {
                final int uploadNumber = uploadNum;
                
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String key = "file" + uploadNumber + ".txt";
                        String uploadId = multipartManager.initiateMultipartUpload("bucket", key, new HashMap<>());
                        
                        // Upload 3 parts for each upload
                        List<CompletedPartInfo> parts = new ArrayList<>();
                        for (int partNum = 1; partNum <= 3; partNum++) {
                            byte[] data = ("Upload " + uploadNumber + " Part " + partNum).getBytes();
                            InputStream inputStream = new ByteArrayInputStream(data);
                            String etag = multipartManager.putMultipartPart("bucket", key, uploadId, partNum, inputStream, data.length);
                            parts.add(new CompletedPartInfo(partNum, etag));
                        }
                        
                        // Complete the upload with the actual returned ETags
                        multipartManager.completeMultipartUpload("bucket", key, uploadId, parts);
                        
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
                
                futures.add(future);
            }

            // Then - all uploads should complete successfully
            for (CompletableFuture<Void> future : futures) {
                assertDoesNotThrow(() -> future.get(15, TimeUnit.SECONDS));
            }
            
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void testInvalidPartNumber() {
        // Given
        String uploadId = multipartManager.initiateMultipartUpload("bucket", "key", new HashMap<>());
        byte[] data = "test".getBytes();

        // When & Then - test invalid part numbers
        assertThrows(IllegalArgumentException.class, () -> {
            multipartManager.putMultipartPart("bucket", "key", uploadId, 0, 
                new ByteArrayInputStream(data), data.length);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            multipartManager.putMultipartPart("bucket", "key", uploadId, 10001, 
                new ByteArrayInputStream(data), data.length);
        });
    }

    @Test
    void testListMultipartUploadsSummaries() {
        // Given
        String bucket = "bucket";
        String uploadId1 = multipartManager.initiateMultipartUpload(bucket, "prefix/fileA.txt", new HashMap<>());
        String uploadId2 = multipartManager.initiateMultipartUpload(bucket, "other/fileB.txt", new HashMap<>());

        // When
        List<S3Service.MultipartUploadSummary> all = multipartManager.listMultipartUploads(bucket, null);
        List<S3Service.MultipartUploadSummary> prefixed = multipartManager.listMultipartUploads(bucket, "prefix/");

        // Then
        assertTrue(all.stream().anyMatch(s -> s.getUploadId().equals(uploadId1)));
        assertTrue(all.stream().anyMatch(s -> s.getUploadId().equals(uploadId2)));

        assertEquals(1, prefixed.size());
        assertEquals("prefix/fileA.txt", prefixed.get(0).getKey());
    }
}
