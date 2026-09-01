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

package com.altastata.performance.altastata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;

/**
 * Performance test for enhanced Iterator with background processing.
 * 
 * Tests AltaStata with an enhanced Iterator that:
 * - Wraps listCloudFilesVersions() Iterator
 * - Processes getFileAttributes() in background batches of 400
 * - Returns processed results as you iterate
 * - No waiting - results stream in as you consume the Iterator
 *
 * @author AltaStata
 */
/**
 * Performance test for enhanced Iterator with background processing.
 * 
 * Tests AltaStata with an enhanced Iterator that:
 * - Wraps listCloudFilesVersions() Iterator
 * - Processes getFileAttributes() in background batches of 400
 * - Returns processed results as you iterate
 * - No waiting - results stream in as you consume the Iterator
 *
 * @author AltaStata
 */
public class PerformanceTestBatchProcessingFilesList {

    private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestBatchProcessingFilesList.class);
    
    // Test configuration
    private static final int BATCH_SIZE = 400;
    private static final String TEST_PATH = "test-suite/page-listing-test";
    private static final String ATTRIBUTE_NAME = "size";
    
    private static AltaStataFileSystem altaStataFileSystem;
    private static ExecutorService backgroundExecutor;
    private static long totalStartTime;
    private static long totalFilesProcessed = 0;
    private static final List<Long> individualFileTimes = new ArrayList<>();
    private static String accountPath;
    
    /**
     * Main execution entry point for batch processing performance tests.
     * Starts and orchestrates multi-threaded baseline listing and attribute retrieval benchmarks.
     *
     * @param args command-line arguments (unused)
     * @throws Exception if execution fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 AltaStata Performance Test - Batch Processing Files List");
        System.out.println("==================================================");
        
        // Initialize AltaStata
        initializeAltaStata();
        
        // Initialize executor
        backgroundExecutor = Executors.newFixedThreadPool(8); // 8 parallel threads

        try {
            // Test 0: Baseline - No attributes (just file listing)
            System.out.println("\n📊 TEST 0: BASELINE - NO ATTRIBUTES (just file listing)");
            System.out.println("==================================================");
            runBaselineTestMultipleTimes();
            
            // Test 1: Size attribute (3 runs with average)
            System.out.println("\n📊 TEST 1: SIZE ATTRIBUTE (3 runs with average)");
            System.out.println("==================================================");
            runSizeAttributeTestMultipleTimes();
            
            // Test 2: Readers attribute (3 runs with average)
            System.out.println("\n📊 TEST 2: READERS ATTRIBUTE (3 runs with average)");
            System.out.println("==================================================");
            runReadersAttributeTestMultipleTimes();
            
            // Test 3: Size + eTag attributes (3 runs with average)
            System.out.println("\n📊 TEST 3: SIZE + ETAG ATTRIBUTES (3 runs with average)");
            System.out.println("==================================================");
            runSizeAndETagTestMultipleTimes();
            
        } finally {
            // Cleanup
            if (backgroundExecutor != null) {
                backgroundExecutor.shutdown();
                if (!backgroundExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            }
        }

        System.out.println("\n=== TEST COMPLETED SUCCESSFULLY ===");
    }

    /**
     * Initializes the AltaStataFileSystem from {@code ALTASTATA_ACCOUNT_DIR}, or a local MinIO account.
     */
    private static void initializeAltaStata() {
        try {
            String fromEnv = System.getenv("ALTASTATA_ACCOUNT_DIR");
            if (fromEnv == null || fromEnv.isEmpty()) {
                throw new IllegalStateException("Set ALTASTATA_ACCOUNT_DIR to an AltaStata account directory");
            }
            accountPath = fromEnv;
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            System.out.println("✓ AltaStata initialized successfully");
            System.out.println("  Account path: " + accountPath);
            
        } catch (Exception e) {
            System.err.println("✗ Failed to initialize AltaStata: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Process a batch of files in parallel
     */
    private static List<FileAttributeResult> processBatchSequentially(List<String> filePaths, int batchNum) {
        List<FileAttributeResult> results = new ArrayList<>();
        
        // Process files in parallel within the batch
        List<CompletableFuture<FileAttributeResult>> futures = filePaths.stream()
            .map(filePath -> CompletableFuture.supplyAsync(() -> {
                return processFileAttribute(filePath);
            }, backgroundExecutor))
            .collect(java.util.stream.Collectors.toList());
        
        // Wait for all files in this batch to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        for (CompletableFuture<FileAttributeResult> future : futures) {
            try {
                FileAttributeResult result = future.get();
                results.add(result);
            } catch (Exception e) {
                System.err.println("Error getting result from future: " + e.getMessage());
            }
        }
        
        System.out.println("  ✓ Batch " + batchNum + " completed with " + results.size() + " files");
        
        return results;
    }

    /**
     * Resolves the configured attribute (e.g. size) for a specific cloud file path, tracking performance timing metrics.
     *
     * @param filePath the logical target file path to retrieve attributes for
     * @return the captured FileAttributeResult payload
     */
    private static FileAttributeResult processFileAttribute(String filePath) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Get file attributes (size)
            Map<String, String> attributes = altaStataFileSystem.getFileAttributes(
                filePath, null, Arrays.asList(ATTRIBUTE_NAME));
            
            String sizeAttribute = attributes.get(ATTRIBUTE_NAME);
            long processingTime = System.currentTimeMillis() - startTime;
            
            individualFileTimes.add(processingTime);
            totalFilesProcessed++;
            
            return new FileAttributeResult(filePath, sizeAttribute, processingTime, true, null);
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            individualFileTimes.add(processingTime);
            totalFilesProcessed++;
            
            return new FileAttributeResult(filePath, null, processingTime, false, e.getMessage());
        }
    }

    /**
     * Result class for file attribute processing
     */
    private static class FileAttributeResult {
        final String filePath;
        final String sizeAttribute;
        final long processingTimeMs;
        final boolean success;
        final String errorMessage;
        
        FileAttributeResult(String filePath, String sizeAttribute, long processingTimeMs, boolean success, String errorMessage) {
            this.filePath = filePath;
            this.sizeAttribute = sizeAttribute;
            this.processingTimeMs = processingTimeMs;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Run baseline test multiple times (just file listing, no attributes)
     */
    private static void runBaselineTestMultipleTimes() throws Exception {
        List<Long> totalTimes = new ArrayList<>();
        List<Long> batch1Times = new ArrayList<>();
        List<Long> batch2Times = new ArrayList<>();
        List<Long> batch3Times = new ArrayList<>();
        
        for (int run = 1; run <= 3; run++) {
            System.out.println("\n🔄 RUN " + run + " of 3");
            System.out.println("----------------------------------------");
            
            // Reset the file system for clean state
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            long totalStartTime = System.currentTimeMillis();
            
            // Get the base Iterator
            System.out.println("Getting file list from: " + TEST_PATH);
            Iterator<String[]> baseIterator = altaStataFileSystem.listCloudFilesVersions(
                TEST_PATH, true, null, null);

            if (baseIterator == null || !baseIterator.hasNext()) {
                System.out.println("No files found in path: " + TEST_PATH);
                return;
            }

            // Collect all file paths first
            List<String> allFilePaths = new ArrayList<>();
            while (baseIterator.hasNext()) {
                String[] fileVersion = baseIterator.next();
                String filePath = fileVersion[0];
                allFilePaths.add(filePath);
            }

            System.out.println("Total files found: " + allFilePaths.size());
            System.out.println("Processing files in sequential batches (no attributes)...");

            // Process Batch 1 (first 400 files)
            System.out.println("\n--- BATCH 1 (400 files) ---");
            long batch1Start = System.currentTimeMillis();
            List<String> batch1Results = processBatchBaseline(allFilePaths.subList(0, Math.min(400, allFilePaths.size())), 1);
            long batch1Time = System.currentTimeMillis() - batch1Start;
            System.out.println("✓ Batch 1 completed in " + batch1Time + "ms");

            // Process Batch 2 (next 400 files)
            System.out.println("\n--- BATCH 2 (400 files) ---");
            long batch2Start = System.currentTimeMillis();
            List<String> batch2Results = processBatchBaseline(allFilePaths.subList(400, Math.min(800, allFilePaths.size())), 2);
            long batch2Time = System.currentTimeMillis() - batch2Start;
            System.out.println("✓ Batch 2 completed in " + batch2Time + "ms");

            // Process Batch 3 (remaining files)
            System.out.println("\n--- BATCH 3 (remaining files) ---");
            long batch3Start = System.currentTimeMillis();
            List<String> batch3Results = processBatchBaseline(allFilePaths.subList(800, allFilePaths.size()), 3);
            long batch3Time = System.currentTimeMillis() - batch3Start;
            System.out.println("✓ Batch 3 completed in " + batch3Time + "ms");

            // Calculate total time
            long totalTime = System.currentTimeMillis() - totalStartTime;
            int totalFilesProcessed = batch1Results.size() + batch2Results.size() + batch3Results.size();

            // Store times for averaging
            totalTimes.add(totalTime);
            batch1Times.add(batch1Time);
            batch2Times.add(batch2Time);
            batch3Times.add(batch3Time);

            // Print run summary
            System.out.println("\n📊 RUN " + run + " SUMMARY:");
            System.out.println("  ⏱️  Total Time: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
            System.out.println("  📊 Files Processed: " + totalFilesProcessed);
            System.out.println("  🚀 Batch 1: " + batch1Time + "ms");
            System.out.println("  🚀 Batch 2: " + batch2Time + "ms");
            System.out.println("  🚀 Batch 3: " + batch3Time + "ms");
            System.out.println("  ⚡ Throughput: " + String.format("%.2f", (totalFilesProcessed / (totalTime / 1000.0))) + " files/second");
        }
        
        // Calculate and print averages
        printAverages("BASELINE - NO ATTRIBUTES", totalTimes, batch1Times, batch2Times, batch3Times);
    }
    
    /**
     * Process a batch of files in parallel for BASELINE (no attributes)
     */
    private static List<String> processBatchBaseline(List<String> filePaths, int batchNum) {
        List<String> results = new ArrayList<>();
        
        // Process files in parallel within the batch
        List<CompletableFuture<String>> futures = filePaths.stream()
            .map(filePath -> CompletableFuture.supplyAsync(() -> {
                return processFileBaseline(filePath);
            }, backgroundExecutor))
            .collect(java.util.stream.Collectors.toList());
        
        // Wait for all files in this batch to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        for (CompletableFuture<String> future : futures) {
            try {
                String result = future.get();
                results.add(result);
            } catch (Exception e) {
                System.err.println("Error getting result from future: " + e.getMessage());
            }
        }
        
        System.out.println("  ✓ Batch " + batchNum + " completed with " + results.size() + " files");
        
        return results;
    }
    
    /**
     * Process a single file for BASELINE (no attributes)
     */
    private static String processFileBaseline(String filePath) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Just simulate some minimal processing time
            // In reality, this would just be the file path itself
            String result = "FILE:" + filePath;
            
            long endTime = System.currentTimeMillis();
            
            // Add minimal delay to simulate real processing
            if (endTime - startTime < 1) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            return result;
        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }
    }

    /**
     * Run size attribute test multiple times and calculate average
     */
    private static void runSizeAttributeTestMultipleTimes() throws Exception {
        List<Long> totalTimes = new ArrayList<>();
        List<Long> batch1Times = new ArrayList<>();
        List<Long> batch2Times = new ArrayList<>();
        List<Long> batch3Times = new ArrayList<>();
        
        for (int run = 1; run <= 3; run++) {
            System.out.println("\n🔄 RUN " + run + " of 3");
            System.out.println("----------------------------------------");
            
            // Reset the file system for clean state
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            long totalStartTime = System.currentTimeMillis();
            
            // Get the base Iterator
            System.out.println("Getting file list from: " + TEST_PATH);
            Iterator<String[]> baseIterator = altaStataFileSystem.listCloudFilesVersions(
                TEST_PATH, true, null, null);

            if (baseIterator == null || !baseIterator.hasNext()) {
                System.out.println("No files found in path: " + TEST_PATH);
                return;
            }

            // Collect all file paths first
            List<String> allFilePaths = new ArrayList<>();
            while (baseIterator.hasNext()) {
                String[] fileVersion = baseIterator.next();
                String filePath = fileVersion[0];
                allFilePaths.add(filePath);
            }

            System.out.println("Total files found: " + allFilePaths.size());
            System.out.println("Processing files in sequential batches...");

            // Process Batch 1 (first 400 files)
            System.out.println("\n--- BATCH 1 (400 files) ---");
            long batch1Start = System.currentTimeMillis();
            List<FileAttributeResult> batch1Results = processBatchSequentially(allFilePaths.subList(0, Math.min(400, allFilePaths.size())), 1);
            long batch1Time = System.currentTimeMillis() - batch1Start;
            System.out.println("✓ Batch 1 completed in " + batch1Time + "ms");

            // Process Batch 2 (next 400 files)
            System.out.println("\n--- BATCH 2 (400 files) ---");
            long batch2Start = System.currentTimeMillis();
            List<FileAttributeResult> batch2Results = processBatchSequentially(allFilePaths.subList(400, Math.min(800, allFilePaths.size())), 2);
            long batch2Time = System.currentTimeMillis() - batch2Start;
            System.out.println("✓ Batch 2 completed in " + batch2Time + "ms");

            // Process Batch 3 (remaining files)
            System.out.println("\n--- BATCH 3 (remaining files) ---");
            long batch3Start = System.currentTimeMillis();
            List<FileAttributeResult> batch3Results = processBatchSequentially(allFilePaths.subList(800, allFilePaths.size()), 3);
            long batch3Time = System.currentTimeMillis() - batch3Start;
            System.out.println("✓ Batch 3 completed in " + batch3Time + "ms");

            // Calculate total time
            long totalTime = System.currentTimeMillis() - totalStartTime;
            int totalFilesProcessed = batch1Results.size() + batch2Results.size() + batch3Results.size();

            // Store times for averaging
            totalTimes.add(totalTime);
            batch1Times.add(batch1Time);
            batch2Times.add(batch2Time);
            batch3Times.add(batch3Time);

            // Print run summary
            System.out.println("\n📊 RUN " + run + " SUMMARY:");
            System.out.println("  ⏱️  Total Time: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
            System.out.println("  📊 Files Processed: " + totalFilesProcessed);
            System.out.println("  🚀 Batch 1: " + batch1Time + "ms");
            System.out.println("  🚀 Batch 2: " + batch2Time + "ms");
            System.out.println("  🚀 Batch 3: " + batch3Time + "ms");
            System.out.println("  ⚡ Throughput: " + String.format("%.2f", (totalFilesProcessed / (totalTime / 1000.0))) + " files/second");
        }
        
        // Calculate and print averages
        printAverages("SIZE ATTRIBUTE", totalTimes, batch1Times, batch2Times, batch3Times);
    }
    
    /**
     * Run readers attribute test multiple times and calculate average
     */
    private static void runReadersAttributeTestMultipleTimes() throws Exception {
        List<Long> totalTimes = new ArrayList<>();
        List<Long> batch1Times = new ArrayList<>();
        List<Long> batch2Times = new ArrayList<>();
        List<Long> batch3Times = new ArrayList<>();
        
        for (int run = 1; run <= 3; run++) {
            System.out.println("\n🔄 RUN " + run + " of 3");
            System.out.println("----------------------------------------");
            
            // Reset the file system for clean state
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            long totalStartTime = System.currentTimeMillis();
            
            // Get the base Iterator
            System.out.println("Getting file list from: " + TEST_PATH);
            Iterator<String[]> baseIterator = altaStataFileSystem.listCloudFilesVersions(
                TEST_PATH, true, null, null);

            if (baseIterator == null || !baseIterator.hasNext()) {
                System.out.println("No files found in path: " + TEST_PATH);
                return;
            }

            // Collect all file paths first
            List<String> allFilePaths = new ArrayList<>();
            while (baseIterator.hasNext()) {
                String[] fileVersion = baseIterator.next();
                String filePath = fileVersion[0];
                allFilePaths.add(filePath);
            }

            System.out.println("Total files found: " + allFilePaths.size());
            System.out.println("Processing files in sequential batches...");

            // Process Batch 1 (first 400 files)
            System.out.println("\n--- BATCH 1 (400 files) ---");
            long batch1Start = System.currentTimeMillis();
            List<FileAttributeResult> batch1Results = processBatchSequentiallyReaders(allFilePaths.subList(0, Math.min(400, allFilePaths.size())), 1);
            long batch1Time = System.currentTimeMillis() - batch1Start;
            System.out.println("✓ Batch 1 completed in " + batch1Time + "ms");

            // Process Batch 2 (next 400 files)
            System.out.println("\n--- BATCH 2 (400 files) ---");
            long batch2Start = System.currentTimeMillis();
            List<FileAttributeResult> batch2Results = processBatchSequentiallyReaders(allFilePaths.subList(400, Math.min(800, allFilePaths.size())), 2);
            long batch2Time = System.currentTimeMillis() - batch2Start;
            System.out.println("✓ Batch 2 completed in " + batch2Time + "ms");

            // Process Batch 3 (remaining files)
            System.out.println("\n--- BATCH 3 (remaining files) ---");
            long batch3Start = System.currentTimeMillis();
            List<FileAttributeResult> batch3Results = processBatchSequentiallyReaders(allFilePaths.subList(800, allFilePaths.size()), 3);
            long batch3Time = System.currentTimeMillis() - batch3Start;
            System.out.println("✓ Batch 3 completed in " + batch3Time + "ms");

            // Calculate total time
            long totalTime = System.currentTimeMillis() - totalStartTime;
            int totalFilesProcessed = batch1Results.size() + batch2Results.size() + batch3Results.size();

            // Store times for averaging
            totalTimes.add(totalTime);
            batch1Times.add(batch1Time);
            batch2Times.add(batch2Time);
            batch3Times.add(batch3Time);

            // Print run summary
            System.out.println("\n📊 RUN " + run + " SUMMARY:");
            System.out.println("  ⏱️  Total Time: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
            System.out.println("  📊 Files Processed: " + totalFilesProcessed);
            System.out.println("  🚀 Batch 1: " + batch1Time + "ms");
            System.out.println("  🚀 Batch 2: " + batch2Time + "ms");
            System.out.println("  🚀 Batch 3: " + batch3Time + "ms");
            System.out.println("  ⚡ Throughput: " + String.format("%.2f", (totalFilesProcessed / (totalTime / 1000.0))) + " files/second");
        }
        
        // Calculate and print averages
        printAverages("READERS ATTRIBUTE", totalTimes, batch1Times, batch2Times, batch3Times);
    }
    
    /**
     * Run size + eTag attributes test multiple times and calculate average
     */
    private static void runSizeAndETagTestMultipleTimes() throws Exception {
        List<Long> totalTimes = new ArrayList<>();
        List<Long> batch1Times = new ArrayList<>();
        List<Long> batch2Times = new ArrayList<>();
        List<Long> batch3Times = new ArrayList<>();
        
        for (int run = 1; run <= 3; run++) {
            System.out.println("\n🔄 RUN " + run + " of 3");
            System.out.println("----------------------------------------");
            
            // Reset the file system for clean state
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            long totalStartTime = System.currentTimeMillis();
            
            // Get the base Iterator
            System.out.println("Getting file list from: " + TEST_PATH);
            Iterator<String[]> baseIterator = altaStataFileSystem.listCloudFilesVersions(
                TEST_PATH, true, null, null);

            if (baseIterator == null || !baseIterator.hasNext()) {
                System.out.println("No files found in path: " + TEST_PATH);
                return;
            }

            // Collect all file paths first
            List<String> allFilePaths = new ArrayList<>();
            while (baseIterator.hasNext()) {
                String[] fileVersion = baseIterator.next();
                String filePath = fileVersion[0];
                allFilePaths.add(filePath);
            }

            System.out.println("Total files found: " + allFilePaths.size());
            System.out.println("Processing files in sequential batches...");

            // Process Batch 1 (first 400 files)
            System.out.println("\n--- BATCH 1 (400 files) ---");
            long batch1Start = System.currentTimeMillis();
            List<FileAttributeResult> batch1Results = processBatchParallelSizeAndETag(allFilePaths.subList(0, Math.min(400, allFilePaths.size())), 1);
            long batch1Time = System.currentTimeMillis() - batch1Start;
            System.out.println("✓ Batch 1 completed in " + batch1Time + "ms");

            // Process Batch 2 (next 400 files)
            System.out.println("\n--- BATCH 2 (400 files) ---");
            long batch2Start = System.currentTimeMillis();
            List<FileAttributeResult> batch2Results = processBatchParallelSizeAndETag(allFilePaths.subList(400, Math.min(800, allFilePaths.size())), 2);
            long batch2Time = System.currentTimeMillis() - batch2Start;
            System.out.println("✓ Batch 2 completed in " + batch2Time + "ms");

            // Process Batch 3 (remaining files)
            System.out.println("\n--- BATCH 3 (remaining files) ---");
            long batch3Start = System.currentTimeMillis();
            List<FileAttributeResult> batch3Results = processBatchParallelSizeAndETag(allFilePaths.subList(800, allFilePaths.size()), 3);
            long batch3Time = System.currentTimeMillis() - batch3Start;
            System.out.println("✓ Batch 3 completed in " + batch3Time + "ms");

            // Calculate total time
            long totalTime = System.currentTimeMillis() - totalStartTime;
            int totalFilesProcessed = batch1Results.size() + batch2Results.size() + batch3Results.size();

            // Store times for averaging
            totalTimes.add(totalTime);
            batch1Times.add(batch1Time);
            batch2Times.add(batch2Time);
            batch3Times.add(batch3Time);

            // Print run summary
            System.out.println("\n📊 RUN " + run + " SUMMARY:");
            System.out.println("  ⏱️  Total Time: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
            System.out.println("  📊 Files Processed: " + totalFilesProcessed);
            System.out.println("  🚀 Batch 1: " + batch1Time + "ms");
            System.out.println("  🚀 Batch 2: " + batch2Time + "ms");
            System.out.println("  🚀 Batch 3: " + batch3Time + "ms");
            System.out.println("  ⚡ Throughput: " + String.format("%.2f", (totalFilesProcessed / (totalTime / 1000.0))) + " files/second");
        }
        
        // Calculate and print averages
        printAverages("SIZE + ETAG ATTRIBUTES", totalTimes, batch1Times, batch2Times, batch3Times);
    }
    
    /**
     * Process a batch of files in parallel for READERS attribute
     */
    private static List<FileAttributeResult> processBatchSequentiallyReaders(List<String> filePaths, int batchNum) {
        List<FileAttributeResult> results = new ArrayList<>();
        
        // Process files in parallel within the batch
        List<CompletableFuture<FileAttributeResult>> futures = filePaths.stream()
            .map(filePath -> CompletableFuture.supplyAsync(() -> {
                return processFileAttributeReaders(filePath);
            }, backgroundExecutor))
            .collect(java.util.stream.Collectors.toList());
        
        // Wait for all files in this batch to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        for (CompletableFuture<FileAttributeResult> future : futures) {
            try {
                FileAttributeResult result = future.get();
                results.add(result);
            } catch (Exception e) {
                System.err.println("Error getting result from future: " + e.getMessage());
            }
        }
        
        System.out.println("  ✓ Batch " + batchNum + " completed with " + results.size() + " files");
        
        return results;
    }
    
    /**
     * Process a single file attribute for READERS
     */
    private static FileAttributeResult processFileAttributeReaders(String filePath) {
        try {
            long startTime = System.currentTimeMillis();
            String readersAttribute = altaStataFileSystem.getFileAttribute(filePath, null, "readers");
            long endTime = System.currentTimeMillis();
            
            return new FileAttributeResult(filePath, readersAttribute, endTime - startTime, true, null);
        } catch (Exception e) {
            return new FileAttributeResult(filePath, "ERROR: " + e.getMessage(), 0, false, e.getMessage());
        }
    }

    /**
     * Process a batch of files in parallel for SIZE + ETAG attributes
     */
    private static List<FileAttributeResult> processBatchParallelSizeAndETag(List<String> filePaths, int batchNum) {
        List<FileAttributeResult> results = new ArrayList<>();
        
        // Process files in parallel within the batch
        List<CompletableFuture<FileAttributeResult>> futures = filePaths.stream()
            .map(filePath -> CompletableFuture.supplyAsync(() -> {
                return processFileAttributeSizeAndETag(filePath);
            }, backgroundExecutor))
            .collect(java.util.stream.Collectors.toList());
        
        // Wait for all files in this batch to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        for (CompletableFuture<FileAttributeResult> future : futures) {
            try {
                FileAttributeResult result = future.get();
                results.add(result);
            } catch (Exception e) {
                System.err.println("Error getting result from future: " + e.getMessage());
            }
        }
        
        System.out.println("  ✓ Batch " + batchNum + " completed with " + results.size() + " files");
        
        return results;
    }
    
    /**
     * Process a single file attribute for SIZE + ETAG
     */
    private static FileAttributeResult processFileAttributeSizeAndETag(String filePath) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Get both size and eTag attributes in a single call
            Map<String, String> attributes = altaStataFileSystem.getFileAttributes(
                filePath, null, Arrays.asList("size", "eTag"));
            
            String sizeAttribute = attributes.get("size");
            String eTagAttribute = attributes.get("eTag");
            
            // Combine both attributes into a single result string
            String combinedAttribute = "size:" + sizeAttribute + "|eTag:" + eTagAttribute;
            
            long endTime = System.currentTimeMillis();
            
            return new FileAttributeResult(filePath, combinedAttribute, endTime - startTime, true, null);
        } catch (Exception e) {
            return new FileAttributeResult(filePath, "ERROR: " + e.getMessage(), 0, false, e.getMessage());
        }
    }

    /**
     * Print average results for all runs
     */
    private static void printAverages(String testType, List<Long> totalTimes, List<Long> batch1Times, 
                                    List<Long> batch2Times, List<Long> batch3Times) {
        System.out.println("\n" + "============================================================");
        System.out.println("🏆 " + testType + " - FINAL AVERAGE RESULTS (3 runs)");
        System.out.println("============================================================");
        
        double avgTotalTime = totalTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgBatch1Time = batch1Times.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgBatch2Time = batch2Times.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgBatch3Time = batch3Times.stream().mapToLong(Long::longValue).average().orElse(0.0);
        
        System.out.println("📊 AVERAGE TIMES:");
        System.out.println("  ⏱️  Total Execution: " + String.format("%.1f", avgTotalTime) + "ms (" + String.format("%.3f", avgTotalTime / 1000.0) + "s)");
        System.out.println("  🚀 Batch 1: " + String.format("%.1f", avgBatch1Time) + "ms (" + String.format("%.3f", avgBatch1Time / 1000.0) + "s)");
        System.out.println("  🚀 Batch 2: " + String.format("%.1f", avgBatch2Time) + "ms (" + String.format("%.3f", avgBatch2Time / 1000.0) + "s)");
        System.out.println("  🚀 Batch 3: " + String.format("%.1f", avgBatch3Time) + "ms (" + String.format("%.3f", avgBatch3Time / 1000.0) + "s)");
        
        // Calculate throughput
        double avgThroughput = (1100.0 / (avgTotalTime / 1000.0)); // Assuming ~1100 files
        System.out.println("  ⚡ Average Throughput: " + String.format("%.2f", avgThroughput) + " files/second");
        
        // Show individual run times for reference
        System.out.println("\n📈 INDIVIDUAL RUN TIMES:");
        for (int i = 0; i < totalTimes.size(); i++) {
            System.out.println("  Run " + (i + 1) + ": " + totalTimes.get(i) + "ms");
        }
        
        System.out.println("============================================================");
    }
}
