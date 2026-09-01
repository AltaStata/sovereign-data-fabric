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

package com.altastata.performance.gcp;

import java.io.File;
import java.io.IOException;
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
import com.amazonaws.AmazonServiceException;

/**
 * Enhanced performance test for Google Cloud Storage upload and download operations.
 * 
 * Tests various file sizes from the test-files directory:
 * - text-1MB.txt (1MB)
 * - text-10MB.txt (10MB) 
 * - text-100MB.txt (100MB)
 * - text-1GB.txt (1GB)
 * - text-5GB.txt (5GB)
 * - binary-1MB.bin (1MB binary)
 * - binary-10MB.bin (10MB binary)
 * - binary-100MB.bin (100MB binary)
 * - binary-1GB.bin (1GB binary)
 * - binary-5GB.bin (5GB binary)
 *
 * Enhanced features:
 * - Statistical analysis (mean, std dev, percentiles)
 * - Throughput calculations (MB/s, GB/s)
 * - Better error handling and retry logic
 * - Memory usage monitoring
 * - Comprehensive reporting
 *
 * @author AltaStata
 *
 */
public class PerformanceTestUploadDownloadGoogleCloud {

	private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestUploadDownloadGoogleCloud.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";
	
	// Performance test configuration
	private static final int WARMUP_RUNS = 2;
	private static final int SMALL_FILE_RUNS = 15;    // 1MB-10MB
	private static final int MEDIUM_FILE_RUNS = 10;   // 100MB
	private static final int LARGE_FILE_RUNS = 5;     // 1GB
	private static final int XLARGE_FILE_RUNS = 3;    // 5GB
	private static final int MAX_RETRIES = 3;
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-gcs";

	/**
	 * Main execution entry point for AltaStata Google Cloud Storage performance tests.
	 *
	 * @param args command-line arguments (unused)
	 * @throws IOException if directory or account setups fail
	 */
	public static void main(String [] args) throws IOException  {

		AltaStataFileSystem altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
				Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "google.rsa.bob123");

		altaStataFileSystem.setPassword("123");

		System.out.println("=== Google Cloud Storage Performance Test ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Account: google.rsa.bob123");
		System.out.println("Bucket: performance-gcs");
		System.out.println("=============================================\n");

		cleanupAltaStataPerformanceFiles(altaStataFileSystem);

		// Warm up with smaller files
		runPerformanceTest(altaStataFileSystem, "text-1MB.txt", WARMUP_RUNS, "Warm-up");
		runPerformanceTest(altaStataFileSystem, "binary-1MB.bin", WARMUP_RUNS, "Warm-up");

		// Test small files
		runPerformanceTest(altaStataFileSystem, "text-1MB.txt", SMALL_FILE_RUNS, "Small Files");
		runPerformanceTest(altaStataFileSystem, "binary-1MB.bin", SMALL_FILE_RUNS, "Small Files");
		runPerformanceTest(altaStataFileSystem, "text-10MB.txt", SMALL_FILE_RUNS, "Small Files");
		runPerformanceTest(altaStataFileSystem, "binary-10MB.bin", SMALL_FILE_RUNS, "Small Files");

		// Test medium files
		runPerformanceTest(altaStataFileSystem, "text-100MB.txt", MEDIUM_FILE_RUNS, "Medium Files");
		runPerformanceTest(altaStataFileSystem, "binary-100MB.bin", MEDIUM_FILE_RUNS, "Medium Files");

		// Test large files
		runPerformanceTest(altaStataFileSystem, "text-1GB.txt", LARGE_FILE_RUNS, "Large Files");
		runPerformanceTest(altaStataFileSystem, "binary-1GB.bin", LARGE_FILE_RUNS, "Large Files");

		// Test very large files
		runPerformanceTest(altaStataFileSystem, "text-5GB.txt", XLARGE_FILE_RUNS, "Very Large Files");
		runPerformanceTest(altaStataFileSystem, "binary-5GB.bin", XLARGE_FILE_RUNS, "Very Large Files");

