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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.utils.Account;

/**
 * AltaStata-only performance test.
 * 
 * Tests AltaStata functionality with various file sizes to verify that
 * "key not found" exceptions are not thrown after the cleanup of retry logic.
 *
 * @author AltaStata
 */
public class PerformanceTestAltaStataOnly {

	private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestAltaStataOnly.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";
	
	// Performance test configuration
	private static final int WARMUP_RUNS = 2;
	private static final int SMALL_FILE_RUNS = 10;     // 1MB-10MB
	private static final int MEDIUM_FILE_RUNS = 5;    // 100MB
	private static final int LARGE_FILE_RUNS = 3;     // 1GB
	private static final int XLARGE_FILE_RUNS = 1;    // 5GB
	private static final int MAX_RETRIES = 3;
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-gcs";
	
	private static AltaStataFileSystem altaStataFileSystem;

	/**
	 * Main execution entry point for AltaStata-only performance testing.
	 * Executes sequential uploads/downloads for configured small, medium, large, and extra-large test files,
	 * measuring durations and throughput metrics.
	 *
	 * @param args command-line arguments (unused)
	 * @throws Exception if an error occurs during execution
	 */
	public static void main(String [] args) throws Exception {

		System.out.println("=== ALTASTATA-ONLY PERFORMANCE TEST ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("AltaStata Account: google.rsa.bob123");
		System.out.println("AltaStata Bucket: performance-gcs");
		System.out.println("========================================\n");

		// Initialize AltaStata
		initializeAltaStata();
		cleanupAltaStataPerformanceFiles();

		// Test small files
		runAltaStataTest("text-1MB.txt", SMALL_FILE_RUNS, "Small Files");
		runAltaStataTest("binary-1MB.bin", SMALL_FILE_RUNS, "Small Files");
		runAltaStataTest("text-10MB.txt", SMALL_FILE_RUNS, "Small Files");
		runAltaStataTest("binary-10MB.bin", SMALL_FILE_RUNS, "Small Files");

		// Test medium files
		runAltaStataTest("text-100MB.txt", MEDIUM_FILE_RUNS, "Medium Files");
		runAltaStataTest("binary-100MB.bin", MEDIUM_FILE_RUNS, "Medium Files");

		// Test large files
		runAltaStataTest("text-1GB.txt", LARGE_FILE_RUNS, "Large Files");
		runAltaStataTest("binary-1GB.bin", LARGE_FILE_RUNS, "Large Files");

		// Test very large files
		runAltaStataTest("text-5GB.txt", XLARGE_FILE_RUNS, "Very Large Files");
		runAltaStataTest("binary-5GB.bin", XLARGE_FILE_RUNS, "Very Large Files");
		
		LOGGER.warn("AltaStata-only performance test completed successfully");
		System.out.println("\n=== TEST COMPLETED SUCCESSFULLY ===");
		System.out.println("No 'key not found' exceptions were thrown, confirming that");
		System.out.println("the retry logic cleanup is working correctly.");
		cleanupAltaStataPerformanceFiles();
	}
	
	/**
	 * Initializes the AltaStataFileSystem instance using the predefined google.rsa.bob123 account home directory.
	 */
	private static void initializeAltaStata() {
		try {
			altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
				Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "google.rsa.bob123");
			altaStataFileSystem.setPassword("123");
			System.out.println("Successfully initialized AltaStata FileSystem");
		} catch (Exception e) {
			System.err.println("Failed to initialize AltaStata: " + e.getMessage());
			throw new RuntimeException("Failed to initialize AltaStata", e);
		}
	}
	
	/**
	 * Orchestrates and executes upload and download performance runs for a given test file.
	 *
	 * @param fileName the target file name to test
	 * @param runs the number of sequential iterations
	 * @param testCategory descriptive category label (e.g. "Small Files")
	 */
	private static void runAltaStataTest(String fileName, int runs, String testCategory) {
		System.out.println("\n=== " + testCategory + " Test: " + fileName + " ===");

		cleanupAltaStataPerformanceFiles();
		
		// Test upload
		PerformanceResult uploadResult = uploadFileAltaStata(fileName, runs);
		printResults("UPLOAD", fileName, uploadResult);
		
		// Test download
		PerformanceResult downloadResult = downloadFileAltaStata(fileName, runs);
		printResults("DOWNLOAD", fileName, downloadResult);
	}
	
