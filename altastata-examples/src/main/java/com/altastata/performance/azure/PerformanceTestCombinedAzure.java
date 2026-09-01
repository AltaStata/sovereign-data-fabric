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

package com.altastata.performance.azure;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.performance.PerformanceMetrics;
import com.altastata.performance.PerformanceProfile;
import com.altastata.utils.Account;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.ParallelTransferOptions;

/**
 * Combined performance test: direct Azure Blob vs AltaStata on Azure
 * ({@code azure.rsa.bob123}).
 *
 * <p>One cloud object per run (same as fixed GCP harness). Do not extrapolate to GCP/AWS.
 */
public class PerformanceTestCombinedAzure {

	private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceTestCombinedAzure.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";

	private static final int MAX_RETRIES = 3;

	private static final String DIRECT_AZURE_CONTAINER = "altastata-performance-test";
	private static final String DIRECT_AZURE_PREFIX = "performance-test/combined/";
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-azure";
	private static final String ALTASTATA_ACCOUNT = "azure.rsa.bob123";
	/** Unique per JVM so re-runs do not reuse warm object keys. */
	private static final String RUN_ID = Long.toString(System.currentTimeMillis());
	/** Match AWS multipart threshold: stream put for small blobs, parallel block upload for large. */
	private static final long AZURE_PARALLEL_UPLOAD_THRESHOLD_BYTES = 16L * 1024 * 1024;
	/** Slightly above Azure Netty’s 60s default; parallel block upload is the main large-file fix. */
	private static final Duration AZURE_HTTP_TIMEOUT = Duration.ofMinutes(2);


	private static BlobContainerClient azureContainer;
	private static AltaStataFileSystem altaStataFileSystem;
	private static Map<String, Path> testFiles = new HashMap<>();

	public static void main(String[] args) throws IOException {
		args = normalizeArgs(args);
		boolean downloadOnly = args != null && args.length >= 2
				&& "download-only".equalsIgnoreCase(args[0]);
		boolean fileOnly = args != null && args.length >= 2
				&& "file-only".equalsIgnoreCase(args[0]);
		boolean altastataOnly = args != null && args.length >= 2
				&& "altastata-only".equalsIgnoreCase(args[0]);
		PerformanceProfile profile = (downloadOnly || fileOnly || altastataOnly)
				? PerformanceProfile.full()
				: PerformanceProfile.fromArgs(args);

		System.out.println("=== COMBINED AZURE BLOB vs ALTASTATA PERFORMANCE TEST ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Direct Azure container: " + DIRECT_AZURE_CONTAINER);
		System.out.println("AltaStata Account: " + ALTASTATA_ACCOUNT);
		System.out.println("AltaStata prefix: " + ALTASTATA_CLOUD_BUCKET);
		System.out.println("Object name run-id (anti-cache): " + RUN_ID);
		if (downloadOnly) {
			System.out.println("Mode: download-only resume for " + args[1]);
		} else if (fileOnly) {
			System.out.println("Mode: file-only (upload+download) for " + args[1]);
		} else if (altastataOnly) {
			System.out.println("Mode: altastata-only (upload+download AltaStata; Azure download if blobs exist) for " + args[1]);
		}
		profile.printBanner();
		System.out.println("==========================================================\n");

		initializeAzureBlob();
		initializeAltaStata();
		loadTestFiles();
		if (!downloadOnly) {
			cleanupAltaStataPerformanceFiles();
		}

		if (downloadOnly || fileOnly || altastataOnly) {
			String fileName = args[1];
			int runs = args.length >= 3
					? Integer.parseInt(args[2])
					: defaultRunsForFile(fileName, profile);
			if (downloadOnly) {
				runDownloadOnlyTest(fileName, runs);
				LOGGER.warn("Download-only Azure vs AltaStata test completed for {}", fileName);
			} else if (altastataOnly) {
				runAltaStataOnlyTest(fileName, runs);
				LOGGER.warn("AltaStata-only Azure vs AltaStata test completed for {}", fileName);
			} else {
				runCombinedTest(fileName, runs, "File-only");
				LOGGER.warn("File-only Azure vs AltaStata test completed for {}", fileName);
			}
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

		cleanupCloudStorage();
		LOGGER.warn("Combined Azure vs AltaStata performance test completed");
		// Account event loop + Azure/Netty threads are non-daemon; without this, Gradle runExample hangs.
		System.exit(0);
	}

	private static void initializeAzureBlob() throws IOException {
		String connectionString = resolveAzureConnectionString();
		HttpClient httpClient = new NettyAsyncHttpClientBuilder()
				.responseTimeout(AZURE_HTTP_TIMEOUT)
				.writeTimeout(AZURE_HTTP_TIMEOUT)
				.readTimeout(AZURE_HTTP_TIMEOUT)
				.build();
		BlobServiceClient service = new BlobServiceClientBuilder()
				.connectionString(connectionString)
				.httpClient(httpClient)
				.buildClient();
		azureContainer = service.getBlobContainerClient(DIRECT_AZURE_CONTAINER);
		if (!azureContainer.exists()) {
			azureContainer.create();
			System.out.println("Created Azure container: " + DIRECT_AZURE_CONTAINER);
		}
		System.out.println("Successfully initialized Azure Blob client (HTTP timeouts "
				+ AZURE_HTTP_TIMEOUT.toMinutes() + "m) for account endpoint from connection string");
	}

	/**
	 * Prefer a real {@code AZURE_STORAGE_CONNECTION_STRING}; otherwise load
	 * {@code adminStorageConnectionString} from {@code ~/.altastata/admin/azure_admin.properties}
	 * (same storage account as {@code azure.rsa.bob123}).
	 */
	private static String resolveAzureConnectionString() throws IOException {
		String env = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
		if (env != null && env.length() > 50 && env.contains("AccountKey=")) {
			System.out.println("Using AZURE_STORAGE_CONNECTION_STRING from environment");
			return env;
		}

		Path adminProps = Paths.get(System.getProperty("user.home"), ".altastata", "admin", "azure_admin.properties");
		if (!Files.exists(adminProps)) {
			throw new IOException("Azure connection string not found. Set AZURE_STORAGE_CONNECTION_STRING "
					+ "or provide " + adminProps);
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(adminProps)) {
			props.load(in);
		}
		String cs = props.getProperty("adminStorageConnectionString");
		if (cs == null || cs.isEmpty()) {
			throw new IOException("adminStorageConnectionString missing in " + adminProps);
		}
		System.out.println("Using adminStorageConnectionString from: " + adminProps);
		return cs;
	}

	private static void initializeAltaStata() {
		try {
			altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
					Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + ALTASTATA_ACCOUNT);
			ensureAltaStataPassword();
			System.out.println("Successfully initialized AltaStata FileSystem (" + ALTASTATA_ACCOUNT + ")");
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize AltaStata Azure account", e);
		}
	}

