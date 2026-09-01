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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Enhanced performance test for direct Google Cloud Storage upload and download operations.
 *
 * <p><b>GCP only.</b> Do not treat these numbers as AWS S3 or Azure Blob results.
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
 * - Memory usage monitoring
 * - Comprehensive reporting
 * - Better error handling and retry logic
 *
 * @author AltaStata
 */
public class GoogleCloudPerformanceTest {

	private static Logger LOGGER = LoggerFactory.getLogger(GoogleCloudPerformanceTest.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";
	
	// Performance test configuration
	private static final int MAX_RETRIES = 3;
	
	// Google Cloud Storage configuration
	private static final String DIRECT_GCS_BUCKET = "altastata-performance-test";
	private static final String DIRECT_GCS_PREFIX = "performance-test/direct/";
	
	private static Storage gcsStorage;
	private static Map<String, Path> testFiles = new HashMap<>();

	/**
	 * Main execution entry point for direct GCS baseline performance benchmarks.
	 *
	 * @param args command-line arguments (unused)
	 * @throws IOException if Google Cloud Storage initialization or file operations fail
	 */
	public static void main(String [] args) throws IOException  {

		GcpPerformanceProfile profile = GcpPerformanceProfile.fromArgs(args);

		System.out.println("=== DIRECT GOOGLE CLOUD STORAGE PERFORMANCE TEST ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Bucket: " + DIRECT_GCS_BUCKET);
		System.out.println("Prefix: " + DIRECT_GCS_PREFIX);
		profile.printBanner();
		System.out.println("==================================================\n");

		// Initialize Google Cloud Storage
		initializeGoogleCloudStorage();
		
		// Load test files
		loadTestFiles();

		// Warm up with smaller files
		runPerformanceTest("text-1MB.txt", profile.warmupRuns, "Warm-up");
		runPerformanceTest("binary-1MB.bin", profile.warmupRuns, "Warm-up");

		// Test small files
		runPerformanceTest("text-1MB.txt", profile.smallFileRuns, "Small Files");
		runPerformanceTest("binary-1MB.bin", profile.smallFileRuns, "Small Files");
		runPerformanceTest("text-10MB.txt", profile.smallFileRuns, "Small Files");
		runPerformanceTest("binary-10MB.bin", profile.smallFileRuns, "Small Files");

		// Test medium files
		runPerformanceTest("text-100MB.txt", profile.mediumFileRuns, "Medium Files");
		runPerformanceTest("binary-100MB.bin", profile.mediumFileRuns, "Medium Files");

		if (profile.includeLargeFiles) {
			runPerformanceTest("text-1GB.txt", profile.largeFileRuns, "Large Files");
			runPerformanceTest("binary-1GB.bin", profile.largeFileRuns, "Large Files");
		}

		if (profile.includeXLargeFiles) {
			runPerformanceTest("text-5GB.txt", profile.xlargeFileRuns, "Very Large Files");
			runPerformanceTest("binary-5GB.bin", profile.xlargeFileRuns, "Very Large Files");
		}

		// Clean up
		cleanupCloudStorage();
		
		LOGGER.warn("Direct Google Cloud Storage performance test completed");
	}
	
	/**
	 * Initializes the Google Cloud Storage client from {@code GOOGLE_APPLICATION_CREDENTIALS},
	 * or default application credentials if that file is unset.
	 *
	 * @throws IOException if the storage service fails to initialize
	 */
	private static void initializeGoogleCloudStorage() throws IOException {
		String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		if (credentialsPath != null && !credentialsPath.isEmpty() && Files.exists(Paths.get(credentialsPath))) {
			System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath);
			System.out.println("Using Google Cloud credentials from: " + credentialsPath);
			
			try {
				ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(
					Files.newInputStream(Paths.get(credentialsPath)));
				
				String projectId = credentials.getProjectId();
				gcsStorage = StorageOptions.newBuilder()
					.setCredentials(credentials)
					.setProjectId(projectId)
					.build()
					.getService();
				
				System.out.println("Successfully initialized GCS storage with explicit credentials for project: " + projectId);
			} catch (Exception e) {
				System.err.println("Failed to load credentials: " + e.getMessage());
				throw new IOException("Failed to initialize Google Cloud Storage", e);
			}
		} else {
			System.out.println("Google Cloud credentials not found at: " + credentialsPath);
			gcsStorage = StorageOptions.getDefaultInstance().getService();
		}
	}
	
