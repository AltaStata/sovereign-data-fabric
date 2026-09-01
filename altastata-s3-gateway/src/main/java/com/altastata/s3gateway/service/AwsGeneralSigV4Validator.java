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

import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS General Signature Version 4 Validator
 * 
 * This class validates AWS Signature Version 4 signatures for general S3 operations
 * (not presigned URLs). It recreates signatures from request data and compares
 * them with client-provided signatures to determine validity.
 * 
 * Key responsibilities:
 * - Recreate AWS SigV4 signatures from request data (method, URI, headers, body)
 * - Compare client signatures with server-recreated signatures
 * - Handle dynamic signed headers based on client requests
 * - Support all AWS SigV4 operations (GET, PUT, POST, DELETE, HEAD, etc.)
 * 
 * This is the "general validator" - it handles regular AWS SigV4 requests
 * with Authorization headers, not presigned URLs with query parameters.
 * 
 * Uses: AwsSigV4Calculator for signature calculation operations
 */
public class AwsGeneralSigV4Validator {
    
    private static final Logger logger = LoggerFactory.getLogger(AwsGeneralSigV4Validator.class);

    private final String serviceName;

    private final String accessKey;
    private final String secretKey;
    private final String region;

    /**
     * Constructs AwsGeneralSigV4Validator with specified parameters.
     *
     * @param serviceName target AWS service name
     * @param accessKey S3 access key
     * @param secretKey S3 secret key
     * @param region AWS region name
     */
    public AwsGeneralSigV4Validator(String serviceName, String accessKey, String secretKey, String region) {
        this.serviceName = serviceName;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;

        logger.info("AwsGeneralSigV4Validator initialized for service: {}, region: {}", serviceName, region);
    }
    
    // Default constructor with test credentials
    /**
      * Constructs a new AwsGeneralSigV4Validator.
      * @param accessKey access key
      * @param secretKey secret key
      * @param region aws region
      */
    public AwsGeneralSigV4Validator(String accessKey, String secretKey, String region) {
        this("s3", accessKey, secretKey, region);
    }

    /**
     * Gets the configured Access Key.
     *
     * @return S3 access key
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * Gets the configured Secret Key.
     *
     * @return S3 secret key
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Recreate signature for a request and return the signature headers
     * Using pure Java implementation to match boto3 exactly
     */
    public synchronized Map<String, List<String>> recreateSignature(
            String method,
            String uri,
            Map<String, List<String>> headers,
            byte[] body) {
        
        try {
            logger.debug("Recreating signature for method: {}, uri: {}", method, uri);
            
            // Parse URI to extract path and query parameters
            // Handle URIs that may not have a scheme/host (relative URIs)
            URI parsedUri;
            if (uri.startsWith("http")) {
                // Full URI with scheme - use as is
                parsedUri = URI.create(uri);
            } else {
                // Relative URI - we need to extract the path and query properly
                // Split the URI into path and query components
                String[] uriParts = uri.split("\\?", 2);
                String path = uriParts[0];
                String query = uriParts.length > 1 ? uriParts[1] : "";
                
                // Use proper URL encoding for the path component
                // This handles special characters like parentheses, spaces, etc.
                // Use URI.create() with proper encoding - it handles path separators correctly
                String encodedPath = java.net.URLEncoder.encode(path, "UTF-8");
                
                // Reconstruct the URI with proper encoding
                String encodedUri = encodedPath;
                if (!query.isEmpty()) {
                    encodedUri += "?" + query;
                }
                
                logger.debug("Encoded relative URI: {} -> {}", uri, encodedUri);
                parsedUri = URI.create(encodedUri);
            }
            String path = parsedUri.getPath();
            String query = parsedUri.getQuery();
            
            // Manually construct canonical query string in alphabetical order
            String canonicalQueryString = "";
            if (query != null && !query.isEmpty()) {
                Map<String, String> queryParams = new TreeMap<>();
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    String key = keyValue[0];
                    String value = keyValue.length > 1 ? keyValue[1] : "";
                    queryParams.put(key, value);
                }
                
                StringBuilder canonicalQuery = new StringBuilder();
                for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                    if (canonicalQuery.length() > 0) {
                        canonicalQuery.append("&");
                    }
                    canonicalQuery.append(entry.getKey()).append("=").append(entry.getValue());
                }
                canonicalQueryString = canonicalQuery.toString();
            }
            
