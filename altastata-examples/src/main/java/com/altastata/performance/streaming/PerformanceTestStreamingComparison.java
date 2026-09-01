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

package com.altastata.performance.streaming;

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
 * Performance test to compare streaming vs non-streaming modes for binary files.
 * Tests download performance with isStreaming=false and isStreaming=true
 * 
 * Tests binary files:
 * - binary-1GB.bin (1GB binary)
 * - binary-5GB.bin (5GB binary)
 *
 * @author AltaStata
 */
public class PerformanceTestStreamingComparison {

	private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestStreamingComparison.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";
	
	// Performance test configuration
	private static final int RUNS_PER_TEST = 3;
	private static final int MAX_RETRIES = 3;
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-gcs";
	
	// Test files to download
	private static final String[] TEST_FILES = {
		"binary-1GB.bin",
		"binary-5GB.bin"
	};

	/**
	 * Main execution entry point for AltaStata streaming vs non-streaming performance comparison.
	 *
	 * @param args command-line arguments (unused)
	 * @throws IOException if account setup or file operations fail
	 */
	public static void main(String [] args) throws IOException  {

		AltaStataFileSystem altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
				Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "google.rsa.bob123");

		altaStataFileSystem.setPassword("123");

		System.out.println("=== Streaming vs Non-Streaming Performance Test ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Account: google.rsa.bob123");
		System.out.println("Bucket: performance-gcs");
		System.out.println("=============================================\n");

		for (String fileName : TEST_FILES) {
			System.out.println("\n=== Testing file: " + fileName + " ===");

			cleanupAltaStataPerformanceFiles(altaStataFileSystem);
			
			// Upload the file first to ensure it's in the cloud
			System.out.println("Uploading file to cloud first...");
			try {
				String localFile = file_path + "/" + fileName;
				String cloudPath = altaStataCloudFilePath(fileName, 0);
				List<CloudFileOperationStatus> stored = altaStataFileSystem.store(
					Arrays.asList(localFile), localFile, cloudPath, true);
				System.out.println("Upload completed with " + (stored != null ? stored.size() : 0) + " operations");
				Thread.sleep(2000); // Wait for upload to complete
			} catch (Exception e) {
				System.err.println("Upload failed: " + e.getMessage());
				continue; // Skip this file if upload fails
			}
			
			// Test with isStreaming=false
			System.out.println("Testing with isStreaming=false...");
			PerformanceResult nonStreamingResult = downloadFileWithRetry(altaStataFileSystem, fileName, RUNS_PER_TEST, false);
			printPerformanceResult("Non-Streaming", fileName, nonStreamingResult);
			
			// Test with isStreaming=true
			System.out.println("Testing with isStreaming=true...");
			PerformanceResult streamingResult = downloadFileWithRetry(altaStataFileSystem, fileName, RUNS_PER_TEST, true);
			printPerformanceResult("Streaming", fileName, streamingResult);
			
			// Compare results
			compareResults(fileName, nonStreamingResult, streamingResult);
		}

