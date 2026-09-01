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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * AWS Presigned URL Signature Validator
 * 
 * This class validates AWS Signature Version 4 signatures specifically for presigned URLs.
 * It handles the unique requirements of presigned URL validation including query parameter
 * parsing, expiry time validation, and specialized signature reconstruction.
 * 
 * Key responsibilities:
 * - Validate presigned URL signatures using query parameters (X-Amz-Algorithm, X-Amz-Credential, etc.)
 * - Parse and validate presigned URL query parameters
 * - Check expiry times for presigned URLs
 * - Recreate signatures for presigned URL comparison
 * - Handle presigned URL specific signature calculation
 * 
 * This is the "presigned URL specialist" - it handles presigned URLs with query parameters,
 * not regular AWS SigV4 requests with Authorization headers.
 * 
 * Uses: AwsSigV4Calculator for signature calculation operations
 */
public class AwsPresignedValidator {
    private static final Logger logger = LoggerFactory.getLogger(AwsPresignedValidator.class);
    
    private final String accessKey;
    private final String secretKey;
    private final String region;
    
    /**
     * Constructs AwsPresignedValidator with specified parameters.
     *
     * @param accessKey S3 access key
     * @param secretKey S3 secret key
     * @param region AWS region name
     */
    public AwsPresignedValidator(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }
    