	/**
	 * Scans and loads local test benchmark files (text and binary formats) of various sizes (1MB to 5GB)
	 * under the test-files directory.
	 */
	private static void loadTestFiles() {
		String[] fileNames = {
			"text-1MB.txt", "text-10MB.txt", "text-100MB.txt", "text-1GB.txt", "text-5GB.txt",
			"binary-1MB.bin", "binary-10MB.bin", "binary-100MB.bin", "binary-1GB.bin", "binary-5GB.bin"
		};
		
		for (String fileName : fileNames) {
			Path filePath = Paths.get(file_path, fileName);
			if (Files.exists(filePath)) {
				testFiles.put(fileName, filePath);
				System.out.println("Loaded test file: " + fileName + " (" + filePath.toFile().length() + " bytes)");
			} else {
				System.out.println("Test file not found: " + filePath);
			}
		}
		System.out.println("Loaded " + testFiles.size() + " test files\n");
	}
	
	/**
	 * Orchestrates sequential upload and download benchmarks for a given file.
	 *
	 * @param fileName the target file name to test
	 * @param runs the number of sequential iterations
	 * @param testCategory the category label for reporting
	 */
	private static void runPerformanceTest(String fileName, int runs, String testCategory) {
		System.out.println("\n=== " + testCategory + " Test: " + fileName + " ===");
		
		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}
		
		// Upload test
		PerformanceResult uploadResult = uploadFileWithRetry(fileName, filePath, runs);
		printPerformanceResult("UPLOAD", fileName, uploadResult);
		
		// Download test
		PerformanceResult downloadResult = downloadFileWithRetry(fileName, filePath, runs);
		printPerformanceResult("DOWNLOAD", fileName, downloadResult);
	}
	
	/**
	 * Benchmarks streaming direct Google Cloud Storage upload operations with automatic retry logic.
	 *
	 * @param fileName the destination object key name
	 * @param filePath local path of the file to upload
	 * @param runs the number of sequential iterations
	 * @return collected PerformanceResult metrics
	 */
	private static PerformanceResult uploadFileWithRetry(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();
		
		System.out.format("Uploading %s to Direct Google Cloud Storage (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectName = DIRECT_GCS_PREFIX + fileName + "-" + i;
					BlobId blobId = BlobId.of(DIRECT_GCS_BUCKET, objectName);
					BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
					
					// Use streaming upload to avoid OutOfMemoryError for large files
					try (InputStream inputStream = Files.newInputStream(filePath)) {
						gcsStorage.create(blobInfo, inputStream);
					}

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
	 * Benchmarks direct Google Cloud Storage download/retrieval operations with automatic retry logic.
	 *
	 * @param fileName source object key name
	 * @param filePath local path representing the expected file size
	 * @param runs the number of sequential iterations
	 * @return collected PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileWithRetry(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length(); // Keep for throughput calculation
		
		System.out.format("Downloading %s from Direct Google Cloud Storage (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectName = DIRECT_GCS_PREFIX + fileName + "-" + i;
					BlobId blobId = BlobId.of(DIRECT_GCS_BUCKET, objectName);
					
					// Use downloadTo with temporary file for realistic streaming
					Path tempFile = Files.createTempFile("download-test-", ".tmp");
					try {
						gcsStorage.downloadTo(blobId, Files.newOutputStream(tempFile));
					} finally {
						// Clean up temporary file
						Files.deleteIfExists(tempFile);
					}

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
	 * Formats and prints out collected average, standard deviation, percentile (P50, P95, P99),
	 * and min/max duration metrics for a specific direct Google Cloud Storage operation.
	 *
	 * @param operation name of the operation (e.g. "UPLOAD", "DOWNLOAD")
	 * @param fileName the target benchmark file name
	 * @param result the captured performance metrics
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
	 * Computes the standard deviation for a list of numerical durations or throughput values.
	 *
	 * @param values list of measured numeric values
	 * @param mean the computed average/mean of those values
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
	 * Deletes all temporary/benchmark cloud objects created under the direct GCS bucket prefix
	 * to ensure storage cleanups.
	 */
	private static void cleanupCloudStorage() {
		System.out.println("\nCleaning up cloud storage...");
		
		try {
			// Clean up direct GCS objects
			for (Blob blob : gcsStorage.list(DIRECT_GCS_BUCKET, 
					Storage.BlobListOption.prefix(DIRECT_GCS_PREFIX)).iterateAll()) {
				blob.delete();
			}
			System.out.println("Cleanup completed successfully");
			
		} catch (Exception e) {
			System.err.println("Error during cleanup: " + e.getMessage());
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
