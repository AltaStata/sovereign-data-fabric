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

package com.altastata.performance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.utils.Account;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Deletes leftover direct-cloud objects and AltaStata performance prefixes
 * from Azure, GCP, and AWS after combined performance runs.
 */
public class PerformanceCleanupClouds {

	private static final String DIRECT_PREFIX = "performance-test/combined/";
	private static final String DIRECT_AZURE_CONTAINER = "altastata-performance-test";
	private static final String DIRECT_GCS_BUCKET = "altastata-performance-test";
	private static final String DIRECT_S3_BUCKET = "altastata-performance-test";

	private static final String ALTASTATA_AZURE_ACCOUNT = "azure.rsa.bob123";
	private static final String ALTASTATA_AZURE_PREFIX = "performance-azure";
	private static final String ALTASTATA_GCP_ACCOUNT = "google.rsa.bob123";
	private static final String ALTASTATA_GCP_PREFIX = "performance-gcs";
	private static final String ALTASTATA_AWS_ACCOUNT = "amazon.rsa.bob123";
	private static final String ALTASTATA_AWS_PREFIX = "performance-aws";

	public static void main(String[] args) {
		System.out.println("=== Performance cloud cleanup (Azure / GCP / AWS) ===\n");
		cleanupAzure();
		cleanupGcp();
		cleanupAws();
		System.out.println("\nCleanup finished.");
		System.exit(0);
	}

	private static void cleanupAzure() {
		System.out.println("--- Azure ---");
		try {
			String connectionString = resolveAzureConnectionString();
			BlobServiceClient service = new BlobServiceClientBuilder()
					.connectionString(connectionString)
					.buildClient();
			BlobContainerClient container = service.getBlobContainerClient(DIRECT_AZURE_CONTAINER);
			if (!container.exists()) {
				System.out.println("Azure container missing, skip direct blobs: " + DIRECT_AZURE_CONTAINER);
			} else {
				int deleted = 0;
				ListBlobsOptions options = new ListBlobsOptions().setPrefix(DIRECT_PREFIX);
				for (BlobItem item : container.listBlobs(options, null)) {
					container.getBlobClient(item.getName()).deleteIfExists();
					deleted++;
				}
				System.out.println("Deleted " + deleted + " Azure blobs under " + DIRECT_PREFIX);
			}
		} catch (Exception e) {
			System.err.println("Azure direct cleanup skipped/failed: " + e.getMessage());
		}

		try {
			AltaStataFileSystem fs = AccountRegistry.getOrCreateFromDir(
					Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + ALTASTATA_AZURE_ACCOUNT);
			fs.setPassword("123");
			fs.delete(ALTASTATA_AZURE_PREFIX, true, null, null);
			System.out.println("AltaStata Azure delete(" + ALTASTATA_AZURE_PREFIX + ") done");
		} catch (Exception e) {
			System.err.println("AltaStata Azure cleanup skipped/failed: " + e.getMessage());
		}
	}

	/**
	 * Prefer a real {@code AZURE_STORAGE_CONNECTION_STRING}; otherwise load
	 * {@code adminStorageConnectionString} from {@code ~/.altastata/admin/azure_admin.properties}.
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

	private static void cleanupGcp() {
		System.out.println("\n--- GCP ---");
		String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		if (credentialsPath == null || credentialsPath.isEmpty() || !Files.exists(Paths.get(credentialsPath))) {
			System.out.println("GOOGLE_APPLICATION_CREDENTIALS unset or missing — skip GCP cleanup");
		} else {
			try {
				System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath);
				ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(
						Files.newInputStream(Paths.get(credentialsPath)));
				Storage gcs = StorageOptions.newBuilder()
						.setCredentials(credentials)
						.setProjectId(credentials.getProjectId())
						.build()
						.getService();
				int deleted = 0;
				for (Blob blob : gcs.list(DIRECT_GCS_BUCKET,
						Storage.BlobListOption.prefix(DIRECT_PREFIX)).iterateAll()) {
					blob.delete();
					deleted++;
				}
				System.out.println("Deleted " + deleted + " GCS objects under " + DIRECT_PREFIX
						+ " (creds: " + credentialsPath + ")");
			} catch (Exception e) {
				System.err.println("GCP direct cleanup skipped/failed: " + e.getMessage());
			}
		}

		try {
			AltaStataFileSystem fs = AccountRegistry.getOrCreateFromDir(
					Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + ALTASTATA_GCP_ACCOUNT);
			fs.setPassword("123");
			fs.delete(ALTASTATA_GCP_PREFIX, true, null, null);
			System.out.println("AltaStata GCP delete(" + ALTASTATA_GCP_PREFIX + ") done");
		} catch (Exception e) {
			System.err.println("AltaStata GCP cleanup skipped/failed: " + e.getMessage());
		}
	}

	private static void cleanupAws() {
		System.out.println("\n--- AWS ---");
		try {
			String region = resolveAwsRegion();
			AmazonS3 s3 = AmazonS3ClientBuilder.standard()
					.withCredentials(new DefaultAWSCredentialsProviderChain())
					.withRegion(region)
					.build();
			int deleted = 0;
			ListObjectsV2Request listReq = new ListObjectsV2Request()
					.withBucketName(DIRECT_S3_BUCKET)
					.withPrefix(DIRECT_PREFIX);
			ListObjectsV2Result result;
			do {
				result = s3.listObjectsV2(listReq);
				List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
				for (S3ObjectSummary summary : result.getObjectSummaries()) {
					keys.add(new DeleteObjectsRequest.KeyVersion(summary.getKey()));
				}
				if (!keys.isEmpty()) {
					s3.deleteObjects(new DeleteObjectsRequest(DIRECT_S3_BUCKET).withKeys(keys));
					deleted += keys.size();
				}
				listReq.setContinuationToken(result.getNextContinuationToken());
			} while (result.isTruncated());
			System.out.println("Deleted " + deleted + " S3 objects under " + DIRECT_PREFIX
					+ " (region=" + region + ")");
		} catch (Exception e) {
			System.err.println("AWS direct cleanup skipped/failed: " + e.getMessage());
		}

		try {
			AltaStataFileSystem fs = AccountRegistry.getOrCreateFromDir(
					Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + ALTASTATA_AWS_ACCOUNT);
			fs.setPassword("123");
			fs.delete(ALTASTATA_AWS_PREFIX, true, null, null);
			System.out.println("AltaStata AWS delete(" + ALTASTATA_AWS_PREFIX + ") done");
		} catch (Exception e) {
			System.err.println("AltaStata AWS cleanup skipped/failed: " + e.getMessage());
		}
	}

	private static String resolveAwsRegion() {
		String env = System.getenv("AWS_REGION");
		if (env != null && !env.isEmpty()) {
			return env;
		}
		env = System.getenv("AWS_DEFAULT_REGION");
		if (env != null && !env.isEmpty()) {
			return env;
		}
		try {
			String chainRegion = new DefaultAwsRegionProviderChain().getRegion();
			if (chainRegion != null && !chainRegion.isEmpty()) {
				return chainRegion;
			}
		} catch (Exception ignored) {
			// fall through
		}
		return "us-east-1";
	}
}