	/** Session password expires after ~15 min idle (Account.passwordTimeoutInterval); re-apply before ops. */
	private static void ensureAltaStataPassword() {
		altaStataFileSystem.setPassword("123");
	}

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

	private static void runCombinedTest(String fileName, int runs, String testCategory) {
		System.out.println("\n=== " + testCategory + " Test: " + fileName + " ===");

		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}

		cleanupAltaStataPerformanceFiles();
		cleanupLocalDownloadDir();

		PerformanceResult azureUploadResult = uploadFileDirectAzure(fileName, filePath, runs);
		PerformanceResult altaStataUploadResult = uploadFileAltaStata(fileName, runs);
		boolean report = !"Warm-up".equals(testCategory);
		if (report) {
			printComparison("UPLOAD", fileName, azureUploadResult, altaStataUploadResult);
		} else {
			System.out.println("  (warm-up — comparison omitted; not scored)");
		}

		PerformanceResult azureDownloadResult = downloadFileDirectAzure(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		if (report) {
			printComparison("DOWNLOAD", fileName, azureDownloadResult, altaStataDownloadResult);
		}
	}

	/** Re-run download only; expects upload blobs from a prior combined run. */
	private static void runDownloadOnlyTest(String fileName, int runs) {
		System.out.println("\n=== Download-only (resume): " + fileName + " (" + runs + " runs) ===");

		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}

		cleanupLocalDownloadDir();

		PerformanceResult azureDownloadResult = downloadFileDirectAzure(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		printComparison("DOWNLOAD", fileName, azureDownloadResult, altaStataDownloadResult);
	}

	/**
	 * AltaStata upload+download only; Azure download uses existing direct blobs (skip Azure upload).
	 * Use when Azure upload already finished and password session expired during that idle wait.
	 */
	private static void runAltaStataOnlyTest(String fileName, int runs) {
		System.out.println("\n=== AltaStata-only (resume): " + fileName + " (" + runs + " runs) ===");

		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}

		cleanupAltaStataPerformanceFiles();
		cleanupLocalDownloadDir();

		PerformanceResult altaStataUploadResult = uploadFileAltaStata(fileName, runs);
		printComparison("UPLOAD", fileName, emptyResult(filePath.toFile().length()), altaStataUploadResult);

		PerformanceResult azureDownloadResult = downloadFileDirectAzure(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		printComparison("DOWNLOAD", fileName, azureDownloadResult, altaStataDownloadResult);
	}

	private static PerformanceResult emptyResult(long fileSize) {
		return new PerformanceResult(new ArrayList<>(), new ArrayList<>(), fileSize);
	}

