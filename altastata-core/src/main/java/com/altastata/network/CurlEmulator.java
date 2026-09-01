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

package com.altastata.network;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Utility to perform POST HTTP requests to the AWS Lambda certificate signing endpoint.
 * Emulates the behavior of a curl-based request with built-in retries and exponential backoff
 * to handle Lambda cold starts and transient network hiccups.
 */
public class CurlEmulator {

	private static final Logger LOGGER = LoggerFactory.getLogger(CurlEmulator.class);
	private static final int MAX_RETRIES = 3;
	private static final int[] RETRY_DELAYS_MS = {10_000, 15_000, 20_000};

	/**
	 * Sends a certificate signing request with multiple retry attempts to handle transient failures.
	 *
	 * @param query target API endpoint URL string
	 * @param jsonRequest JSON payload of the signing order
	 * @return response map of the certificate and other properties
	 * @throws IOException if network or HTTP protocol errors persist after maximum retries
	 * @throws JSONException if parsing JSON payloads fails
	 */
	public static Map<String, Object> getCertificate(String query, JSONObject jsonRequest) throws IOException, JSONException {
		Exception lastException = null;
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				LOGGER.debug("Certificate request attempt {} of {}", attempt, MAX_RETRIES);
				return tryToSign(query, jsonRequest);
			} catch (Exception ex) {
				lastException = ex;
				LOGGER.warn("Certificate request attempt {} failed", attempt);
				if (attempt < MAX_RETRIES) {
					int delay = RETRY_DELAYS_MS[attempt - 1];
					LOGGER.debug("Retrying certificate request in {} seconds", delay / 1000);
					try {
						Thread.sleep(delay);
					} catch (InterruptedException ignore) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}
		throw (lastException instanceof IOException)
				? (IOException) lastException
				: new IOException("Certificate signing failed after " + MAX_RETRIES + " attempts", lastException);
	}

	/**
	 * Executes a single HTTP POST request to the signing service.
	 *
	 * @param query target API endpoint URL string
	 * @param jsonRequest JSON payload of the signing order
	 * @return response map of the certificate and other properties
	 * @throws MalformedURLException if the query URL is invalid
	 * @throws IOException if networking errors occur
	 * @throws ProtocolException if HTTP method configuration fails
	 * @throws UnsupportedEncodingException if UTF-8 is unsupported on the platform
	 */
	private static Map<String, Object> tryToSign(String query, JSONObject jsonRequest)
			throws MalformedURLException, IOException, ProtocolException, UnsupportedEncodingException {
		URL url = new URL(query);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(30000);
		conn.setReadTimeout(90000);
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setDoOutput(true);
		conn.setDoInput(true);
		conn.setRequestMethod("POST");

		OutputStream os = conn.getOutputStream();
		os.write(jsonRequest.toString().getBytes("UTF-8"));
		os.close();

		int responseCode = conn.getResponseCode();
		LOGGER.debug("Certificate signing response code: {}", responseCode);

		if (responseCode == 504 || responseCode == 502 || responseCode == 503) {
			String errorBody = "";
			try (InputStream es = conn.getErrorStream()) {
				if (es != null) {
					errorBody = org.apache.commons.io.IOUtils.toString(new BufferedInputStream(es), "UTF-8");
				}
			}
			conn.disconnect();
			throw new IOException("Certificate signing gateway error " + responseCode);
		}

		try (InputStream is = conn.getInputStream()) {
			InputStream in = new BufferedInputStream(is);
			String result = org.apache.commons.io.IOUtils.toString(in, "UTF-8");
			JSONObject jsonObject = new JSONObject(result);

			in.close();
			conn.disconnect();

			return jsonObject.toMap();
		} catch (IOException e) {
			try (InputStream es = conn.getErrorStream()) {
				if (es != null) {
					InputStream ein = new BufferedInputStream(es);
					org.apache.commons.io.IOUtils.toString(ein, "UTF-8");
				}
			}
			throw e;
		}
	}

	/**
	 * Manual check of a certificate signing endpoint.
	 *
	 * @param args sign-cert-url, organization, userName, kyber PEM file, Dilithium PEM file
	 * @throws Exception if an I/O or network error occurs during connection
	 */
	public static void main(String[] args) throws Exception {
		if (args == null || args.length < 5) {
			System.err.println("Usage: CurlEmulator <sign-cert-url> <organization> <userName> <kyber-pem-file> <dilithium-pem-file>");
			System.exit(1);
		}
		String query = args[0].trim();
		JSONObject jsonRequest = new JSONObject();
		jsonRequest.put("organization", args[1].trim());
		jsonRequest.put("userName", args[2].trim());
		jsonRequest.put("publicKeyKyberPEM", new String(Files.readAllBytes(Paths.get(args[3])), StandardCharsets.UTF_8));
		jsonRequest.put("publicKeyDilithiumPEM", new String(Files.readAllBytes(Paths.get(args[4])), StandardCharsets.UTF_8));

		Map<String, Object> pemMap = getCertificate(query, jsonRequest);
		pemMap.forEach((key, value) -> System.out.println(key + ":" + value));
	}
}
