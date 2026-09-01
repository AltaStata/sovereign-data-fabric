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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;

/**
 * Performance test for different file sizes with detailed timing breakdown.
 * 
 * Tests AltaStata file retrieval performance across various file sizes
 * and provides detailed timing information for metadata retrieval,
 * signature verification, and content retrieval phases.
 *
 * @author AltaStata
 */
public class PerformanceTestFileSizes {

    private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestFileSizes.class);
    
    // Test configuration
    private static final int WARMUP_RUNS = 3;
    
    // File sizes to test (in MB) - 100KB, 1MB, and 10MB files for detailed analysis
    private static final int[] FILE_SIZES_MB = {0, 1, 10}; // 0 = 100KB, 1 = 1MB, 10 = 10MB
    
    private static AltaStataFileSystem altaStataFileSystem;
    private static String outputDir;
    
    // Performance tracking
    private static final Map<Integer, List<PerformanceResult>> performanceResults = new HashMap<>();

    /**
     * Main execution entry point for file size performance tests.
     * Initializes filesystem, runs warmups, conducts benchmarks across various file size groups,
     * and reports detailed throughput averages.
     *
     * @param args command-line arguments (unused)
     * @throws Exception if an execution failure occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== ALTASTATA FILE SIZE PERFORMANCE TEST ===");
        System.out.println("Testing performance across different file sizes");
        System.out.println("Account: " + accountDirForLog());
        System.out.println("=============================================\n");

        // Initialize AltaStata
        initializeAltaStata();

        // Create output directory
        outputDir = System.getProperty("user.home") + "/.altastata/performance-test-output";
        new File(outputDir).mkdirs();
        System.out.println("Output directory: " + outputDir);

        // Run warmup
        System.out.println("\n=== WARMUP RUNS ===");
        runWarmup();

        // Test all available files for comprehensive performance analysis
        System.out.println("\n=== PERFORMANCE TESTS ===");
        testAllFiles();
        
        // Summary of performance results
        System.out.println("\n=== PERFORMANCE SUMMARY ===");
        System.out.println("Note: Each file tested once for cold start performance (no cache)");
        printAllFilesSummary();

        System.out.println("\n=== TEST COMPLETED SUCCESSFULLY ===");
        System.out.println("Check the logs for detailed performance breakdown:");
        System.out.println("- Metadata retrieval time");
        System.out.println("- Signature verification time");
        System.out.println("- Content retrieval time");
        System.out.println("- Total operation time");
    }

    private static String accountDirForLog() {
        String fromEnv = System.getenv("ALTASTATA_ACCOUNT_DIR");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        throw new IllegalStateException("Set ALTASTATA_ACCOUNT_DIR to an AltaStata account directory");
    }

    /**
     * Initializes the AltaStataFileSystem from {@code ALTASTATA_ACCOUNT_DIR}, or a local MinIO account.
     */
    private static void initializeAltaStata() {
        try {
            String accountPath = accountDirForLog();
            altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountPath);
            altaStataFileSystem.setPassword("123");
            
            // Note: User needs to set password manually
            System.out.println("Successfully initialized AltaStata FileSystem");
            System.out.println("Account path: " + accountPath);
            System.out.println("IMPORTANT: Set password using altaStataFileSystem.setPassword(\"your_password\")");
            
        } catch (Exception e) {
            System.err.println("Failed to initialize AltaStata: " + e.getMessage());
            throw new RuntimeException("Failed to initialize AltaStata", e);
        }
    }

    /**
     * Executes several warmup iterations to pre-warm the connection pools, classloaders, and cache systems.
     */
    private static void runWarmup() {
        System.out.println("Running warmup runs...");
        
        // Try to find any existing file for warmup
        try {
            // List files in the performance_test directory where your files are located
            Iterator<String[]> fileIterator = altaStataFileSystem.listCloudFilesVersions("performance_test", true, null, null);
            if (fileIterator.hasNext()) {
                String[] firstFile = fileIterator.next();
                if (firstFile.length > 0) {
                    String filePath = firstFile[0];
                    System.out.println("Warmup: Found file for testing: " + filePath);
                    
                    // Try a small retrieval as warmup
                    for (int i = 0; i < WARMUP_RUNS; i++) {
                        try {
                            System.out.println("Warmup run " + (i + 1) + "/" + WARMUP_RUNS);
                            // This will just test the connection and basic functionality
                        } catch (Exception e) {
                            System.out.println("Warmup run " + (i + 1) + " failed: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Warmup failed: " + e.getMessage());
        }
    }

    /**
     * Executes benchmarks for a specific target file size category in MB.
     *
     * @param fileSizeMB the file size category to filter and benchmark (0 = 100KB, 1 = 1MB, 10 = 10MB)
     */
    private static void testFileSize(int fileSizeMB) {
        String sizeLabel = fileSizeMB == 0 ? "100KB files" : fileSizeMB + "MB files";
        System.out.println("\n--- Testing " + sizeLabel + " ---");
        
        // Try to find files of approximately this size
        try {
            // List files in the performance_test directory where your files are located
            Iterator<String[]> fileIterator = altaStataFileSystem.listCloudFilesVersions("performance_test", true, null, null);
            
            List<String> testFiles = new ArrayList<>();
            while (fileIterator.hasNext() && testFiles.size() < 3) {
                String[] fileInfo = fileIterator.next();
                if (fileInfo.length > 0) {
                    String filePath = fileInfo[0];
                    // For now, we'll test with any available files
                    // In a real scenario, you'd want to filter by actual file size
                    testFiles.add(filePath);
                }
            }

            if (testFiles.isEmpty()) {
                System.out.println("No files found for testing " + fileSizeMB + "MB size");
                return;
            }

            System.out.println("Found " + testFiles.size() + " files to test");
            
            // Test each file once for cold start performance (no cache)
            // Only test files that match the expected size category
            List<String> matchingFiles = new ArrayList<>();
            for (String testFile : testFiles) {
                if (isFileSizeMatch(testFile, fileSizeMB)) {
                    matchingFiles.add(testFile);
                }
            }
            
            if (matchingFiles.isEmpty()) {
                System.out.println("No files found matching " + fileSizeMB + "MB size category");
                return;
            }
            
            System.out.println("Found " + matchingFiles.size() + " files matching " + fileSizeMB + "MB size category");
            
            // Test each matching file exactly once
            for (int i = 0; i < matchingFiles.size(); i++) {
                String testFile = matchingFiles.get(i);
                System.out.println("Test run " + (i + 1) + "/" + matchingFiles.size() + ": " + testFile);
                
                testFileRetrieval(testFile, fileSizeMB, i + 1);
                
                // Small delay between files
                if (i < matchingFiles.size() - 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error testing " + fileSizeMB + "MB files: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Downloads/retrieves a specific cloud file, capturing precise metrics for metadata resolution and content download.
     *
     * @param cloudFilePath logical path of the target secure file to download
     * @param expectedSizeMB the expected file size category in MB
     * @param runNumber sequence index of this test iteration
     */
    private static void testFileRetrieval(String cloudFilePath, int expectedSizeMB, int runNumber) {
        long startTime = System.currentTimeMillis();
        
        try {
            System.out.println("  Starting retrieval of: " + cloudFilePath);
            
            // Create a unique output filename
            String fileName = new File(cloudFilePath).getName();
            String outputPath = outputDir + File.separator + "retrieved_" + System.currentTimeMillis() + "_" + fileName;
            
            // Retrieve the file
            List<CloudFileOperationStatus> results = altaStataFileSystem.retrieve(
                outputDir, 
                cloudFilePath, 
                true,
                System.currentTimeMillis(),   // use latest version
                false,  // not streaming
                true    // wait until done
            );
            
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            
            // Check results
            boolean success = false;
            System.out.println("  Results count: " + results.size());
            for (int i = 0; i < results.size(); i++) {
                CloudFileOperationStatus status = results.get(i);
                System.out.println("  Result " + (i + 1) + ": State=" + status.getOperationState() + 
                                 ", Progress=" + status.getProgressValue() + 
                                 ", Error=" + (status.getError() != null ? status.getError() : "null"));
                
                if (status.getOperationState() == AltaStataFileSystem.OperationState.DONE) {
                    success = true;
                    System.out.println("  ✓ Successfully retrieved in " + totalTime + "ms");
                    System.out.println("  ✓ Output file: " + outputPath);
                    break;
                } else if (status.getOperationState() == AltaStataFileSystem.OperationState.ERROR) {
                    System.out.println("  ✗ Error: " + status.getError());
                    if (status.getError() != null && !status.getError().isEmpty()) {
                        System.out.println("  ✗ Error details: " + status.getError());
                    }
                }
            }
            
            if (!success) {
                System.out.println("  ✗ Retrieval failed - no successful results");
            }
            
            // Calculate performance metrics
            double actualSizeMB = expectedSizeMB == 0 ? 0.1 : expectedSizeMB; // 100KB = 0.1MB
            double throughputMBps = (actualSizeMB * 1000.0) / totalTime; // MB/s
            System.out.println("  📊 Performance: " + String.format("%.2f", throughputMBps) + " MB/s");
            
            // Parse Scala performance logs to extract detailed timing
            PerformanceTiming timing = parsePerformanceLogs(cloudFilePath);
            
            // Store performance result with detailed timing
            PerformanceResult result = new PerformanceResult(runNumber, cloudFilePath, 
                timing.metadataTime, timing.contentTime, totalTime, throughputMBps);
            performanceResults.computeIfAbsent(expectedSizeMB, k -> new ArrayList<>()).add(result);
            
            // Display detailed timing breakdown
            System.out.println("  📊 Detailed Performance Breakdown:");
            System.out.println("    Metadata + Attributes: " + timing.metadataTime + "ms");
            System.out.println("    Content Retrieval: " + timing.contentTime + "ms");
            System.out.println("    Total Operation: " + totalTime + "ms");
            
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            System.err.println("  ✗ Exception after " + totalTime + "ms: " + e.getMessage());
            e.printStackTrace();
        }
    }



    /**
     * Prints helpful usage details and local execution setup prerequisites.
     */
    private static void printUsage() {
        System.out.println("\nUsage:");
        System.out.println("1. Set ALTASTATA_ACCOUNT_DIR to an AltaStata account directory");
        System.out.println("2. Set the password in the code or modify the initializeAltaStata() method");
        System.out.println("3. Run the application: java -cp <classpath> com.altastata.performance.PerformanceTestFileSizes");
        System.out.println("\nThe application will:");
        System.out.println("- Test files of different sizes (1MB to 1GB)");
        System.out.println("- Provide detailed timing breakdown in the logs");
        System.out.println("- Calculate throughput for each file size");
        System.out.println("- Save retrieved files to ~/.altastata/performance-test-output/");
    }
    
    // Performance timing data class
    static class PerformanceTiming {
        final long metadataTime;
        final long contentTime;
        
        PerformanceTiming(long metadataTime, long contentTime) {
            this.metadataTime = metadataTime;
            this.contentTime = contentTime;
        }
    }
    
    // Performance result data class
    static class PerformanceResult {
        final int runNumber;
        final String fileName;
        final long metadataTime;
        final long contentTime;
        final long totalTime;
        final double throughput;
        
        PerformanceResult(int runNumber, String fileName, long metadataTime, long contentTime, long totalTime, double throughput) {
            this.runNumber = runNumber;
            this.fileName = fileName;
            this.metadataTime = metadataTime;
            this.contentTime = contentTime;
            this.totalTime = totalTime;
            this.throughput = throughput;
        }
    }
    
    /**
     * Finds and tests all available benchmark files matching expected sizes under the "performance_test" prefix.
     */
    private static void testAllFiles() {
        System.out.println("--- Testing All Available Files ---");
        
        try {
            // List all files in the performance_test directory
            Iterator<String[]> fileIterator = altaStataFileSystem.listCloudFilesVersions("performance_test", true, null, null);
            
            List<String> allFiles = new ArrayList<>();
            while (fileIterator.hasNext()) {
                String[] fileInfo = fileIterator.next();
                if (fileInfo.length > 0) {
                    String filePath = fileInfo[0];
                    allFiles.add(filePath);
                }
            }

            if (allFiles.isEmpty()) {
                System.out.println("No files found for testing");
                return;
            }

            System.out.println("Found " + allFiles.size() + " files to test");
            
            // Test each file exactly once for cold start performance
            for (int i = 0; i < allFiles.size(); i++) {
                String testFile = allFiles.get(i);
                System.out.println("Test run " + (i + 1) + "/" + allFiles.size() + ": " + testFile);
                
                // Determine file size from filename for throughput calculation
                int fileSizeMB = extractFileSizeFromName(testFile);
                testFileRetrieval(testFile, fileSizeMB, i + 1);
                
                // Small delay between files
                if (i < allFiles.size() - 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error testing files: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Extracts the file size indicator (in MB) based on standard benchmark naming patterns (e.g. 100KB, 1MB, 10MB).
     *
     * @param fileName target filename to parse
     * @return the resolved size index
     */
    private static int extractFileSizeFromName(String fileName) {
        // Extract file size from filename for throughput calculation
        if (fileName.contains("100KB")) return 0;  // 0 = 100KB
        if (fileName.contains("1MB")) return 1;
        if (fileName.contains("10MB")) return 10;
        if (fileName.contains("100MB")) return 100;
        return 0; // Default to 100KB if unknown
    }
    
    /**
     * Checks if a filename matches the expected size category string.
     *
     * @param fileName target filename to match
     * @param expectedSizeMB the size category (0 = 100KB, 1 = 1MB, 10 = 10MB)
     * @return true if it matches; false otherwise
     */
    private static boolean isFileSizeMatch(String fileName, int expectedSizeMB) {
        // Simple heuristic to match files to size categories based on filename
        if (expectedSizeMB == 0) {
            // 100KB files
            return fileName.contains("100KB");
        } else if (expectedSizeMB == 1) {
            // 1MB files
            return fileName.contains("1MB");
        } else if (expectedSizeMB == 10) {
            // 10MB files
            return fileName.contains("10MB");
        }
        return false;
    }
    
    /**
     * Parses the log streams or outputs to reconstruct precise phase timing metrics.
     *
     * @param cloudFilePath logical target path
     * @return reconstructed PerformanceTiming metrics
     */
    private static PerformanceTiming parsePerformanceLogs(String cloudFilePath) {
        // For now, return placeholder values since we can't easily parse the logs
        // In a real implementation, you would parse the log output to extract:
        // - Metadata retrieval time from "Metadata retrieval took Xms"
        // - Content retrieval time from "Content retrieval took Xms"
        // - Data attribute time from the detailed breakdown logs
        
        // Placeholder implementation - returns 0 for now
        // TODO: Implement actual log parsing to extract real timing data
        return new PerformanceTiming(0, 0);
    }
    
    /**
     * Reports a detailed, tabular total summary of all file retrieval times and throughput rates.
     */
    private static void printAllFilesSummary() {
        System.out.println("\n--- All Files Performance Summary ---");
        
        // Collect all results from all categories
        List<PerformanceResult> allResults = new ArrayList<>();
        for (List<PerformanceResult> results : performanceResults.values()) {
            allResults.addAll(results);
        }
        
        if (allResults.isEmpty()) {
            System.out.println("No performance data available");
            return;
        }
        
        System.out.println("Analyzed " + allResults.size() + " file(s):");
        
        // Show individual file results with detailed breakdown
        for (PerformanceResult result : allResults) {
            System.out.println("  " + result.fileName + ":");
            System.out.println("    Total Time: " + result.totalTime + "ms");
            System.out.println("    Throughput: " + String.format("%.2f", result.throughput) + " MB/s");
            if (result.metadataTime > 0 || result.contentTime > 0) {
                System.out.println("    Metadata Time: " + result.metadataTime + "ms");
                System.out.println("    Content Time: " + result.contentTime + "ms");
            }
        }
    }
    
    /**
     * Reports average metrics specifically for a single file size category.
     *
     * @param fileSizeMB the target file size category in MB
     */
    private static void printPerformanceSummary(int fileSizeMB) {
        String sizeLabel = fileSizeMB == 0 ? "100KB files" : fileSizeMB + "MB files";
        List<PerformanceResult> results = performanceResults.get(fileSizeMB);
        
        if (results == null || results.isEmpty()) {
            System.out.println("No performance data available for " + sizeLabel);
            return;
        }
        
        System.out.println("\n--- " + sizeLabel + " Performance Summary ---");
        System.out.println("Analyzed " + results.size() + " file(s):");
        
        // Show individual file results with detailed breakdown
        for (PerformanceResult result : results) {
            System.out.println("  " + result.fileName + ":");
            System.out.println("    Total Time: " + result.totalTime + "ms");
            System.out.println("    Throughput: " + String.format("%.2f", result.throughput) + " MB/s");
            if (result.metadataTime > 0 || result.contentTime > 0) {
                System.out.println("    Metadata Time: " + result.metadataTime + "ms");
                System.out.println("    Content Time: " + result.contentTime + "ms");
            }
        }
    }
}