	private static int defaultRunsForFile(String fileName, PerformanceProfile profile) {
		if (fileName.contains("5GB")) {
			return profile.xlargeFileRuns;
		}
		if (fileName.contains("1GB") || fileName.contains("100MB")) {
			return fileName.contains("100MB") ? profile.mediumFileRuns : profile.largeFileRuns;
		}
		return profile.smallFileRuns > 0 ? profile.smallFileRuns : 3;
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

	private static PerformanceResult uploadFileDirectAzure(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();

		System.out.format("Direct Azure Upload: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String blobName = DIRECT_AZURE_PREFIX + fileName + "-direct-" + RUN_ID + "-" + i;
					BlobClient blob = azureContainer.getBlobClient(blobName);
					if (fileSize >= AZURE_PARALLEL_UPLOAD_THRESHOLD_BYTES) {
						// Parallel block upload (like AWS TransferManager) — avoids 60s single-stream Netty timeouts.
						ParallelTransferOptions transfer = new ParallelTransferOptions()
								.setBlockSizeLong(8L * 1024 * 1024)
								.setMaxConcurrency(8);
						blob.uploadFromFile(filePath.toString(), transfer, null, null, null, null, null);
					} else {
						try (InputStream in = new FileInputStream(filePath.toFile())) {
							blob.upload(in, fileSize, true);
						}
					}
					if (!blob.exists()) {
						throw new IOException("Upload reported success but blob missing: " + blobName);
					}

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  Azure Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  Azure Run %d failed (attempt %d/%d): %s\n",
							i + 1, retries, MAX_RETRIES, e.getMessage());
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
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String localFile = file_path + "/" + fileName;
					String cloudPath = altaStataCloudFilePath(fileName, i);
					List<CloudFileOperationStatus> stored =
							altaStataFileSystem.store(Arrays.asList(localFile), localFile, cloudPath, true);
					assertStoreSucceeded(stored, fileName);

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					if (durationInMillis < 1000 && fileSize > 100 * 1024 * 1024L) {
						throw new IOException("Upload finished too quickly (" + durationInMillis
								+ " ms) for " + fileSize + " bytes — likely failed silently");
					}
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  AltaStata Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n",
							i + 1, retries, MAX_RETRIES, e.getMessage());
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

	private static void assertStoreSucceeded(List<CloudFileOperationStatus> stored, String fileName)
			throws IOException {
		if (stored == null || stored.isEmpty()) {
			throw new IOException("store returned no results for " + fileName);
		}
		for (CloudFileOperationStatus status : stored) {
			if (status.getError() != null) {
				throw new IOException("store failed for " + fileName + ": " + status.getError(),
						status.getErrorTrace());
			}
		}
	}

	private static PerformanceResult downloadFileDirectAzure(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();

		System.out.format("Direct Azure Download: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String blobName = DIRECT_AZURE_PREFIX + fileName + "-direct-" + RUN_ID + "-" + i;
					BlobClient blob = azureContainer.getBlobClient(blobName);
					Path tempFile = Files.createTempFile("azure-download-test-", ".tmp");
					try {
						try (OutputStream out = Files.newOutputStream(tempFile)) {
							blob.downloadStream(out);
						}
					} finally {
						Files.deleteIfExists(tempFile);
					}

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  Azure Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  Azure Run %d failed (attempt %d/%d): %s\n",
							i + 1, retries, MAX_RETRIES, e.getMessage());
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
					ensureAltaStataPassword();
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					List<CloudFileOperationStatus> retrieved =
							altaStataFileSystem.retrieve(file_path + "2",
									altaStataCloudFilePath(fileName, i), true,
									System.currentTimeMillis(), false, true);
					if (retrieved == null || retrieved.isEmpty()) {
						throw new IOException("retrieve returned no results for " + fileName);
					}
					for (CloudFileOperationStatus status : retrieved) {
						if (status.getError() != null) {
							throw new IOException("retrieve failed for " + fileName + ": " + status.getError(),
									status.getErrorTrace());
						}
					}

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  AltaStata Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  AltaStata Run %d failed (attempt %d/%d): %s\n",
							i + 1, retries, MAX_RETRIES, e.getMessage());
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

	private static void printComparison(String operation, String fileName,
			PerformanceResult azureResult, PerformanceResult altaStataResult) {
		PerformanceMetrics.printSideBySide(
				operation, fileName, "DIRECT AZURE", "Azure",
				azureResult.durations, azureResult.throughputs,
				altaStataResult.durations, altaStataResult.throughputs);
	}

	private static double calculateStdDev(List<? extends Number> values, double mean) {
		if (values.size() <= 1) {
			return 0.0;
		}
		double sumSquaredDiff = values.stream()
				.mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
				.sum();
		return Math.sqrt(sumSquaredDiff / (values.size() - 1));
	}

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

	private static void cleanupCloudStorage() {
		System.out.println("\nCleaning up cloud storage...");
		try {
			ListBlobsOptions options = new ListBlobsOptions().setPrefix(DIRECT_AZURE_PREFIX);
			for (BlobItem item : azureContainer.listBlobs(options, null)) {
				azureContainer.getBlobClient(item.getName()).deleteIfExists();
			}
			cleanupAltaStataPerformanceFiles();
			cleanupLocalDownloadDir();
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