            // Get the date from headers - handle missing header gracefully
            String amzDate = null;
            
            logger.info("=== HEADER EXTRACTION DEBUG ===");
            logger.info("All available headers: {}", headers.keySet());
            
            // Normalize headers to lowercase for consistent lookup
            Map<String, List<String>> normalizedHeaders = new HashMap<>();
            headers.forEach((key, value) -> {
                normalizedHeaders.put(key.toLowerCase(), value);
            });
            logger.info("Normalized headers (lowercase keys): {}", normalizedHeaders.keySet());
            
            // Look for X-Amz-Date header using lowercase key
            List<String> amzDateHeaders = normalizedHeaders.get("x-amz-date");
            logger.info("Looking for 'x-amz-date': {}", amzDateHeaders);
            
            if (amzDateHeaders != null && !amzDateHeaders.isEmpty()) {
                amzDate = amzDateHeaders.get(0);
                logger.info("Found X-Amz-Date: {}", amzDate);
            } else {
                logger.warn("X-Amz-Date header not found");
                
                // If X-Amz-Date is missing, try to extract from Authorization header
                List<String> authHeaders = normalizedHeaders.get("authorization");
                logger.info("Looking for 'authorization': {}", authHeaders);
                
                if (authHeaders != null && !authHeaders.isEmpty()) {
                    String authHeader = authHeaders.get(0);
                    logger.info("Found Authorization header: {}", authHeader);
                    // Extract date from Authorization header format: AWS4-HMAC-SHA256 Credential=testkey/20240101/us-east-1/s3/aws4_request
                    if (authHeader.contains("Credential=")) {
                        String credentialPart = authHeader.split("Credential=")[1].split(",")[0];
                        String[] parts = credentialPart.split("/");
                        if (parts.length >= 2) {
                            amzDate = parts[1] + "T000000Z"; // Use the date from credential
                            logger.info("Extracted date from Authorization header: {}", amzDate);
                        }
                    }
                } else {
                    logger.warn("Authorization header not found");
                }
            }
            
            logger.info("Final amzDate value: {}", amzDate);
            logger.info("=== END HEADER EXTRACTION DEBUG ===");
            
            if (amzDate == null) {
                logger.warn("No X-Amz-Date header found and could not extract from Authorization header");
                throw new RuntimeException("Missing required X-Amz-Date header");
            }
            
