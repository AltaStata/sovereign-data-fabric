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

package com.altastata.performance.aws;

import java.io.File;
import java.io.IOException;
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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.performance.PerformanceMetrics;
import com.altastata.performance.PerformanceProfile;
import com.altastata.utils.Account;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;

/**
 * Combined performance test: direct AWS S3 vs AltaStata on AWS
 * ({@code amazon.rsa.bob123}).
 *
 * <p>One cloud object per run (same as fixed GCP/Azure harness). Do not extrapolate across clouds.
 */
public class PerformanceTestCombinedAWS {

	private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceTestCombinedAWS.class);
	static String file_path = System.getProperty("user.home") + "/.altastata/test-files";

	private static final int MAX_RETRIES = 3;
	private static final long MULTIPART_THRESHOLD_BYTES = 10L * 1024 * 1024;

	private static final String DIRECT_S3_BUCKET = "altastata-performance-test";
	private static final String DIRECT_S3_PREFIX = "performance-test/combined/";
	private static final String ALTASTATA_CLOUD_BUCKET = "performance-aws";
	private static final String ALTASTATA_ACCOUNT = "amazon.rsa.bob123";
	/** Unique per JVM so re-runs do not hit warm object keys / client caches. */
	private static final String RUN_ID = Long.toString(System.currentTimeMillis());

	private static AmazonS3 s3Client;
	private static TransferManager transferManager;
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

		System.out.println("=== COMBINED AWS S3 vs ALTASTATA PERFORMANCE TEST ===");
		System.out.println("Test files directory: " + file_path);
		System.out.println("Direct S3 bucket: " + DIRECT_S3_BUCKET);
		System.out.println("AltaStata Account: " + ALTASTATA_ACCOUNT);
		System.out.println("AltaStata prefix: " + ALTASTATA_CLOUD_BUCKET);
		System.out.println("Object name run-id (anti-cache): " + RUN_ID);
		if (downloadOnly) {
			System.out.println("Mode: download-only resume for " + args[1]);
		} else if (fileOnly) {
			System.out.println("Mode: file-only (upload+download) for " + args[1]);
		} else if (altastataOnly) {
			System.out.println("Mode: altastata-only (upload+download AltaStata; AWS download if objects exist) for " + args[1]);
		}
		profile.printBanner();
		System.out.println("==========================================================\n");

		initializeAwsS3();
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
				LOGGER.warn("Download-only AWS vs AltaStata test completed for {}", fileName);
			} else if (altastataOnly) {
				runAltaStataOnlyTest(fileName, runs);
				LOGGER.warn("AltaStata-only AWS vs AltaStata test completed for {}", fileName);
			} else {
				runCombinedTest(fileName, runs, "File-only");
				LOGGER.warn("File-only AWS vs AltaStata test completed for {}", fileName);
			}
			shutdownTransferManager();
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
		shutdownTransferManager();
		LOGGER.warn("Combined AWS vs AltaStata performance test completed");
		// Account event loop threads are non-daemon; without this, Gradle runExample hangs.
		System.exit(0);
	}

	private static void initializeAwsS3() {
		String region = resolveAwsRegion();
		s3Client = AmazonS3ClientBuilder.standard()
				.withCredentials(new DefaultAWSCredentialsProviderChain())
				.withRegion(region)
				.build();
		transferManager = TransferManagerBuilder.standard()
				.withS3Client(s3Client)
				.withMultipartUploadThreshold(MULTIPART_THRESHOLD_BYTES)
				.build();
		System.out.println("Successfully initialized AWS S3 client (region=" + region
				+ ", bucket=" + DIRECT_S3_BUCKET + ")");
	}

	/**
	 * Prefer {@code AWS_REGION} / {@code AWS_DEFAULT_REGION}, then
	 * {@link DefaultAwsRegionProviderChain}, else {@code us-east-1}.
	 */
	private static String resolveAwsRegion() {
		String env = System.getenv("AWS_REGION");
		if (env != null && !env.isEmpty()) {
			System.out.println("Using AWS_REGION from environment: " + env);
			return env;
		}
		env = System.getenv("AWS_DEFAULT_REGION");
		if (env != null && !env.isEmpty()) {
			System.out.println("Using AWS_DEFAULT_REGION from environment: " + env);
			return env;
		}
		try {
			String chainRegion = new DefaultAwsRegionProviderChain().getRegion();
			if (chainRegion != null && !chainRegion.isEmpty()) {
				System.out.println("Using region from DefaultAwsRegionProviderChain: " + chainRegion);
				return chainRegion;
			}
		} catch (Exception e) {
			System.out.println("DefaultAwsRegionProviderChain unavailable: " + e.getMessage());
		}
		System.out.println("Using default AWS region: us-east-1");
		return "us-east-1";
	}

	private static void initializeAltaStata() {
		try {
			altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
					Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + ALTASTATA_ACCOUNT);
			ensureAltaStataPassword();
			System.out.println("Successfully initialized AltaStata FileSystem (" + ALTASTATA_ACCOUNT + ")");
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize AltaStata AWS account", e);
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

		PerformanceResult awsUploadResult = uploadFileDirectAws(fileName, filePath, runs);
		PerformanceResult altaStataUploadResult = uploadFileAltaStata(fileName, runs);
		boolean report = !"Warm-up".equals(testCategory);
		if (report) {
			printComparison("UPLOAD", fileName, awsUploadResult, altaStataUploadResult);
		} else {
			System.out.println("  (warm-up — comparison omitted; not scored)");
		}

		PerformanceResult awsDownloadResult = downloadFileDirectAws(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		if (report) {
			printComparison("DOWNLOAD", fileName, awsDownloadResult, altaStataDownloadResult);
		}
	}

	/** Re-run download only; expects upload objects from a prior combined run. */
	private static void runDownloadOnlyTest(String fileName, int runs) {
		System.out.println("\n=== Download-only (resume): " + fileName + " (" + runs + " runs) ===");

		Path filePath = testFiles.get(fileName);
		if (filePath == null) {
			System.err.println("Test file not found: " + fileName);
			return;
		}

		cleanupLocalDownloadDir();

		PerformanceResult awsDownloadResult = downloadFileDirectAws(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		printComparison("DOWNLOAD", fileName, awsDownloadResult, altaStataDownloadResult);
	}

	/**
	 * AltaStata upload+download only; AWS download uses existing direct objects (skip AWS upload).
	 * Use when AWS upload already finished and password session expired during that idle wait.
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

		PerformanceResult awsDownloadResult = downloadFileDirectAws(fileName, filePath, runs);
		PerformanceResult altaStataDownloadResult = downloadFileAltaStata(fileName, runs);
		printComparison("DOWNLOAD", fileName, awsDownloadResult, altaStataDownloadResult);
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

	private static PerformanceResult uploadFileDirectAws(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();

		System.out.format("Direct AWS Upload: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectKey = directObjectKey(fileName, i);
					File localFile = filePath.toFile();
					if (fileSize >= MULTIPART_THRESHOLD_BYTES) {
						Upload upload = transferManager.upload(DIRECT_S3_BUCKET, objectKey, localFile);
						upload.waitForCompletion();
					} else {
						s3Client.putObject(DIRECT_S3_BUCKET, objectKey, localFile);
					}
					if (!s3Client.doesObjectExist(DIRECT_S3_BUCKET, objectKey)) {
						throw new IOException("Upload reported success but object missing: " + objectKey);
					}

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  AWS Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  AWS Run %d failed (attempt %d/%d): %s\n",
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

	private static PerformanceResult downloadFileDirectAws(String fileName, Path filePath, int runs) {
		List<Long> durations = new ArrayList<>();
		List<Double> throughputs = new ArrayList<>();
		long fileSize = filePath.toFile().length();

		System.out.format("Direct AWS Download: %s (%d runs)...\n", fileName, runs);

		for (int i = 0; i < runs; i++) {
			int retries = 0;
			boolean success = false;
			while (retries < MAX_RETRIES && !success) {
				try {
					long startTime = System.nanoTime();
					long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

					String objectKey = directObjectKey(fileName, i);
					Path tempFile = Files.createTempFile("aws-download-test-", ".tmp");
					try {
						s3Client.getObject(new GetObjectRequest(DIRECT_S3_BUCKET, objectKey), tempFile.toFile());
					} finally {
						Files.deleteIfExists(tempFile);
					}

					long endTime = System.nanoTime();
					long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
					double throughputMBps = (fileSize / (1024.0 * 1024.0)) / (durationInMillis / 1000.0);

					durations.add(durationInMillis);
					throughputs.add(throughputMBps);
					System.out.format("  AWS Run %d: %d ms, %.2f MB/s, Memory: %d KB\n",
							i + 1, durationInMillis, throughputMBps,
							(endMemory - startMemory) / 1024);
					success = true;
					PerformanceMetrics.coolDown();
				} catch (Exception e) {
					retries++;
					System.err.format("  AWS Run %d failed (attempt %d/%d): %s\n",
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
			PerformanceResult awsResult, PerformanceResult altaStataResult) {
		PerformanceMetrics.printSideBySide(
				operation, fileName, "DIRECT AWS", "AWS",
				awsResult.durations, awsResult.throughputs,
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

	private static String directObjectKey(String fileName, int runIndex) {
		return DIRECT_S3_PREFIX + fileName + "-direct-" + RUN_ID + "-" + runIndex;
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
			ensureAltaStataPassword();
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

	private static void cleanupDirectS3Prefix() {
		if (s3Client == null) {
			return;
		}
		ListObjectsV2Request listReq = new ListObjectsV2Request()
				.withBucketName(DIRECT_S3_BUCKET)
				.withPrefix(DIRECT_S3_PREFIX);
		ListObjectsV2Result result;
		do {
			result = s3Client.listObjectsV2(listReq);
			List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
			for (S3ObjectSummary summary : result.getObjectSummaries()) {
				keys.add(new DeleteObjectsRequest.KeyVersion(summary.getKey()));
			}
			if (!keys.isEmpty()) {
				s3Client.deleteObjects(new DeleteObjectsRequest(DIRECT_S3_BUCKET).withKeys(keys));
			}
			listReq.setContinuationToken(result.getNextContinuationToken());
		} while (result.isTruncated());
	}

	private static void cleanupCloudStorage() {
		System.out.println("\nCleaning up cloud storage...");
		try {
			cleanupDirectS3Prefix();
			cleanupAltaStataPerformanceFiles();
			cleanupLocalDownloadDir();
			System.out.println("Cleanup completed successfully");
		} catch (Exception e) {
			System.err.println("Error during cleanup: " + e.getMessage());
		}
	}

	private static void shutdownTransferManager() {
		if (transferManager != null) {
			try {
				transferManager.shutdownNow(false);
			} catch (Exception e) {
				System.err.println("TransferManager shutdown warning: " + e.getMessage());
			}
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
