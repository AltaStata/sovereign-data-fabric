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

package com.altastata.s3gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS SigV4 Signature Calculation Engine
 * 
 * This class provides the core mathematical and cryptographic operations needed
 * to calculate AWS Signature Version 4 signatures. It contains pure calculation
 * utilities that are used by signature validators to recreate and compare signatures.
 * 
 * Key responsibilities:
 * - Calculate AWS SigV4 signatures from raw request data
 * - Generate signing keys using HMAC-SHA256
 * - Build canonical requests and strings to sign
 * - Create authorization headers
 * - Perform cryptographic operations (HMAC, SHA256, URL encoding)
 * 
 * This is the "calculation engine" - it does not validate signatures itself,
 * but provides the mathematical foundation that validators use to recreate
 * signatures for comparison.
 * 
 * Used by: AwsGeneralSigV4Validator, AwsPresignedValidator
 */
public class AwsSigV4Calculator {
    
    private static final Logger logger = LoggerFactory.getLogger(AwsSigV4Calculator.class);
    
    /**
     * Calculate AWS SigV4 signature using pure Java
     */
    public static synchronized String calculateSignature(String stringToSign, String dateStamp, String region, String serviceName, String secretKey) throws Exception {
        // Step 1: Get signing key
        byte[] signingKey = getSigningKey(dateStamp, region, serviceName, secretKey);
        
        // Step 2: Sign the string to sign
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(signingKey, "HmacSHA256");
        mac.init(keySpec);
        byte[] signature = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        
        return bytesToHex(signature);
    }
    
    /**
     * Get AWS SigV4 signing key
     */
    public static synchronized byte[] getSigningKey(String dateStamp, String region, String serviceName, String secretKey) throws Exception {
        String kSecret = "AWS4" + secretKey;
        byte[] kDate = hmacSha256(kSecret.getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, "aws4_request");
    }
    
    /**
     * Calculate HMAC-SHA256
     */
    public static synchronized byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
        mac.init(keySpec);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Calculate SHA256 hash and return as hex string
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Calculate SHA256 hash of string and return as hex string
     */
    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Convert bytes to hex string
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    /**
     * Build string to sign exactly like boto3
     */
    public static String buildStringToSign(String amzDate, String dateStamp, String region, String serviceName, String canonicalRequest) {
        StringBuilder stringToSign = new StringBuilder();
        
        // Algorithm
        stringToSign.append("AWS4-HMAC-SHA256").append("\n");
        
        // Request timestamp
        stringToSign.append(amzDate).append("\n");
        
        // Credential scope
        stringToSign.append(dateStamp).append("/").append(region).append("/")
                   .append(serviceName).append("/aws4_request").append("\n");
        
        // Hashed canonical request
        stringToSign.append(sha256Hex(canonicalRequest));
        
        return stringToSign.toString();
    }
    
    /**
     * Build authorization header
     */
    public static String buildAuthorizationHeader(String accessKey, String amzDate, String dateStamp, String region, String serviceName, String signature) {
        return String.format("AWS4-HMAC-SHA256 Credential=%s/%s/%s/%s/aws4_request, " +
                           "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=%s",
                           accessKey, dateStamp, region, serviceName, signature);
    }
    
    /**
     * Extract AWS region from SigV4 Authorization Credential scope.
     * Format: Credential=ACCESS_KEY/DATESTAMP/REGION/s3/aws4_request
     */
    public static String extractCredentialRegion(String authHeader) {
        if (authHeader == null || !authHeader.contains("Credential=")) {
            return null;
        }
        try {
            String credentialPart = authHeader.substring(authHeader.indexOf("Credential=") + 11);
            credentialPart = credentialPart.split(",")[0].trim();
            String[] parts = credentialPart.split("/");
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                return parts[2];
            }
        } catch (Exception e) {
            logger.warn("Failed to extract region from Authorization header", e);
        }
        return null;
    }

    /**
     * Extract signature from Authorization header
     */
    public static String extractSignature(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        
        try {
            if (!authHeader.startsWith("AWS4-HMAC-SHA256 ")) {
                logger.warn("Invalid authorization header format: {}", authHeader);
                return null;
            }
            
            String credentialPart = authHeader.substring("AWS4-HMAC-SHA256 ".length());
            String[] components = credentialPart.split(",");
            
            for (String component : components) {
                component = component.trim();
                if (component.startsWith("Signature=")) {
                    String signature = component.substring("Signature=".length());
                    logger.debug("Extracted signature: {}", signature);
                    return signature;
                }
            }
            
            logger.warn("No signature found in authorization header: {}", authHeader);
            return null;
            
        } catch (Exception e) {
            logger.error("Error extracting signature from authorization header: {}", authHeader, e);
            return null;
        }
    }
    
    /**
     * URL encodes a string according to AWS SigV4 specification
     */
    public static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            logger.error("Error URL encoding: {}", value, e);
            return value;
        }
    }
    
    /**
     * Creates canonical query string from parameters
     */
    public static String createCanonicalQueryString(Map<String, String> queryParams) {
        TreeMap<String, String> sortedParams = new TreeMap<>(queryParams);
        StringBuilder queryString = new StringBuilder();
        
        boolean first = true;
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (!first) {
                queryString.append("&");
            }
            String encodedKey = urlEncode(entry.getKey());
            String encodedValue = urlEncode(entry.getValue());
            queryString.append(encodedKey).append("=").append(encodedValue);
            first = false;
        }
        
        return queryString.toString();
    }
} 