		LOGGER.warn("Streaming vs Non-Streaming performance test completed");
		System.exit(0);
	}
	
	/**
	 * Downloads a file via AltaStata with automatic retry logic, supporting both chunked/streaming and standard download.
	 *
	 * @param altaStataFileSystem file system handler instance
	 * @param fileName target filename to download
	 * @param runs iteration count
	 * @param isStreaming true to enable high performance HLS-compatible streaming; false for standard
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileWithRetry(AltaStataFileSystem altaStataFileSystem, String fileName, int runs, boolean isStreaming) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("Downloading %s from Google Cloud Storage via AltaStata with isStreaming=%s (%d runs)...\n", 
				file_path + "2/" + fileName, isStreaming, runs);
		System.out.format("  [DEBUG] Cloud source: performance-gcs/%s\n", fileName);

		// Warm-up run to avoid cold start effects
		System.out.println("  Performing warm-up run...");
		try {
			altaStataFileSystem.retrieve(file_path + "2", altaStataCloudFilePath(fileName, 0), true, System.currentTimeMillis(), false, isStreaming);
			Thread.sleep(2000); // Wait 2 seconds after warm-up
		} catch (Exception e) {
			System.err.println("  Warm-up run failed: " + e.getMessage());
		}

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					// Delete existing file to force real download
					File existingFile = new File(file_path + "2/" + fileName);
					if (existingFile.exists()) {
						existingFile.delete();
						System.out.format("  [DEBUG] Deleted existing file to force real download\n");
					}
					
					System.out.format("  [DEBUG] Calling retrieve: local=%s, cloud=%s, isStreaming=%s\n", 
							file_path + "2", altaStataCloudFilePath(fileName, 0), isStreaming);
					List<CloudFileOperationStatus> retrieved = 
							altaStataFileSystem.retrieve(file_path + "2", altaStataCloudFilePath(fileName, 0), true, System.currentTimeMillis(), false, true);
					
					// Check the operation status
					if (retrieved != null && !retrieved.isEmpty()) {
						for (CloudFileOperationStatus status : retrieved) {
							System.out.format("  [DEBUG] Operation status: %s, Error: %s\n", 
									status.getOperationState(), status.getError());
						}
					} else {
						System.out.format("  [DEBUG] No files found to download\n");
					}

					// Force actual file read to ensure download completion
					File tempFile = new File(file_path + "2/" + fileName);
					if (tempFile.exists()) {
						try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
							byte[] buffer = new byte[8192];
							long bytesRead = 0;
							while (fis.read(buffer) != -1) {
								bytesRead += buffer.length;
								if (bytesRead > 1024 * 1024) break; // Read first 1MB to verify download
							}
						}
					}

					// Small delay to ensure file is fully written
					Thread.sleep(100);
					
					// Verify the file was actually downloaded by checking its size and content
					File downloadedFile = new File(file_path + "2/" + fileName);
					File originalFile = new File(file_path + "/" + fileName);
					System.out.format("  [DEBUG] Downloaded file size: %d bytes, Expected: %d bytes\n", 
							downloadedFile.exists() ? downloadedFile.length() : 0, fileSize);
					if (!downloadedFile.exists() || downloadedFile.length() != fileSize) {
						throw new RuntimeException("Download verification failed - file size mismatch or file not found");
					}
					
					// Compare file content with original
					if (!compareFileContent(originalFile, downloadedFile)) {
						throw new RuntimeException("Download verification failed - file content mismatch");
					}
					System.out.format("  [DEBUG] Download verification passed for %s (size and content match)\n", fileName);

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
					
					// Longer delay between runs to avoid caching effects
					Thread.sleep(3000);
					
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
	 * Formats and prints out average duration and throughput statistics for the tested mode.
	 *
	 * @param mode label of the streaming or non-streaming mode
	 * @param fileName target filename
	 * @param result collected metrics
	 */
	private static void printPerformanceResult(String mode, String fileName, PerformanceResult result) {
		if (result.durations.isEmpty()) {
			System.out.println("  No successful runs for " + mode);
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
		
		System.out.println("\n  " + mode + " Performance Summary for " + fileName + ":");
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
	 * Compares the average timing and throughput differences between standard download and streaming download.
	 *
	 * @param fileName target filename
	 * @param nonStreaming non-streaming metrics
	 * @param streaming streaming metrics
	 */
	private static void compareResults(String fileName, PerformanceResult nonStreaming, PerformanceResult streaming) {
		if (nonStreaming.durations.isEmpty() || streaming.durations.isEmpty()) {
			System.out.println("  Cannot compare results - one or both tests failed");
			return;
		}
		
		double nonStreamingAvgDuration = nonStreaming.durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
		double streamingAvgDuration = streaming.durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
		double nonStreamingAvgThroughput = nonStreaming.throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		double streamingAvgThroughput = streaming.throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		
		double durationDiff = streamingAvgDuration - nonStreamingAvgDuration;
		double throughputDiff = streamingAvgThroughput - nonStreamingAvgThroughput;
		
		System.out.println("\n  Performance Comparison for " + fileName + ":");
		System.out.println("  =================================================");
		System.out.format("  Duration Difference: %.2f ms (Streaming - Non-Streaming)\n", durationDiff);
		System.out.format("  Throughput Difference: %.2f MB/s (Streaming - Non-Streaming)\n", throughputDiff);
		
		if (durationDiff < 0) {
			System.out.format("  ✓ Streaming is FASTER by %.2f ms\n", Math.abs(durationDiff));
		} else {
			System.out.format("  ✗ Streaming is SLOWER by %.2f ms\n", durationDiff);
		}
		
		if (throughputDiff > 0) {
			System.out.format("  ✓ Streaming has HIGHER throughput by %.2f MB/s\n", throughputDiff);
		} else {
			System.out.format("  ✗ Streaming has LOWER throughput by %.2f MB/s\n", Math.abs(throughputDiff));
		}
		
		System.out.println("  =================================================");
	}
	
	/**
	 * Computes standard deviation of runtimes.
	 *
	 * @param values captured numeric values
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
	 * Gets local file size.
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
	
	/**
	 * Compares the first and last parts of two files to verify integrity and correctness after download.
	 *
	 * @param file1 original file
	 * @param file2 downloaded file
	 * @return true if sizes and content headers/footers match; false otherwise
	 */
	private static boolean compareFileContent(File file1, File file2) {
		try {
			if (!file1.exists() || !file2.exists()) {
				return false;
			}
			
			if (file1.length() != file2.length()) {
				return false;
			}
			
			// For large files, compare only first and last 1MB to speed up verification
			long fileSize = file1.length();
			long compareSize = Math.min(1024 * 1024, fileSize / 10); // 1MB or 10% of file, whichever is smaller
			
			try (java.io.FileInputStream fis1 = new java.io.FileInputStream(file1);
				 java.io.FileInputStream fis2 = new java.io.FileInputStream(file2)) {
				
				byte[] buffer1 = new byte[(int) compareSize];
				byte[] buffer2 = new byte[(int) compareSize];
				
				// Compare beginning
				fis1.read(buffer1);
				fis2.read(buffer2);
				if (!java.util.Arrays.equals(buffer1, buffer2)) {
					return false;
				}
				
				// Compare end (for files larger than compareSize)
				if (fileSize > compareSize) {
					fis1.skip(fileSize - compareSize - compareSize);
					fis2.skip(fileSize - compareSize - compareSize);
					fis1.read(buffer1);
					fis2.read(buffer2);
					if (!java.util.Arrays.equals(buffer1, buffer2)) {
						return false;
					}
				}
				
				return true;
			}
		} catch (Exception e) {
			System.err.println("  [ERROR] File comparison failed: " + e.getMessage());
			return false;
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
