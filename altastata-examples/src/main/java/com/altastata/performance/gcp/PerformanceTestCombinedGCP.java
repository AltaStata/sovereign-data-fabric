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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.performance.PerformanceMemoryProbe;
import com.altastata.performance.PerformanceMetrics;
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.utils.Account;
import com.amazonaws.AmazonServiceException;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Combined performance test comparing direct Google Cloud Storage vs AltaStata/GCP.
 *
 * <p><b>GCP only.</b> Results apply to the Google Cloud Storage backend used here
 * ({@code google.rsa.bob123}). Throughput on AWS S3 or Azure Blob can be higher;
 * use {@code com.altastata.performance.aws} tests for S3 baselines.
 * 
 * Runs both tests side by side for each file:
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
 * Features:
 * - Side-by-side comparison
 * - Statistical analysis
 * - Throughput calculations
 * - Memory usage monitoring
 * - Comprehensive reporting
 *
 * @author AltaStata
 */
public class PerformanceTestCombinedGCP {

	private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestCombinedGCP.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";
	
	// Performance test configuration
	private static final int MAX_RETRIES = 3;
	
	// Google Cloud Storage configuration
	private static final String DIRECT_GCS_BUCKET = "altastata-performance-test";
	private static final String DIRECT_GCS_PREFIX = "performance-test/combined/";
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-gcs";
	/** Unique per JVM so re-runs do not reuse warm object keys. */
	private static final String RUN_ID = Long.toString(System.currentTimeMillis());

	
	private static Storage gcsStorage;
	private static AltaStataFileSystem altaStataFileSystem;
	private static PerformanceMemoryProbe memoryProbe;
	private static Map<String, Path> testFiles = new HashMap<>();

	/**
	 * Main execution entry point for combined direct GCP and AltaStata performance comparisons.
	 *
	 * @param args command-line arguments (unused)
	 * @throws IOException if Google Cloud Storage initialization or file operations fail
	 */
	public static void main(String [] args) throws IOException  {
		args = normalizeArgs(args);
		boolean fileOnly = args != null && args.length >= 2
				&& "file-only".equalsIgnoreCase(args[0]);
		GcpPerformanceProfile profile = fileOnly
				? GcpPerformanceProfile.full()
				: GcpPerformanceProfile.fromArgs(args);

		System.out.println("=== COMBINED GCP vs ALTASTATA PERFORMANCE TEST ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Direct GCS Bucket: " + DIRECT_GCS_BUCKET);
		System.out.println("AltaStata Account: google.rsa.bob123");
		System.out.println("AltaStata Bucket: performance-gcs");
		System.out.println("Object name run-id (anti-cache): " + RUN_ID);
		if (fileOnly) {
			System.out.println("Mode: file-only (upload+download) for " + args[1]);
		}
		profile.printBanner();
		System.out.println("==================================================\n");

		// Initialize both systems
		initializeGoogleCloudStorage();
		initializeAltaStata();
		memoryProbe = PerformanceMemoryProbe.start(altaStataFileSystem);

		// Load test files
		loadTestFiles();
		cleanupAltaStataPerformanceFiles();

		if (fileOnly) {
			String fileName = args[1];
			int runs = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
			runCombinedTest(fileName, runs, "File-only");
			cleanupCloudStorage();
			if (memoryProbe != null) {
				memoryProbe.stop();
			}
			LOGGER.warn("File-only GCP vs AltaStata test completed for {}", fileName);
			System.exit(0);
		}

		if (profile.warmupRuns > 0) {
			runCombinedTest("text-1MB.txt", profile.warmupRuns, "Warm-up");
			runCombinedTest("binary-1MB.bin", profile.warmupRuns, "Warm-up");
		}

		if (profile.smallFileRuns > 0) {
			runCombinedTest("text-1MB.txt", profile.smallFileRuns, "Small Files");
			runCombinedTest("binary-1MB.bin", profile.smallFileRuns, "Small Files");
			runCombinedTest("text-10MB.txt", profile.smallFileRuns, "Small Files");
			runCombinedTest("binary-10MB.bin", profile.smallFileRuns, "Small Files");
		}

		if (profile.mediumFileRuns > 0) {
			runCombinedTest("text-100MB.txt", profile.mediumFileRuns, "Medium Files");
			runCombinedTest("binary-100MB.bin", profile.mediumFileRuns, "Medium Files");
		}

		if (profile.includeLargeFiles) {
			runCombinedTest("text-1GB.txt", profile.largeFileRuns, "Large Files");
			runCombinedTest("binary-1GB.bin", profile.largeFileRuns, "Large Files");
		}

		if (profile.includeXLargeFiles) {
			runCombinedTest("text-5GB.txt", profile.xlargeFileRuns, "Very Large Files");
			runCombinedTest("binary-5GB.bin", profile.xlargeFileRuns, "Very Large Files");
		}

		// Clean up
		cleanupCloudStorage();
		if (memoryProbe != null) {
			memoryProbe.stop();
		}

		LOGGER.warn("Combined GCP vs AltaStata performance test completed");
		System.exit(0);
	}
	
