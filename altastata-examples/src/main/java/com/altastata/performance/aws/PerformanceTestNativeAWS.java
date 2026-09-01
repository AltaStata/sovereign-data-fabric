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

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance benchmarking client using native AWS SDK S3 client directly (without AltaStata encryption layers)
 * to measure baseline upload and download speeds.
 */
public class PerformanceTestNativeAWS {

	static String bucket_name = "sample-test-bucket";
	static String file_path = System.getProperty("user.home") + "/samplefiles/";

	/**
	 * Main execution entry point for native AWS S3 baseline performance benchmarks.
	 *
	 * @param args command-line arguments (unused)
	 */
	public static void main(String[] args) {

		BasicAWSCredentials awsCreds = new BasicAWSCredentials("AKIAIOSFODNN7EXAMPLE",
				"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

		AmazonS3 s3 = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(awsCreds))
				.build();

		// warm it up
		s3.putObject(bucket_name, "bin/1MB.bin", new File(file_path + "bin/1MB.bin"));

		uploadFile(s3, 5, "text/enwik8.txt", true);
		//downloadFile(s3, 5, "bin/50MB.bin");
		
		// check the bucket size for the files uploaded by AltaStata
		//bucketSize(s3);
	}

	/**
	 * Benchmarks native S3 file upload speed, supporting optional multipart TransferManager uploads.
	 *
	 * @param s3 active AWS S3 client instance
	 * @param times number of benchmark iterations
	 * @param keyName target cloud destination object key
	 * @param isMultipart true to use TransferManager multipart uploads; false for standard putObject
	 */
	private static void uploadFile(AmazonS3 s3, int times, String keyName, boolean isMultipart) {
		int totalDuration = 0;

		System.out.format("Uploading %s to S3 bucket %s...\n", file_path + keyName, bucket_name);

		for (int i = 0; i < times; i++) {
			try {

				long startTime = System.nanoTime();

				if (isMultipart) {
					TransferManager tm = TransferManagerBuilder.standard()
			                .withS3Client(s3)
			                .build();
	
			        // TransferManager processes all transfers asynchronously,
			        // so this call returns immediately.
			        Upload upload = tm.upload(bucket_name, keyName + i, new File(file_path + keyName));
			        System.out.println("Object upload started");
	
			        // Optionally, wait for the upload to finish before continuing.
			        upload.waitForCompletion();
				}
				else {
					s3.putObject(bucket_name, keyName + i, new File(file_path + keyName));					
				}

				long endTime = System.nanoTime();

				long durationInNano = (endTime - startTime); // Total execution time in nano seconds

				// Same duration in millis

				long durationInMillis = TimeUnit.NANOSECONDS.toMillis(durationInNano); // Total execution time in nano
																						// seconds

				totalDuration += durationInMillis;

				// System.out.println(durationUploadInNano);
				System.out.println(file_path + keyName + i + " upload : " + durationInMillis + "Ms");

			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				System.exit(1);
			} catch (AmazonClientException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		System.out.println(file_path + keyName + " upload Average: " + (totalDuration / times) + "Ms");
	}

	/**
	 * Benchmarks native S3 file download speed, writing downloaded files locally to calculate averages.
	 *
	 * @param s3 active AWS S3 client instance
	 * @param times number of benchmark iterations
	 * @param keyName target source cloud object key name
	 */
	private static void downloadFile(AmazonS3 s3, int times, String keyName) {
		int totalDuration = 0;
		
		System.out.format("Downloading %s from S3 bucket %s...\n", System.getProperty("user.home") + "/Downloads/" + keyName,
				bucket_name);

		for (int i = 0; i < times; i++) {
			long startTime = System.nanoTime();
			try {
				S3Object o = s3.getObject(bucket_name, keyName + i);
				S3ObjectInputStream s3is = o.getObjectContent();
				FileOutputStream fos = new FileOutputStream(new File(System.getProperty("user.home") + "/Downloads/" + 
						keyName.substring(keyName.indexOf('/')) + i));
				byte[] read_buf = new byte[1024];
				int read_len = 0;
				while ((read_len = s3is.read(read_buf)) > 0) {
					fos.write(read_buf, 0, read_len);
				}
				s3is.close();
				fos.close();
			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				e.printStackTrace();
				System.exit(1);
			} catch (FileNotFoundException e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
				System.exit(1);
			} catch (IOException e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
				System.exit(1);
			}

			long endTime = System.nanoTime();

			long durationInNano = (endTime - startTime); // Total execution time in nano seconds

			// Same duration in millis

			long durationInMillis = TimeUnit.NANOSECONDS.toMillis(durationInNano); // Total execution time in nano
																						// seconds
			totalDuration += durationInMillis;

			// System.out.println(durationUploadInNano);
			System.out.println(keyName + i + " download : " + durationInMillis + "Ms");
		}

		System.out.println(keyName + " download Average: " + (totalDuration / times) + "Ms");
	}
	
	/**
	 * Computes and prints the total cumulative byte size of all objects stored inside the designated bucket.
	 *
	 * @param s3 active AWS S3 client instance
	 */
	private static void bucketSize(AmazonS3 s3) {
		final AtomicLong totalSize = new AtomicLong();

		S3Objects.inBucket(s3, "altastata-myorg321-chunks").forEach((S3ObjectSummary objectSummary) -> {
			totalSize.addAndGet(objectSummary.getSize());
		});

		System.out.println(totalSize.get());
		
	}
}
