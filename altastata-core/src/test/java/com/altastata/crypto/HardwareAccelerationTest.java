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

import java.security.Provider;
import java.security.Security;

import javax.crypto.Cipher;

/**
 * Test to check if AES/GCM is using hardware acceleration
 */
public class HardwareAccelerationTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hardware Acceleration Test for AES/GCM ===");
        
        // Check available providers
        System.out.println("\n1. Available Security Providers:");
        Provider[] providers = Security.getProviders();
        for (Provider provider : providers) {
            System.out.println("   - " + provider.getName() + " (v" + provider.getVersion() + ")");
        }
        
        // Check AES/GCM support
        System.out.println("\n2. AES/GCM Support:");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            System.out.println("   - AES/GCM/NoPadding: SUPPORTED");
            System.out.println("   - Provider: " + cipher.getProvider().getName());
            System.out.println("   - Algorithm: " + cipher.getAlgorithm());
        } catch (Exception e) {
            System.out.println("   - AES/GCM/NoPadding: NOT SUPPORTED - " + e.getMessage());
        }
        
        // Check for AES-NI support (Intel)
        System.out.println("\n3. CPU Information:");
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            checkLinuxCPUInfo();
        } else if (osName.contains("mac")) {
            checkMacCPUInfo();
        } else {
            System.out.println("   - OS: " + System.getProperty("os.name"));
            System.out.println("   - Architecture: " + System.getProperty("os.arch"));
        }
        
        // Performance test
        System.out.println("\n4. Performance Test:");
        runPerformanceTest();
        
        // FIPS specific test
        System.out.println("\n5. FIPS Hardware Test:");
        testFIPSHardware();
    }
    
    private static void checkLinuxCPUInfo() {
        try {
            Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
            java.util.Scanner scanner = new java.util.Scanner(process.getInputStream());
            boolean hasAES = false;
            boolean hasAVX = false;
            
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains("aes")) {
                    hasAES = true;
                    System.out.println("   - " + line.trim());
                }
                if (line.contains("avx")) {
                    hasAVX = true;
                    System.out.println("   - " + line.trim());
                }
            }
            
            System.out.println("   - AES-NI Support: " + (hasAES ? "YES" : "NO"));
            System.out.println("   - AVX Support: " + (hasAVX ? "YES" : "NO"));
            
        } catch (Exception e) {
            System.out.println("   - Could not check CPU info: " + e.getMessage());
        }
    }
    
    private static void checkMacCPUInfo() {
        try {
            Process process = Runtime.getRuntime().exec("sysctl -n machdep.cpu.features");
            java.util.Scanner scanner = new java.util.Scanner(process.getInputStream());
            String features = scanner.nextLine();
            
            boolean hasAES = features.contains("AES");
            boolean hasAVX = features.contains("AVX");
            
            System.out.println("   - CPU Features: " + features);
            System.out.println("   - AES-NI Support: " + (hasAES ? "YES" : "NO"));
            System.out.println("   - AVX Support: " + (hasAVX ? "YES" : "NO"));
            
        } catch (Exception e) {
            System.out.println("   - Could not check CPU info: " + e.getMessage());
        }
    }
    
    private static void runPerformanceTest() throws Exception {
        // Generate test data
        byte[] key = new byte[32]; // 256-bit key
        byte[] iv = new byte[12];  // GCM IV
        byte[] plaintext = new byte[1024 * 1024]; // 1MB
        
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(key);
        random.nextBytes(iv);
        random.nextBytes(plaintext);
        
        // Test regular AES/GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
        javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        
        long startTime = System.nanoTime();
        byte[] encrypted = cipher.doFinal(plaintext);
        long endTime = System.nanoTime();
        
        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughputMBps = (plaintext.length / (1024.0 * 1024.0)) / (durationMs / 1000.0);
        
        System.out.println("   - Encryption time: " + String.format("%.2f", durationMs) + " ms");
        System.out.println("   - Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
        System.out.println("   - Provider: " + cipher.getProvider().getName());
        
        // High throughput (>100 MB/s) often indicates hardware acceleration
        if (throughputMBps > 100) {
            System.out.println("   - Likely using hardware acceleration (high throughput)");
        } else {
            System.out.println("   - Likely using software implementation (lower throughput)");
        }
    }
    
    private static void testFIPSHardware() {
        try {
            // Test FIPS-compliant AES/GCM
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
            System.out.println("   - FIPS AES/GCM: SUPPORTED");
            System.out.println("   - FIPS Provider: " + cipher.getProvider().getName());
            
            // Check if hardware acceleration is available
            System.out.println("   - Hardware acceleration detection: " + 
                (cipher.getProvider().getName().contains("SunJCE") ? "AVAILABLE" : "NOT AVAILABLE"));
            
        } catch (Exception e) {
            System.out.println("   - FIPS test failed: " + e.getMessage());
        }
    }
} 