	/**
	 * Initializes Google Cloud Storage from {@code GOOGLE_APPLICATION_CREDENTIALS},
	 * or default application credentials if that file is unset.
	 *
	 * @throws IOException if storage fails to initialize
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
	 * Initializes the AltaStataFileSystem instance using the predefined google.rsa.bob123 account home directory.
	 */
	private static void initializeAltaStata() {
		try {
			altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
				Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "google.rsa.bob123");
			ensureAltaStataPassword();
			System.out.println("Successfully initialized AltaStata FileSystem");
		} catch (Exception e) {
			System.err.println("Failed to initialize AltaStata: " + e.getMessage());
			throw new RuntimeException("Failed to initialize AltaStata", e);
		}
	}

	/** Session password expires after ~15 min idle; re-apply before AltaStata ops. */
	private static void ensureAltaStataPassword() {
		altaStataFileSystem.setPassword("123");
	}
	
	/**
	 * Pre-scans and loads all benchmark files of various sizes.
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
	 * Orchestrates comparative upload and download runs across both direct GCS and AltaStata filesystems.
	 *
	 * @param fileName benchmark file name
	 * @param runs iteration count
	 * @param testCategory label of the test suite
	 */
	private static void runCombinedTest(String fileName, int runs, String testCategory) {
		System.out.println("\n=== " + testCategory + " Test: " + fileName + " ===");
		if (memoryProbe != null) {
			memoryProbe.mark(testCategory + " " + fileName);
		}
		
		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}

		cleanupAltaStataPerformanceFiles();
		cleanupLocalDownloadDir();
		
		// Run both tests
		PerformanceResult gcpUploadResult = uploadFileDirectGCS(fileName, filePath, runs);
		PerformanceResult altaStataUploadResult = uploadFileAltaStata(fileName, runs);

		boolean report = !"Warm-up".equals(testCategory);
		if (report) {
			printComparison("UPLOAD", fileName, gcpUploadResult, altaStataUploadResult);
		} else {
			System.out.println("  (warm-up — comparison omitted; not scored)");
		}

		// Run download tests
		PerformanceResult gcpDownloadResult = downloadFileDirectGCS(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs, false);

		if (report) {
			printComparison("DOWNLOAD", fileName, gcpDownloadResult, altaStataDownloadResult);
		}
	}
	
	/**
	 * Benchmarks direct GCS file upload.
	 *
	 * @param fileName destination file key
	 * @param filePath local file path to upload
	 * @param runs iteration count
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult uploadFileDirectGCS(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();
		
		System.out.format("Direct GCS Upload: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectName = DIRECT_GCS_PREFIX + fileName + "-direct-" + RUN_ID + "-" + i;
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

					System.out.format("  GCS Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					PerformanceMetrics.coolDown();
					
				} catch (Exception e) {
					retries++;
					System.err.format("  GCS Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}
	
	/**
	 * Benchmarks AltaStata secure file upload.
	 *
	 * @param fileName target filename to upload
	 * @param runs iteration count
	 * @return PerformanceResult metrics
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
					ensureAltaStataPassword();
					if (memoryProbe != null) {
						memoryProbe.mark("AS-upload " + fileName + " run " + (i + 1));
					}
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String localFile = file_path + "/" + fileName;
					String cloudPath = altaStataCloudFilePath(fileName, i);
					List<CloudFileOperationStatus> stored =
							altaStataFileSystem.store(Arrays.asList(localFile), localFile, cloudPath, true);
					assertOpsSucceeded(stored, "store", fileName);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					if (durationInMillis < 1000 && fileSize > 100 * 1024 * 1024L) {
						throw new IOException("Upload finished too quickly (" + durationInMillis
								+ " ms) for " + fileSize + " bytes — likely failed silently");
					}
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);
					long memoryUsed = endMemory - startMemory;

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);

					System.out.format("  AltaStata Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					PerformanceMetrics.coolDown();
					if (memoryProbe != null) {
						memoryProbe.afterGc("AS-upload " + fileName + " run " + (i + 1));
					}
					
				} catch (AmazonServiceException e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getErrorMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				} catch (Exception e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}
	
	/**
	 * Benchmarks direct GCS file download.
	 *
	 * @param fileName source cloud key name
	 * @param filePath local path tracking expected size
	 * @param runs iteration count
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileDirectGCS(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();
		
		System.out.format("Direct GCS Download: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectName = DIRECT_GCS_PREFIX + fileName + "-direct-" + RUN_ID + "-" + i;
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

					System.out.format("  GCS Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					PerformanceMetrics.coolDown();
					
				} catch (Exception e) {
					retries++;
					System.err.format("  GCS Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}
	
	/**
	 * Benchmarks AltaStata secure file download.
	 *
	 * @param fileName target source file name
	 * @param runs iteration count
	 * @param isStreaming true to use HLS/HTTP streaming; false for standard downloads
	 * @return PerformanceResult metrics
	 */
	private static PerformanceResult downloadFileAltaStata(String fileName, int runs, boolean isStreaming) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = getFileSize(fileName);
		
		System.out.format("AltaStata Download: %s with streaming=%s (%d runs)...\n", fileName, isStreaming, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			
			while (retries < MAX_RETRIES && !success) {
				try {
					ensureAltaStataPassword();
					if (memoryProbe != null) {
						memoryProbe.mark("AS-download " + fileName + " run " + (i + 1));
					}
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					List<CloudFileOperationStatus> retrieved = 
							altaStataFileSystem.retrieve(file_path + "2", altaStataCloudFilePath(fileName, i), true, System.currentTimeMillis(), isStreaming, true);
					assertOpsSucceeded(retrieved, "retrieve", fileName);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);
					long memoryUsed = endMemory - startMemory;

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);

					System.out.format("  AltaStata Run %d: %d ms, %.2f MB/s, Memory: %d KB\n", 
							i + 1, durationInMillis, throughputMBps, memoryUsed / 1024);
					
					success = true;
					PerformanceMetrics.coolDown();
					if (memoryProbe != null) {
						memoryProbe.afterGc("AS-download " + fileName + " run " + (i + 1));
					}
					
				} catch (AmazonServiceException e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getErrorMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				} catch (Exception e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n", i + 1, retries, MAX_RETRIES, e.getMessage());
					if (retries >= MAX_RETRIES) {
						System.err.println("  Max retries reached, skipping this run");
						break;
					}
					PerformanceMetrics.recoverAfterFailure(e);
				}
			}
		}
		
		return new PerformanceResult(durations, throughputs, fileSize);
	}

	/**
	 * Formats and prints comparative throughput and timing data comparing GCS vs AltaStata.
	 *
	 * @param operation name of the operation
	 * @param fileName target benchmark filename
	 * @param gcpResult direct GCS metrics
	 * @param altaStataResult AltaStata metrics
	 */
	private static void printComparison(String operation, String fileName, PerformanceResult gcpResult, PerformanceResult altaStataResult) {
		PerformanceMetrics.printSideBySide(
				operation, fileName, "DIRECT GCS", "GCS",
				gcpResult.durations, gcpResult.throughputs,
				altaStataResult.durations, altaStataResult.throughputs);
	}

	/**
	 * Computes standard deviation for comparison results.
	 *
	 * @param values list of numeric values
	 * @param mean average of the values
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
	 * Gets the local file size on disk.
	 *
	 * @param fileName target local file name
	 * @return file size in bytes
	 */
	private static long getFileSize(String fileName) {
		File file = new File(file_path + "/" + fileName);
		return file.exists() ? file.length() : 0;
	}

	/**
	 * Anti-cache object name with the original extension kept at the end
	 * (e.g. {@code text-1GB-altastata-&lt;run&gt;-0.txt}) so account
	 * {@code compresstypes} (default {@code .*.(txt|csv|parquet)}) still matches.
	 */
	private static String altaStataCloudFilePath(String fileName, int runIndex) {
		int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		String ext = dot > 0 ? fileName.substring(dot) : "";
		return ALTASTATA_CLOUD_BUCKET + "/" + base + "-altastata-" + RUN_ID + "-" + runIndex + ext;
	}

	private static void assertOpsSucceeded(List<CloudFileOperationStatus> statuses, String op, String fileName)
			throws IOException {
		if (statuses == null || statuses.isEmpty()) {
			throw new IOException(op + " returned no results for " + fileName);
		}
		for (CloudFileOperationStatus status : statuses) {
			if (status.getError() != null) {
				throw new IOException(op + " failed for " + fileName + ": " + status.getError(),
						status.getErrorTrace());
			}
		}
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

	/** Deletes downloaded copies under ~/.altastata/test-files2 (one object per run accumulates fast). */
	private static void cleanupLocalDownloadDir() {
		Path downloadDir = Paths.get(file_path + "2");
		if (!Files.isDirectory(downloadDir)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(downloadDir)) {
			walk.sorted(java.util.Comparator.reverseOrder())
					.filter(p -> !p.equals(downloadDir))
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException e) {
							System.err.println("Local download cleanup warning: " + e.getMessage());
						}
					});
		} catch (IOException e) {
			System.err.println("Local download cleanup warning: " + e.getMessage());
		}
	}
	
	/**
	 * Deletes GCS benchmark objects created in this comparison.
	 */
	private static void cleanupCloudStorage() {
		System.out.println("\nCleaning up cloud storage...");
		
		try {
			// Clean up direct GCS objects
			for (Blob blob : gcsStorage.list(DIRECT_GCS_BUCKET, 
					Storage.BlobListOption.prefix(DIRECT_GCS_PREFIX)).iterateAll()) {
				blob.delete();
			}
			cleanupAltaStataPerformanceFiles();
			cleanupLocalDownloadDir();
			System.out.println("Cleanup completed successfully");
			
		} catch (Exception e) {
			System.err.println("Error during cleanup: " + e.getMessage());
		}
	}
	
	/** Gradle runExample passes -PappArgs as one string; split for multi-arg profiles. */
	private static String[] normalizeArgs(String[] args) {
		if (args == null || args.length != 1) {
			return args;
		}
		String single = args[0].trim();
		if (single.isEmpty() || !single.contains(" ")) {
			return args;
		}
		return single.split("\\s+");
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