		LOGGER.warn("Google Cloud Storage performance test completed");
		cleanupAltaStataPerformanceFiles(altaStataFileSystem);
	}
	
	/**
	 * Runs a performance test run of uploads and downloads via AltaStata.
	 *
	 * @param altaStataFileSystem file system handler instance
	 * @param fileName target file to test
	 * @param runs iteration count
	 * @param testCategory label of test category
	 */
	private static void runPerformanceTest(AltaStataFileSystem altaStataFileSystem, String fileName, int runs, String testCategory) {
		System.out.println("\n=== " + testCategory + " Test: " + fileName + " ===");

		cleanupAltaStataPerformanceFiles(altaStataFileSystem);
		
		// Upload test
		PerformanceResult uploadResult = uploadFileWithRetry(altaStataFileSystem, fileName, runs);
		printPerformanceResult("UPLOAD", fileName, uploadResult);
		
		// Download test
		PerformanceResult downloadResult = downloadFileWithRetry(altaStataFileSystem, fileName, runs, false);
		printPerformanceResult("DOWNLOAD", fileName, downloadResult);
	}
	
	/**
	 * Benchmarks file upload via AltaStata to GCS with retry mechanism.
	 *
	 * @param altaStataFileSystem file system handler instance
	 * @param fileName target file key/name
	 * @param runs iteration count
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult uploadFileWithRetry(AltaStataFileSystem altaStataFileSystem, String fileName, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("Uploading %s to Google Cloud Storage via AltaStata (%d runs)...\n", 
				file_path + "/" + fileName, runs);

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

					System.out.format("  Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					
				} catch (AmazonServiceException e) {
					retries++;
					System.err.format("  Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getErrorMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					// Wait before retry
					try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
				} catch (Exception e) {
					retries++;
					System.err.format("  Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
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
	 * Benchmarks file download via AltaStata from GCS with retry mechanism.
	 *
	 * @param altaStataFileSystem file system handler instance
	 * @param fileName target file key/name
	 * @param runs iteration count
	 * @param isStreaming true to enable HTTP streaming mode
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileWithRetry(AltaStataFileSystem altaStataFileSystem, String fileName, int runs, boolean isStreaming) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("Downloading %s from Google Cloud Storage via AltaStata with streaming=%s (%d runs)...\n", 
				file_path + "2/" + fileName, isStreaming, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					List<CloudFileOperationStatus> retrieved = 
							altaStataFileSystem.retrieve(file_path + "2", altaStataCloudFilePath(fileName, i), true, System.currentTimeMillis(), isStreaming, true);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);
					long memoryUsed = endMemory - startMemory;

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);

					System.out.format("  Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					
				} catch (AmazonServiceException e) {
					retries++;
					System.err.format("  Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getErrorMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
				} catch (Exception e) {
					retries++;
					System.err.format("  Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
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
	 * Formats and prints performance stats for upload/download runs.
	 *
	 * @param operation name of operation (e.g. "UPLOAD")
	 * @param fileName target filename
	 * @param result collected PerformanceResult metrics
	 */
	private static void printPerformanceResult(String operation, String fileName, PerformanceResult result) {
		if (result.durations.isEmpty()) {
			System.out.println("  No successful runs for " + operation);
			return;
		}
		
		Collections.sort(result.durations);
		Collections.sort(result.throughputs);
		
		double avgDuration = result.durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
		double avgThroughput = result.throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		
		double stdDevDuration = calculateStdDev(result.durations, avgDuration);
		double stdDevThroughput = calculateStdDev(result.throughputs, avgThroughput);
		
		int p50Index = (int) (result.durations.size() * 0.5);
		int p95Index = (int) (result.durations.size() * 0.95);
		int p99Index = (int) (result.durations.size() * 0.99);
		
		System.out.println("\n  " + operation + " Performance Summary for " + fileName + ":");
		System.out.println("  =================================================");
		System.out.format("  Runs: %d successful\n", result.durations.size());
		System.out.format("  File Size: %.2f MB\n", result.fileSize / (1024.0 * 1024.0));
		System.out.format("  Average Duration: %.2f ms (±%.2f ms)\n", avgDuration, stdDevDuration);
		System.out.format("  Average Throughput: %.2f MB/s (±%.2f MB/s)\n", avgThroughput, stdDevThroughput);
		System.out.format("  P50 Duration: %d ms\n", result.durations.get(p50Index));
		System.out.format("  P95 Duration: %d ms\n", result.durations.get(Math.min(p95Index, result.durations.size() - 1)));
		System.out.format("  P99 Duration: %d ms\n", result.durations.get(Math.min(p99Index, result.durations.size() - 1)));
		System.out.format("  Min Duration: %d ms\n", result.durations.get(0));
		System.out.format("  Max Duration: %d ms\n", result.durations.get(result.durations.size() - 1));
		System.out.println("  =================================================");
	}
	
	/**
	 * Computes standard deviation for GCS runtimes.
	 *
	 * @param values captured values
	 * @param mean calculated mean
	 * @return computed standard deviation double
	 */
	private static double calculateStdDev(List<? extends Number> values, double mean) {
		if (values.size() <= 1) return 0.0;
		
		double sumSquaredDiff = values.stream()
				.mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
				.sum();
		
		return Math.sqrt(sumSquaredDiff / (values.size() - 1));
	}
	
	/**
	 * Gets local file size on disk.
	 *
	 * @param fileName target filename
	 * @return file size in bytes
	 */
	private static long getFileSize(String fileName) {
		File file = new File(file_path + "/" + fileName);
		return file.exists() ? file.length() : 0;
	}

	private static String altaStataCloudFilePath(String fileName, int runIndex) {
		return ALTASTATA_CLOUD_BUCKET + "/" + fileName + "-altastata-" + runIndex;
	}

	private static void cleanupAltaStataPerformanceFiles(AltaStataFileSystem altaStataFileSystem) {
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
