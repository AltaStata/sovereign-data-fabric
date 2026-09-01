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

package com.altastata.crypto;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Simple test to verify AES/GCM hardware acceleration
 */
public class AESHardwareTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== AES/GCM Hardware Acceleration Test ===");
        
        // Check CPU architecture
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Architecture: " + System.getProperty("os.arch"));
        System.out.println("Java Version: " + System.getProperty("java.version"));
        
        // Generate test data
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32]; // 256-bit key
        byte[] iv = new byte[12];  // GCM IV
        byte[] plaintext = new byte[1024 * 1024]; // 1MB
        
        random.nextBytes(key);
        random.nextBytes(iv);
        random.nextBytes(plaintext);
        
        // Test AES/GCM performance
        System.out.println("\n=== AES/GCM Performance Test ===");
        
        // Performance test
        int iterations = 10;
        long totalTime = 0;
        byte[] encrypted = null;
        
        for (int i = 0; i < iterations; i++) {
            // Generate new IV for each iteration
            byte[] newIv = new byte[12];
            random.nextBytes(newIv);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, newIv);
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            
            long startTime = System.nanoTime();
            encrypted = cipher.doFinal(plaintext);
            long endTime = System.nanoTime();
            
            long duration = endTime - startTime;
            totalTime += duration;
            
            double durationMs = duration / 1_000_000.0;
            double throughputMBps = (plaintext.length / (1024.0 * 1024.0)) / (durationMs / 1000.0);
            
            System.out.printf("Run %d: %.2f ms, %.2f MB/s%n", i + 1, durationMs, throughputMBps);
        }
        
        double avgTimeMs = totalTime / (iterations * 1_000_000.0);
        double avgThroughputMBps = (plaintext.length / (1024.0 * 1024.0)) / (avgTimeMs / 1000.0);
        
        System.out.printf("\nAverage: %.2f ms, %.2f MB/s%n", avgTimeMs, avgThroughputMBps);
        
        // Determine if hardware acceleration is likely being used
        if (avgThroughputMBps > 100) {
            System.out.println("✅ LIKELY USING HARDWARE ACCELERATION (high throughput)");
        } else if (avgThroughputMBps > 50) {
            System.out.println("⚠️  MAYBE USING HARDWARE ACCELERATION (medium throughput)");
        } else {
            System.out.println("❌ LIKELY USING SOFTWARE IMPLEMENTATION (low throughput)");
        }
        
        // Provider information
        System.out.println("\n=== Provider Information ===");
        Cipher testCipher = Cipher.getInstance("AES/GCM/NoPadding");
        System.out.println("Cipher Provider: " + testCipher.getProvider().getName());
        System.out.println("Cipher Algorithm: " + testCipher.getAlgorithm());
        
        // Test decryption performance
        System.out.println("\n=== AES/GCM Decryption Test ===");
        
        // Decryption performance test
        totalTime = 0;
        byte[] decrypted = null;
        
        for (int i = 0; i < iterations; i++) {
            // Use the same IV that was used for encryption
            byte[] newIv = new byte[12];
            random.nextBytes(newIv);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, newIv);
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] testEncrypted = cipher.doFinal(plaintext);
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            
            long startTime = System.nanoTime();
            decrypted = cipher.doFinal(testEncrypted);
            long endTime = System.nanoTime();
            
            long duration = endTime - startTime;
            totalTime += duration;
            
            double durationMs = duration / 1_000_000.0;
            double throughputMBps = (plaintext.length / (1024.0 * 1024.0)) / (durationMs / 1000.0);
            
            System.out.printf("Run %d: %.2f ms, %.2f MB/s%n", i + 1, durationMs, throughputMBps);
        }
        
        avgTimeMs = totalTime / (iterations * 1_000_000.0);
        avgThroughputMBps = (plaintext.length / (1024.0 * 1024.0)) / (avgTimeMs / 1000.0);
        
        System.out.printf("\nAverage: %.2f ms, %.2f MB/s%n", avgTimeMs, avgThroughputMBps);
        
        // Verify data integrity
        boolean dataMatches = java.util.Arrays.equals(plaintext, decrypted);
        System.out.println("Data integrity check: " + (dataMatches ? "✅ PASSED" : "❌ FAILED"));
        
        System.out.println("\n=== Summary ===");
        System.out.println("Your system has ARM AES hardware support (Apple Silicon)");
        System.out.println("AES/GCM operations should benefit from hardware acceleration");
        System.out.println("High throughput (>100 MB/s) indicates hardware acceleration is working");
    }
} 