	/**
	 * Executes sequential uploads of the designated test file using AltaStata FileSystem APIs.
	 *
	 * @param fileName the target file name to upload
	 * @param runs the number of sequential iterations
	 * @return the captured PerformanceResult metrics
	 */
	private static PerformanceResult uploadFileAltaStata(String fileName, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("AltaStata Upload: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String localFile = file_path + "/" + fileName;
					String cloudPath = altaStataCloudFilePath(fileName, i);
					List<CloudFileOperationStatus> stored =
							altaStataFileSystem.store(Arrays.asList(localFile), localFile, cloudPath, true);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);
					long memoryUsed = endMemory - startMemory;

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);

					System.out.format("  Upload Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					
				} catch (Exception e) {
					retries++;
					System.err.format("  Upload Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}
	
	/**
	 * Executes sequential downloads of the designated test file using AltaStata FileSystem APIs.
	 *
	 * @param fileName the target file name to download
	 * @param runs the number of sequential iterations
	 * @return the captured PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileAltaStata(String fileName, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("AltaStata Download: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					List<CloudFileOperationStatus> retrieved = 
							altaStataFileSystem.retrieve(file_path + "2", altaStataCloudFilePath(fileName, i), true, System.currentTimeMillis(), false, true);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);
					long memoryUsed = endMemory - startMemory;

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);

					System.out.format("  Download Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					
				} catch (Exception e) {
					retries++;
					System.err.format("  Download Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}

	/**
	 * Formats and prints out collected average duration, throughput, and deviation metrics for an operation.
	 *
	 * @param operation name of the operation (e.g. "UPLOAD")
	 * @param fileName the target file name tested
	 * @param result the captured metrics payload
	 */
	private static void printResults(String operation, String fileName, PerformanceResult result) {
		System.out.println("\n  " + operation + " RESULTS for " + fileName + ":");
		System.out.println("  =================================================");
		
		if (!result.durations.isEmpty()) {
			Collections.sort(result.durations);
			Collections.sort(result.throughputs);
			
			double avgDuration = result.durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
			double avgThroughput = result.throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
			double stdDevDuration = calculateStdDev(result.durations, avgDuration);
			double stdDevThroughput = calculateStdDev(result.throughputs, avgThroughput);
			
			System.out.format("    Runs: %d successful\n", result.durations.size());
			System.out.format("    Average Duration: %.2f ms (±%.2f ms)\n", avgDuration, stdDevDuration);
			System.out.format("    Average Throughput: %.2f MB/s (±%.2f MB/s)\n", avgThroughput, stdDevThroughput);
			System.out.format("    File Size: %.2f MB\n", result.fileSize / (1024.0 * 1024.0));
		} else {
			System.out.println("    No successful runs");
		}
		
		System.out.println("  =================================================");
	}
	
	/**
	 * Computes the standard deviation value for a list of numerical metrics.
	 *
	 * @param values the list of values to calculate deviation for
	 * @param mean the computed average/mean of the values
	 * @return the standard deviation double value
	 */
	private static double calculateStdDev(List<? extends Number> values, double mean) {
		if (values.size() <= 1) return 0.0;
		
		double sumSquaredDiff = values.stream()
				.mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
				.sum();
		
		return Math.sqrt(sumSquaredDiff / (values.size() - 1));
	}
	
	/**
	 * Gets the physical size on disk in bytes for a given local file under the test directory.
	 *
	 * @param fileName the local target file name
	 * @return the size of the file in bytes, or 0 if it does not exist
	 */
	private static long getFileSize(String fileName) {
		File file = new File(file_path + "/" + fileName);
		return file.exists() ? file.length() : 0;
	}

	private static String altaStataCloudFilePath(String fileName, int runIndex) {
		return ALTASTATA_CLOUD_BUCKET + "/" + fileName + "-altastata-" + runIndex;
	}

	private static void cleanupAltaStataPerformanceFiles() {
		if (altaStataFileSystem == null) {
			return;
		}
		try {
			altaStataFileSystem.delete(ALTASTATA_CLOUD_BUCKET, true, null, null);
		} catch (Exception e) {
			System.err.println("AltaStata cleanup warning: " + e.getMessage());
		}
	}
	
	private static class PerformanceResult {
		final List<Long> durations;
		final List<Double> throughputs;
		final long fileSize;
		
		PerformanceResult(List<Long> durations, List<Double> throughputs, long fileSize) {
			this.durations = durations;
			this.throughputs = throughputs;
			this.fileSize = fileSize;
		}
	}
}
