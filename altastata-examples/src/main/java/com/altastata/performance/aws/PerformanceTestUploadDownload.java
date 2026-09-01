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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.utils.Account;
import com.amazonaws.AmazonServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *

For file size 4.3Gb

Appendedstream 7 m 16 s
Parallel upload 10 m 28 s
AWS Upload 18 m 10 s

 * @author AltaStata
 *
 */
/**
 * Performance benchmarking client comparing AltaStata's secure upload and download streams
 * with direct AWS S3 operations.
 *
 * @author AltaStata
 */
public class PerformanceTestUploadDownload {

	private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTestUploadDownload.class);
	static String file_path = System.getProperty("user.home") + "/samplefiles/text";

	/**
	 * Main execution entry point for AltaStata secure S3 upload and download benchmarking.
	 *
	 * @param args command-line arguments (unused)
	 * @throws IOException if local file I/O operations fail
	 */
	public static void main(String [] args) throws IOException  {

		AltaStataFileSystem altaStataFileSystem = AccountRegistry.getOrCreateFromDir(
				Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "amazon.rsa.bob123");

		altaStataFileSystem.setPassword("123");

		// warm up
		uploadFile(altaStataFileSystem, 1, "enwik11.txt");
		downloadFile(altaStataFileSystem, 1, "enwik11.txt");

		uploadFile(altaStataFileSystem, 5, "enwik50.txt");
		downloadFile(altaStataFileSystem, 5, "enwik50.txt");

		/*

		// warm up
		uploadFile(altaStataFileSystem, 1, "100KB.bin");
		downloadFile(altaStataFileSystem, 1, "100KB.bin");

		uploadFile(altaStataFileSystem, 1, "10GB.bin");
		downloadFile(altaStataFileSystem, 1, "10GB.bin");

		 */

		LOGGER.warn("After store");
	}
	
	/**
	 * Benchmarks AltaStata secure file upload throughput.
	 *
	 * @param altaStataFileSystem active AltaStata filesystem instance
	 * @param times number of benchmark iterations
	 * @param keyName target source file name under local file_path
	 */
	private static void uploadFile(AltaStataFileSystem altaStataFileSystem, int times, String keyName) {
		int totalDuration = 0;

		System.out.format("Uploading %s to AltaStata ...\n", file_path + "/" + keyName);

		for (int i = 0; i < times; i++) {
			try {

				long startTime = System.nanoTime();

				List<CloudFileOperationStatus> stored =
						altaStataFileSystem.store(Arrays.asList(file_path + "/" + keyName), file_path, "performance", true);

				//for (CloudFileOperationStatus cloudFileOperationStatus : stored) {
				//	System.out.println("	stored cloudFileOperationStatus: " + cloudFileOperationStatus);
				//}

				long endTime = System.nanoTime();

				long durationInNano = (endTime - startTime); // Total execution time in nano seconds

				// Same duration in millis

				long durationInMillis = TimeUnit.NANOSECONDS.toMillis(durationInNano); // Total execution time in nano
																						// seconds

				totalDuration += durationInMillis;

				// System.out.println(durationUploadInNano);
				System.out.println(file_path + "/" + keyName + i + " upload : " + durationInMillis + "Ms");

			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				System.exit(1);
			}
		}

		System.out.println(file_path + "/" + keyName + " upload Average: " + (totalDuration / times) + "Ms");
	}

	/**
	 * Benchmarks AltaStata secure file download/retrieval throughput.
	 *
	 * @param altaStataFileSystem active AltaStata filesystem instance
	 * @param times number of benchmark iterations
	 * @param keyName target source file name to download
	 */
	private static void downloadFile(AltaStataFileSystem altaStataFileSystem, int times, String keyName) {
		int totalDuration = 0;

		System.out.format("Downloading %s from AltaStata ...\n", file_path + "2/" + keyName);

		for (int i = 0; i < times; i++) {
			try {

				long startTime = System.nanoTime();

				List<CloudFileOperationStatus> retrieved = 
						altaStataFileSystem.retrieve(file_path + "2", "performance", false, System.currentTimeMillis(), false, true);

				//for (CloudFileOperationStatus cloudFileOperationStatus : retrieved) {
				//	System.out.println("	retrieved cloudFileOperationStatus: " + cloudFileOperationStatus);
				//}
				
				long endTime = System.nanoTime();

				long durationInNano = (endTime - startTime); // Total execution time in nano seconds

				// Same duration in millis
				long durationInMillis = TimeUnit.NANOSECONDS.toMillis(durationInNano); // Total execution time in nano
																						// seconds

				totalDuration += durationInMillis;

				// System.out.println(durationUploadInNano);
				System.out.println(file_path + "/" + keyName + i + " download : " + durationInMillis + "Ms");

			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				System.exit(1);
			}
		}

		System.out.println(file_path + "/" + keyName + " download Average: " + (totalDuration / times) + "Ms");
	}

}