            // Prevent Replay Attacks: AWS requires the request time to be within 15 minutes of the current server time
            try {
                Instant requestTime = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).parse(amzDate));
                Instant now = Instant.now();
                long minutesDifference = Math.abs(ChronoUnit.MINUTES.between(requestTime, now));
                
                if (minutesDifference > 15) {
                    logger.warn("Request signature is too old or too far in the future. Time difference: {} minutes. Replay attack prevention.", minutesDifference);
                    throw new RuntimeException("Request time is too skewed. Signature validation failed.");
                }
            } catch (java.time.format.DateTimeParseException e) {
                logger.warn("Failed to parse X-Amz-Date for replay attack prevention: {}", amzDate, e);
                throw new RuntimeException("Invalid X-Amz-Date format.", e);
            }
            
            String dateStamp = amzDate.substring(0, 8); // YYYYMMDD

            // Use the region from the client's Credential scope (e.g. us-west-2), not the
            // gateway default, so cross-region clients (Snowflake getBucketLocation) verify.
            String signingRegion = region;
            List<String> authHeadersForRegion = normalizedHeaders.get("authorization");
            if (authHeadersForRegion != null && !authHeadersForRegion.isEmpty()) {
                String clientRegion = AwsSigV4Calculator.extractCredentialRegion(authHeadersForRegion.get(0));
                if (clientRegion != null && !clientRegion.isEmpty()) {
                    signingRegion = clientRegion;
                    logger.debug("Using client credential region for signature: {}", signingRegion);
                }
            }
            
            // Manually construct canonical request exactly like boto3
            String canonicalRequest = buildCanonicalRequest(method, path, canonicalQueryString, normalizedHeaders, body);
            String stringToSign = AwsSigV4Calculator.buildStringToSign(amzDate, dateStamp, signingRegion, serviceName, canonicalRequest);
            
            logger.info("=== MANUAL CANONICAL REQUEST ===");
            logger.info(canonicalRequest);
            logger.info("=== MANUAL STRING TO SIGN ===");
            logger.info(stringToSign);
            
            // Sign using pure Java HMAC implementation
            String signature = AwsSigV4Calculator.calculateSignature(stringToSign, dateStamp, signingRegion, serviceName, secretKey);
            
            // Build authorization header
            String authorizationHeader = AwsSigV4Calculator.buildAuthorizationHeader(accessKey, amzDate, dateStamp, signingRegion, serviceName, signature);
            
            logger.info("=== PURE JAVA SIGNATURE ===");
            logger.info("Signature: {}", signature);
            logger.info("Authorization: {}", authorizationHeader);
            
            // Return headers with our signature
            Map<String, List<String>> signatureHeaders = new HashMap<>();
            signatureHeaders.put("Authorization", Arrays.asList(authorizationHeader));
            
            // Use the actual host header from the request - no hardcoded values
            List<String> hostHeaders = normalizedHeaders.get("host");
            if (hostHeaders == null || hostHeaders.isEmpty()) {
                throw new RuntimeException("Host header is required for signature validation");
            }
            String actualHost = hostHeaders.get(0);
            logger.info("SIGNATURE_DEBUG: Using host header for signature validation: {}", actualHost);
            signatureHeaders.put("host", Arrays.asList(actualHost));
            
            // Handle X-Amz-Content-SHA256 header using normalized headers
            List<String> contentSha256Headers = normalizedHeaders.get("x-amz-content-sha256");
            if (contentSha256Headers != null && !contentSha256Headers.isEmpty()) {
                signatureHeaders.put("X-Amz-Content-SHA256", contentSha256Headers);
            }
            
            // Handle X-Amz-Date header using normalized headers
            List<String> amzDateHeadersForSignature = normalizedHeaders.get("x-amz-date");
            if (amzDateHeadersForSignature != null && !amzDateHeadersForSignature.isEmpty()) {
                signatureHeaders.put("X-Amz-Date", amzDateHeadersForSignature);
            }
            
            logger.info("=== SERVER SIGNED REQUEST DEBUG ===");
            logger.info("Signed Headers: {}", signatureHeaders);
            logger.info("=== END SERVER SIGNATURE RECREATION DEBUG ===");
            
            return signatureHeaders;
            
        } catch (Exception e) {
            logger.error("Error recreating signature for method: {}, uri: {}", method, uri, e);
            throw new RuntimeException("Failed to recreate signature", e);
        }
    }
    
    /**
     * Build canonical query string with proper URL encoding and sorting
     */
    private String buildCanonicalQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return "";
        }
        
        try {
            // Parse query parameters
            Map<String, String> params = new TreeMap<>();
            String[] pairs = queryString.split("&");
            
            for (String pair : pairs) {
                int equalIndex = pair.indexOf('=');
                if (equalIndex > 0) {
                    String key = pair.substring(0, equalIndex);
                    String value = pair.substring(equalIndex + 1);
                    // URL encode both key and value
                    params.put(URLEncoder.encode(key, "UTF-8"), URLEncoder.encode(value, "UTF-8"));
                } else {
                    // Parameter without value
                    params.put(URLEncoder.encode(pair, "UTF-8"), "");
                }
            }
            
            // Build canonical query string
            StringBuilder canonicalQuery = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (canonicalQuery.length() > 0) {
                    canonicalQuery.append("&");
                }
                if (entry.getValue().isEmpty()) {
                    // S3 flag params (?tagging, etc.): AWS SigV4 requires "name=" even when
                    // the wire query omits "=value". Non-flag params keep the branch below.
                    canonicalQuery.append(entry.getKey()).append("=");
                } else {
                    canonicalQuery.append(entry.getKey());
                    canonicalQuery.append("=").append(entry.getValue());
                }
            }
            
            return canonicalQuery.toString();
            
        } catch (Exception e) {
            logger.error("Error building canonical query string: {}", e.getMessage(), e);
            return queryString; // Fallback to original
        }
    }
    
    /**
     * Build canonical request exactly like boto3 with dynamic header signing
     */
    private String buildCanonicalRequest(String method, String path, String queryString, 
                                       Map<String, List<String>> headers, byte[] body) {
        StringBuilder canonicalRequest = new StringBuilder();
        
        // HTTP Method
        canonicalRequest.append(method).append("\n");
        
        // Canonical URI
        canonicalRequest.append(path).append("\n");
        
        // Canonical Query String - properly encode and sort parameters
        String canonicalQueryString = buildCanonicalQueryString(queryString);
        canonicalRequest.append(canonicalQueryString).append("\n");
        
        // Determine which headers to sign based on the actual request
        Set<String> signedHeaders = determineSignedHeaders(headers);
        
        // Canonical Headers - using normalized headers (already lowercase)
        Map<String, String> canonicalHeaders = new TreeMap<>();
        headers.forEach((name, values) -> {
            // name is already lowercase from normalized headers
            if (signedHeaders.contains(name)) {
                String value = values.get(0);
                if (name.equals("host")) {
                    logger.info("SIGNATURE_DEBUG: Received Host header: {}", value);
                    // Use the actual host header value for signature validation
                    logger.info("SIGNATURE_DEBUG: Using host header value '{}' for signature", value);
                }
                canonicalHeaders.put(name, value);
            }
        });
        
        // Check for any signed headers that might be missing
        for (String signedHeader : signedHeaders) {
            if (!canonicalHeaders.containsKey(signedHeader)) {
                logger.warn("Signed header '{}' not found in normalized headers: {}", signedHeader, headers.keySet());
            }
        }
        
        for (Map.Entry<String, String> entry : canonicalHeaders.entrySet()) {
            canonicalRequest.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
        }
        canonicalRequest.append("\n");
        
        // Signed Headers (dynamic)
        StringBuilder signedHeadersStr = new StringBuilder();
        for (String header : signedHeaders) {
            if (signedHeadersStr.length() > 0) {
                signedHeadersStr.append(";");
            }
            signedHeadersStr.append(header);
        }
        canonicalRequest.append(signedHeadersStr.toString()).append("\n");
        
        // Payload Hash - Use content hash from headers if available, otherwise use body
        String payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // default empty body hash
        
        // For PUT/POST operations, use the x-amz-content-sha256 header if available (using normalized headers)
        List<String> contentSha256Headers = headers.get("x-amz-content-sha256");
        
        if (contentSha256Headers != null && !contentSha256Headers.isEmpty()) {
            String contentSha256 = contentSha256Headers.get(0);
            // Only use the header value if it's not the "UNSIGNED-PAYLOAD" marker
            if (!"UNSIGNED-PAYLOAD".equals(contentSha256)) {
                payloadHash = contentSha256;
                logger.info("=== NEW VERSION: Using content hash from x-amz-content-sha256 header: {}", contentSha256);
            } else {
                logger.info("=== NEW VERSION: Found UNSIGNED-PAYLOAD marker, using default empty body hash");
            }
        } else if (body != null && body.length > 0) {
            // Fallback to calculating hash from body if header not available
            payloadHash = sha256Hex(body);
            logger.info("=== NEW VERSION: No x-amz-content-sha256 header found, calculating hash from body: {}", payloadHash);
        } else {
            logger.info("=== NEW VERSION: Using default empty body hash (no content hash header, no body)");
        }
        
        canonicalRequest.append(payloadHash);
        
        return canonicalRequest.toString();
    }
    
    /**
     * Determine which headers to sign based on the actual request headers
     * This matches boto3's behavior for different operations
     */
    private synchronized Set<String> determineSignedHeaders(Map<String, List<String>> headers) {
        // Extract signed headers from the client's Authorization header
        Set<String> signedHeaders = extractSignedHeadersFromAuthHeader(headers);
        
        if (signedHeaders.isEmpty()) {
            logger.warn("No signed headers found in Authorization header - this may indicate an invalid request");
        }
        
        logger.info("=== DETERMINED SIGNED HEADERS ===");
        logger.info("Headers in request: {}", headers.keySet());
        logger.info("Determined signed headers: {}", signedHeaders);
        logger.info("=== END DETERMINED SIGNED HEADERS ===");
        return signedHeaders;
    }
    
    /**
     * Extract signed headers from the client's Authorization header
     */
    private Set<String> extractSignedHeadersFromAuthHeader(Map<String, List<String>> headers) {
        Set<String> signedHeaders = new TreeSet<>();
        
        logger.info("=== EXTRACTING SIGNED HEADERS DEBUG ===");
        logger.info("Available headers for signed headers extraction: {}", headers.keySet());
        
        // Normalize headers to lowercase for consistent lookup
        Map<String, List<String>> normalizedHeaders = new HashMap<>();
        headers.forEach((key, value) -> {
            normalizedHeaders.put(key.toLowerCase(), value);
        });
        logger.info("Normalized headers (lowercase keys): {}", normalizedHeaders.keySet());
        
        // Look for Authorization header using lowercase key
        List<String> authHeaders = normalizedHeaders.get("authorization");
        logger.info("Looking for 'authorization': {}", authHeaders);
        
        if (authHeaders == null || authHeaders.isEmpty()) {
            logger.warn("No Authorization header found");
            return signedHeaders;
        }
        
        String authHeader = authHeaders.get(0);
        if (!authHeader.startsWith("AWS4-HMAC-SHA256")) {
            return signedHeaders;
        }
        
        // Parse: AWS4-HMAC-SHA256 Credential=..., SignedHeaders=..., Signature=...
        int signedHeadersStart = authHeader.indexOf("SignedHeaders=");
        if (signedHeadersStart == -1) {
            return signedHeaders;
        }
        
        int signedHeadersEnd = authHeader.indexOf(",", signedHeadersStart + 14);
        if (signedHeadersEnd == -1) {
            // If no comma, look for space
            signedHeadersEnd = authHeader.indexOf(" ", signedHeadersStart + 14);
        }
        if (signedHeadersEnd == -1) {
            // If still not found, use the rest of the string
            signedHeadersEnd = authHeader.length();
        }
        
        String signedHeadersPart = authHeader.substring(signedHeadersStart + 14, signedHeadersEnd);
        String[] headerNames = signedHeadersPart.split(";");
        
        for (String headerName : headerNames) {
            signedHeaders.add(headerName.toLowerCase());
        }
        
        logger.info("Extracted signed headers from Authorization header: {}", signedHeaders);
        return signedHeaders;
    }
    
    /**
     * Build string to sign exactly like boto3
     */
    private String buildStringToSign(String amzDate, String dateStamp, String canonicalRequest) {
        return AwsSigV4Calculator.buildStringToSign(amzDate, dateStamp, region, serviceName, canonicalRequest);
    }
    
    /**
     * Calculate AWS SigV4 signature using shared utilities
     */
    private String calculateSignature(String stringToSign, String dateStamp) throws Exception {
        return AwsSigV4Calculator.calculateSignature(stringToSign, dateStamp, region, serviceName, secretKey);
    }
    
    /**
     * Build authorization header using shared utilities
     */
    private String buildAuthorizationHeader(String amzDate, String dateStamp, String signature) {
        return AwsSigV4Calculator.buildAuthorizationHeader(accessKey, amzDate, dateStamp, region, serviceName, signature);
    }
    
    /**
     * Calculate SHA256 hash using shared utilities
     */
    private String sha256Hex(byte[] data) {
        return AwsSigV4Calculator.sha256Hex(data);
    }
    
    /**
     * Convert bytes to hex string using shared utilities
     */
    private String bytesToHex(byte[] bytes) {
        return AwsSigV4Calculator.bytesToHex(bytes);
    }
    
    /**
     * Extract the Authorization header value from recreated signature
     */
    public String getRecreatedAuthorizationHeader(
            String method,
            String uri,
            Map<String, List<String>> headers,
            byte[] body) {
        
        Map<String, List<String>> signatureHeaders = recreateSignature(method, uri, headers, body);
        List<String> authHeaders = signatureHeaders.get("Authorization");
        
        if (authHeaders != null && !authHeaders.isEmpty()) {
            return authHeaders.get(0);
        }
        
        return null;
    }
    
    /**
     * Extract the Authorization header value from recreated signature (String body version)
     * Based on the Medium article approach and matching boto3 exactly
     */
    public String getRecreatedAuthorizationHeader(
            String method,
            String path,
            String queryString,
            Map<String, String> headers,
            String body) {
        
        try {
            logger.debug("Recreating signature for method: {}, path: {}, query: {}", method, path, queryString);
            
            // Convert Map<String, String> to Map<String, List<String>> - following Medium article approach
            // Include all headers that might be signed (we'll determine which ones to sign dynamically)
            Map<String, List<String>> headerList = new java.util.HashMap<>();
            headers.forEach((key, value) -> {
                headerList.put(key, List.of(value));
                logger.debug("Including header for signature calculation: {} = {}", key, value);
            });
            
            // Build full URI - following Medium article approach
            String uri = path;
            if (queryString != null && !queryString.isEmpty()) {
                uri += "?" + queryString;
            }
            
            // AWS SigV4 payload hash is over UTF-8 bytes; platform default breaks non-ASCII bodies on Windows.
            byte[] bodyBytes = body != null ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            
            // Recreate signature using the main method
            Map<String, List<String>> signatureHeaders = recreateSignature(method, uri, headerList, bodyBytes);
            
            // Extract Authorization header - following Medium article approach
            List<String> authHeaders = signatureHeaders.get("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                return authHeaders.get(0);
            }
            
            return null;
            
        } catch (Exception e) {
            logger.error("Error recreating authorization header for method: {}, path: {}", method, path, e);
            throw new RuntimeException("Failed to recreate authorization header", e);
        }
    }
    
    /**
     * Extract the Authorization header value from recreated signature (no body version)
     * Uses x-amz-content-sha256 header for payload hash when available
     */
    public String getRecreatedAuthorizationHeader(
            String method,
            String path,
            String queryString,
            Map<String, String> headers) {
        
        try {
            logger.debug("Recreating signature for method: {}, path: {}, query: {} (no body)", method, path, queryString);
            
            // Convert Map<String, String> to Map<String, List<String>> - following Medium article approach
            // Include all headers that might be signed (we'll determine which ones to sign dynamically)
            Map<String, List<String>> headerList = new java.util.HashMap<>();
            headers.forEach((key, value) -> {
                headerList.put(key, List.of(value));
                logger.debug("Including header for signature calculation: {} = {}", key, value);
            });
            
            // Build full URI - following Medium article approach
            String uri = path;
            if (queryString != null && !queryString.isEmpty()) {
                uri += "?" + queryString;
            }
            
            // Use empty body since we rely on x-amz-content-sha256 header for payload hash
            byte[] bodyBytes = new byte[0];
            
            // Recreate signature using the main method
            Map<String, List<String>> signatureHeaders = recreateSignature(method, uri, headerList, bodyBytes);
            
            // Extract Authorization header - following Medium article approach
            List<String> authHeaders = signatureHeaders.get("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                return authHeaders.get(0);
            }
            
            return null;
            
        } catch (Exception e) {
            logger.error("Error recreating authorization header for method: {}, path: {}", method, path, e);
            throw new RuntimeException("Failed to recreate authorization header", e);
        }
    }
    
    /**
     * Compare two Authorization headers
     * Based on the Medium article approach and matching boto3 exactly
     */
    public synchronized boolean compareSignatures(String clientAuth, String serverAuth) {
        if (clientAuth == null || serverAuth == null) {
            logger.warn("Cannot compare signatures: one or both authorization headers are null");
            return false;
        }
        
        // Extract just the signature part for comparison - following Medium article approach
        String clientSignature = extractSignature(clientAuth);
        String serverSignature = extractSignature(serverAuth);
        
        if (clientSignature == null || serverSignature == null) {
            logger.warn("Cannot compare signatures: failed to extract signature components");
            return false;
        }
        
        boolean match = clientSignature.equals(serverSignature);
        logger.info("=== SIGNATURE COMPARISON ===");
        logger.info("Client Authorization: {}", clientAuth);
        logger.info("Server Authorization: {}", serverAuth);
        logger.info("Client Signature: {}", clientSignature);
        logger.info("Server Signature: {}", serverSignature);
        logger.info("Signatures Match: {}", match);
        logger.info("=== END SIGNATURE COMPARISON ===");
        
        return match;
    }
    
    /**
     * Extract signature from Authorization header using shared utilities
     */
    private String extractSignature(String authHeader) {
        return AwsSigV4Calculator.extractSignature(authHeader);
    }
    
    /**
     * Compare client signature with server-recreated signature
     * Based on the Medium article approach and matching boto3 exactly
     */
    public boolean compareSignatures(
            String clientAuthorization,
            String method,
            String path,
            String queryString,
            Map<String, String> headers,
            String body) {
        
        try {
            String serverAuthorization = getRecreatedAuthorizationHeader(method, path, queryString, headers, body);
            return compareSignatures(clientAuthorization, serverAuthorization);
            
        } catch (Exception e) {
            logger.error("Error comparing signatures", e);
            return false;
        }
    }
    
    /**
     * Log signature components for debugging
     * Based on the Medium article approach
     */
    public void logSignatureComponents(String authorizationHeader) {
        if (authorizationHeader == null) {
            logger.warn("Authorization header is null");
            return;
        }
        
        try {
            String[] parts = authorizationHeader.split(" ");
            if (parts.length != 2 || !parts[0].equals("AWS4-HMAC-SHA256")) {
                logger.warn("Invalid authorization header format");
                return;
            }
            
            String credentialPart = parts[1];
            String[] components = credentialPart.split(",");
            
            logger.info("=== SIGNATURE COMPONENTS ===");
            logger.info("Algorithm: AWS4-HMAC-SHA256");
            
            for (String component : components) {
                component = component.trim();
                if (component.startsWith("Credential=")) {
                    logger.info("Credential: {}", component.substring("Credential=".length()));
                } else if (component.startsWith("SignedHeaders=")) {
                    logger.info("SignedHeaders: {}", component.substring("SignedHeaders=".length()));
                } else if (component.startsWith("Signature=")) {
                    logger.info("Signature: {}", component.substring("Signature=".length()));
                }
            }
            logger.info("=== END SIGNATURE COMPONENTS ===");
            
        } catch (Exception e) {
            logger.error("Error logging signature components", e);
        }
    }
} 