    /**
     * Validates a presigned URL signature by recomputing the signature
     * 
     * @param method HTTP method (GET, PUT, etc.)
     * @param uri The request URI
     * @param queryString The query string containing AWS signature parameters
     * @param headers Request headers
     * @return true if signature is valid, false otherwise
     */
    public boolean validatePresignedUrlSignature(String method, String uri, String queryString, 
                                               Map<String, List<String>> headers) {
        try {
            logger.info("=== PRESIGNED URL VALIDATION DEBUG ===");
            logger.info("Method: {}", method);
            logger.info("URI: {}", uri);
            logger.info("Query String: {}", queryString);
            logger.info("Headers: {}", headers);
            
            // Parse the query string to extract AWS signature parameters
            Map<String, String> queryParams = parseQueryString(queryString);
            logger.info("Parsed Query Params: {}", queryParams);
            
            // Extract required AWS signature parameters
            String algorithm = queryParams.get("X-Amz-Algorithm");
            String credential = queryParams.get("X-Amz-Credential");
            String signature = queryParams.get("X-Amz-Signature");
            String signedHeaders = queryParams.get("X-Amz-SignedHeaders");
            String date = queryParams.get("X-Amz-Date");
            String expires = queryParams.get("X-Amz-Expires");
            
            logger.info("Algorithm: {}", algorithm);
            logger.info("Credential: {}", credential);
            logger.info("Signature: {}", signature);
            logger.info("Signed Headers: {}", signedHeaders);
            logger.info("Date: {}", date);
            logger.info("Expires: {}", expires);
            
            if (algorithm == null || credential == null || signature == null || 
                signedHeaders == null || date == null || expires == null) {
                logger.warn("Missing required AWS signature parameters");
                logger.warn("Algorithm: {}, Credential: {}, Signature: {}, SignedHeaders: {}, Date: {}, Expires: {}", 
                          algorithm, credential, signature, signedHeaders, date, expires);
                return false;
            }
            
            // Validate algorithm
            if (!"AWS4-HMAC-SHA256".equals(algorithm)) {
                logger.warn("Unsupported algorithm: {}", algorithm);
                return false;
            }
            
            // Extract access key from credential parameter
            String requestAccessKey = credential.split("/")[0];
            logger.info("Request Access Key: {}", requestAccessKey);
            logger.info("Expected Access Key: {}", accessKey);
            
            // Check if access key matches
            if (!accessKey.equals(requestAccessKey)) {
                logger.warn("Access key mismatch: expected {}, got {}", accessKey, requestAccessKey);
                return false;
            }
            
            // Validate credential format: accessKey/date/region/service/aws4_request
            String[] credentialParts = credential.split("/");
            logger.info("Credential parts: {}", java.util.Arrays.toString(credentialParts));
            
            if (credentialParts.length != 5) {
                logger.warn("Invalid credential format: {}", credential);
                return false;
            }
            
            // Validate region
            String requestRegion = credentialParts[2];
            logger.info("Request Region: {}", requestRegion);
            logger.info("Expected Region: {}", region);
            
            if (!region.equals(requestRegion)) {
                logger.warn("Region mismatch: expected {}, got {}", region, requestRegion);
                return false;
            }
            
            // Validate service name
            String service = credentialParts[3];
            logger.info("Service: {}", service);
            
            if (!"s3".equals(service)) {
                logger.warn("Service mismatch: expected s3, got {}", service);
                return false;
            }
            
            // Validate signature format (should be a hex string)
            if (!signature.matches("[0-9a-f]{64}")) {
                logger.warn("Invalid signature format: {}", signature);
                return false;
            }
            
            // Validate expiry time
            if (!validateExpiryTime(date, expires)) {
                logger.warn("Presigned URL has expired");
                return false;
            }
            
            // Validate signature using AWS SigV4 algorithm to match AWS SDK v1
            String computedSignature = recomputeSignatureManually(method, uri, queryString, headers, date);
            logger.info("Manual computed signature: {}", computedSignature);
            logger.info("Request signature: {}", signature);
            
            if (computedSignature != null && computedSignature.equals(signature)) {
                logger.info("Signature validation passed using manual implementation for access key: {}", accessKey);
                logger.info("=== END PRESIGNED URL VALIDATION DEBUG ===");
                return true;
            } else {
                logger.warn("Manual signature validation failed. Expected: {}, Got: {}", computedSignature, signature);
                logger.info("=== END PRESIGNED URL VALIDATION DEBUG ===");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error validating signature", e);
            return false;
        }
    }
    

    
    /**
     * Recomputes the AWS SigV4 signature manually following the AWS specification
     */
    private String recomputeSignatureManually(String method, String uri, String queryString, 
                                            Map<String, List<String>> headers, String date) {
        try {
            logger.info("Recomputing signature manually for method: {}, uri: {}", method, uri);
            
            // Extract the path from the URI (remove query string if present)
            String path = uri;
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf("?"));
            }
            
            // AWS SDK v1 normalizes the path (removes duplicate slashes, etc.)
            path = normalizePath(path);
            
            // Parse query parameters (excluding the signature)
            Map<String, String> queryParams = parseQueryString(queryString);
            logger.info("Original query params: {}", queryParams);
            queryParams.remove("X-Amz-Signature"); // Remove the signature we're validating
            logger.info("Query params after removing signature: {}", queryParams);
            
            // Create canonical request
            String canonicalRequest = createCanonicalRequest(method, path, queryParams, headers, date);
            logger.info("Canonical Request: {}", canonicalRequest);
            
            // Create string to sign
            String stringToSign = createStringToSign(date, region, canonicalRequest);
            logger.info("String to Sign: {}", stringToSign);
            
            // Calculate signature
            String signature = calculateSignature(date, region, stringToSign);
            logger.info("Calculated signature: {}", signature);
            
            return signature;
            
        } catch (Exception e) {
            logger.error("Error recomputing signature manually", e);
            return null;
        }
    }
    
    /**
     * Creates the canonical request string as per AWS SigV4 specification
     */
    private String createCanonicalRequest(String method, String path, Map<String, String> queryParams, 
                                        Map<String, List<String>> headers, String date) {
        StringBuilder canonicalRequest = new StringBuilder();
        
        // HTTPRequestMethod
        canonicalRequest.append(method).append("\n");
        logger.info("HTTPRequestMethod: {}", method);
        
        // CanonicalURI
        canonicalRequest.append(path).append("\n");
        logger.info("CanonicalURI: {}", path);
        
        // CanonicalQueryString
        String canonicalQueryString = createCanonicalQueryString(queryParams);
        canonicalRequest.append(canonicalQueryString).append("\n");
        logger.info("CanonicalQueryString: {}", canonicalQueryString);
        
        // CanonicalHeaders
        String canonicalHeaders = createCanonicalHeaders(headers);
        canonicalRequest.append(canonicalHeaders).append("\n");
        logger.info("CanonicalHeaders: {}", canonicalHeaders);
        
        // SignedHeaders
        String signedHeaders = createSignedHeaders(headers);
        canonicalRequest.append(signedHeaders).append("\n");
        logger.info("SignedHeaders: {}", signedHeaders);
        
        // HashedPayload (empty for presigned URLs)
        canonicalRequest.append("UNSIGNED-PAYLOAD");
        logger.info("HashedPayload: UNSIGNED-PAYLOAD");
        
        String result = canonicalRequest.toString();
        logger.info("Final canonical request: {}", result);
        return result;
    }
    
    /**
     * Creates the canonical query string using shared utilities
     */
    private String createCanonicalQueryString(Map<String, String> queryParams) {
        String result = AwsSigV4Calculator.createCanonicalQueryString(queryParams);
        logger.info("Canonical query string: {}", result);
        return result;
    }
    
    /**
     * URL encodes a string using shared utilities
     */
    private String urlEncode(String value) {
        return AwsSigV4Calculator.urlEncode(value);
    }
    
    /**
     * Normalizes the path according to AWS SDK v1 conventions
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        
        // Remove duplicate slashes
        path = path.replaceAll("/+", "/");
        
        // Ensure path starts with /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        // Remove trailing slash unless it's the root
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        
        return path;
    }
    
    /**
     * Validates the expiry time of a presigned URL
     */
    private boolean validateExpiryTime(String date, String expires) {
        try {
            logger.info("=== EXPIRY VALIDATION DEBUG ===");
            logger.info("Input date: {}", date);
            logger.info("Input expires: {}", expires);
            
            // Parse the date (format: YYYYMMDDTHHMMSSZ)
            String dateStr = date.substring(0, 8) + " " + date.substring(9, 11) + ":" + 
                           date.substring(11, 13) + ":" + date.substring(13, 15);
            logger.info("Parsed date string: {}", dateStr);
            
            java.time.LocalDateTime requestTime = java.time.LocalDateTime.parse(dateStr, 
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
            
            // Parse the expiry time (in seconds)
            int expirySeconds = Integer.parseInt(expires);
            
            // Calculate the expiry time
            java.time.LocalDateTime expiryTime = requestTime.plusSeconds(expirySeconds);
            
            // Get current time in UTC to match the request time
            java.time.ZonedDateTime currentTimeZoned = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
            java.time.LocalDateTime currentTime = currentTimeZoned.toLocalDateTime();
            
            logger.info("Request time: {}", requestTime);
            logger.info("Expiry time: {}", expiryTime);
            logger.info("Current time: {}", currentTime);
            logger.info("Expiry seconds: {}", expirySeconds);
            
            // Check if the URL has expired
            boolean isValid = currentTime.isBefore(expiryTime);
            logger.info("URL expiry validation: {}", isValid);
            logger.info("=== END EXPIRY VALIDATION DEBUG ===");
            
            return isValid;
            
        } catch (Exception e) {
            logger.error("Error validating expiry time: date={}, expires={}", date, expires, e);
            return false;
        }
    }
    
    /**
     * Creates the canonical headers string
     */
    private String createCanonicalHeaders(Map<String, List<String>> headers) {
        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        
        // Only include headers that are actually signed (in this case, just 'host')
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String headerName = entry.getKey().toLowerCase();
            if ("host".equals(headerName)) {
                String headerValue = entry.getValue().isEmpty() ? "" : entry.getValue().get(0).trim();
                canonicalHeaders.put(headerName, headerValue);
            }
        }
        
        StringBuilder canonicalHeadersString = new StringBuilder();
        for (Map.Entry<String, String> entry : canonicalHeaders.entrySet()) {
            canonicalHeadersString.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
        }
        
        return canonicalHeadersString.toString();
    }
    
    /**
     * Creates the signed headers string
     */
    private String createSignedHeaders(Map<String, List<String>> headers) {
        // Only include headers that are actually signed (in this case, just 'host')
        StringBuilder signedHeaders = new StringBuilder();
        signedHeaders.append("host");
        
        return signedHeaders.toString();
    }
    
    /**
     * Creates the string to sign using shared utilities
     */
    private String createStringToSign(String date, String region, String canonicalRequest) {
        String dateOnly = date.substring(0, 8); // YYYYMMDD
        return AwsSigV4Calculator.buildStringToSign(date, dateOnly, region, "s3", canonicalRequest);
    }
    
    /**
     * Calculates the final signature using shared utilities
     */
    private String calculateSignature(String date, String region, String stringToSign) {
        try {
            String dateOnly = date.substring(0, 8); // YYYYMMDD
            return AwsSigV4Calculator.calculateSignature(stringToSign, dateOnly, region, "s3", secretKey);
        } catch (Exception e) {
            logger.error("Error calculating signature", e);
            return null;
        }
    }
    
    /**
     * Computes SHA256 hash using shared utilities
     */
    private String sha256(String data) {
        return AwsSigV4Calculator.sha256Hex(data);
    }
    
    /**
     * Parses a query string into a map of parameter names to values
     */
    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }
        
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                params.put(keyValue[0], keyValue[1]);
            }
        }
        return params;
    }
} 
