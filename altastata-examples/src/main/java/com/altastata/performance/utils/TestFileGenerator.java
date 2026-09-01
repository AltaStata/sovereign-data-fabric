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

package com.altastata.performance.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates text (~2–4× gzip) and true-random binary (~1× gzip) fixtures for performance tests.
 */
public class TestFileGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(TestFileGenerator.class);
    
    public static final long[] FILE_SIZES = {
        100 * 1024L,
        1024 * 1024L,
        10 * 1024 * 1024L,
        100 * 1024 * 1024L,
        1024 * 1024 * 1024L,
        5 * 1024 * 1024 * 1024L
    };
    
    public static final String[] SIZE_NAMES = {"100KB", "1MB", "10MB", "100MB", "1GB", "5GB"};

    /** Target gzip ratio band for text fixtures (real English-like content). */
    private static final double TEXT_GZIP_MIN = 2.0;
    private static final double TEXT_GZIP_MAX = 4.5;
    /** Binary must stay near incompressible. */
    private static final double BINARY_GZIP_MAX = 1.15;

    private static final Random random = new Random(42L);

    private static final String[] WORDS = (
            "the of and to a in is that for on with as his they at be this from I have or by one had not "
            + "but what all were when we there can an your which their said if do will each about how up "
            + "out many then them these so some her would make like him into time has look two more write "
            + "go see number no way could people my than first water been call who oil its now find long "
            + "down day did get come made may part over new sound take only little work know place year "
            + "live me back give most very after thing our just name good sentence man think say great "
            + "where help through much before line right too mean old any same tell boy follow came want "
            + "show also around form three small set put end does another well large must big even such "
            + "because turn here why ask went men read need land different home us move try kind hand "
            + "picture again change off play spell air away animal house point page letter mother answer "
            + "found study still learn should America world high every near add food between own below "
            + "country plant last school father keep tree never start city earth eye light thought head "
            + "under story saw left few while along might close something seem next hard open example "
            + "begin life always those both paper together got group often run important until children "
            + "side feet car mile night walk white sea began grow took river four carry state once book "
            + "hear stop without second late miss idea enough eat face watch far Indian real almost let "
            + "above girl sometimes mountains cut young talk soon list song being leave family sample "
            + "data performance encrypt decrypt chunk cloud object storage latency throughput"
    ).split("\\s+");

    /**
     * Word-salad lines with unique line ids → typically ~2.5–3.5× gzip (not a single repeated phrase).
     */
    private static void generateTextFile(Path filePath, long size) throws IOException {
        Random lineRandom = new Random(42L);
        try (OutputStream out = Files.newOutputStream(filePath)) {
            long written = 0;
            long line = 0;
            while (written < size) {
                StringBuilder sb = new StringBuilder(96);
                sb.append(String.format("%08d", line)).append(' ');
                for (int w = 0; w < 16; w++) {
                    if (w > 0) {
                        sb.append(' ');
                    }
                    sb.append(WORDS[lineRandom.nextInt(WORDS.length)]);
                }
                sb.append('\n');
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                long room = size - written;
                if (room < bytes.length) {
                    out.write(bytes, 0, (int) room);
                    written += room;
                    break;
                }
                out.write(bytes);
                written += bytes.length;
                line++;
            }
        }
    }

    public static Map<String, Path> generateAllTestFiles(Path directory) throws IOException {
        Map<String, Path> allFiles = new HashMap<>();
        allFiles.putAll(generateTextFiles(directory));
        allFiles.putAll(generateBinaryFiles(directory));
        logger.info("Generated {} test files (text + binary)", allFiles.size());
        return allFiles;
    }
    
    public static Map<String, Path> generateTextFiles(Path directory) throws IOException {
        logger.info("Generating text test files (target gzip ~2–4x)...");
        Map<String, Path> files = new HashMap<>();
        for (int i = 0; i < FILE_SIZES.length; i++) {
            Path filePath = directory.resolve("text-" + SIZE_NAMES[i] + ".txt");
            ensureTextFile(filePath, FILE_SIZES[i]);
            files.put("text_" + SIZE_NAMES[i], filePath);
        }
        return files;
    }
    
    public static Map<String, Path> generateBinaryFiles(Path directory) throws IOException {
        logger.info("Generating binary test files (incompressible random)...");
        Map<String, Path> files = new HashMap<>();
        for (int i = 0; i < FILE_SIZES.length; i++) {
            Path filePath = directory.resolve("binary-" + SIZE_NAMES[i] + ".bin");
            ensureBinaryFile(filePath, FILE_SIZES[i]);
            files.put("binary_" + SIZE_NAMES[i], filePath);
        }
        return files;
    }

    private static void ensureTextFile(Path filePath, long fileSize) throws IOException {
        if (Files.exists(filePath)
                && Files.size(filePath) == fileSize
                && isAcceptableText(filePath)) {
            logger.info("Reusing text file: {} ({} bytes, gzip≈{}x)",
                    filePath, fileSize, String.format("%.2f", sampleGzipRatio(filePath)));
            return;
        }
        generateTextFile(filePath, fileSize);
        double ratio = sampleGzipRatio(filePath);
        logger.info("Created text file: {} ({} bytes, gzip≈{}x)",
                filePath, Files.size(filePath), String.format("%.2f", ratio));
        if (ratio < TEXT_GZIP_MIN || ratio > TEXT_GZIP_MAX) {
            logger.warn("Text gzip ratio {}x outside expected [{}, {}] for {}",
                    String.format("%.2f", ratio), TEXT_GZIP_MIN, TEXT_GZIP_MAX, filePath);
        }
    }

    private static void ensureBinaryFile(Path filePath, long fileSize) throws IOException {
        if (Files.exists(filePath)
                && Files.size(filePath) == fileSize
                && isAcceptableBinary(filePath)) {
            logger.info("Reusing binary file: {} ({} bytes, gzip≈{}x)",
                    filePath, fileSize, String.format("%.2f", sampleGzipRatio(filePath)));
            return;
        }
        generateBinaryFile(filePath, fileSize);
        double ratio = sampleGzipRatio(filePath);
        logger.info("Created binary file: {} ({} bytes, gzip≈{}x)",
                filePath, Files.size(filePath), String.format("%.2f", ratio));
        if (ratio > BINARY_GZIP_MAX) {
            throw new IOException("Binary fixture still compressible (gzip≈"
                    + String.format("%.2f", ratio) + "x): " + filePath);
        }
    }

    private static boolean isAcceptableText(Path filePath) throws IOException {
        byte[] head = readSample(filePath, 4096);
        if (head.length == 0) {
            return false;
        }
        for (byte b : head) {
            int u = b & 0xff;
            if (u < 9 || (u > 13 && u < 32) || u > 126) {
                return false; // not printable ASCII text
            }
        }
        double ratio = sampleGzipRatio(filePath);
        return ratio >= TEXT_GZIP_MIN && ratio <= TEXT_GZIP_MAX;
    }

    private static boolean isAcceptableBinary(Path filePath) throws IOException {
        return sampleGzipRatio(filePath) <= BINARY_GZIP_MAX;
    }
    
    /** Fresh {@code nextBytes} every chunk — must not compress. */
    private static void generateBinaryFile(Path filePath, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(filePath)) {
            long remaining = size;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int toWrite = (int) Math.min(remaining, buffer.length);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
            }
        }
    }

    /** Gzip ratio on up to 1 MiB from the start of the file. */
    static double sampleGzipRatio(Path filePath) throws IOException {
        byte[] sample = readSample(filePath, 1024 * 1024);
        if (sample.length == 0) {
            return Double.NaN;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(sample);
        }
        return (double) sample.length / baos.size();
    }

    private static byte[] readSample(Path filePath, int maxBytes) throws IOException {
        long size = Files.size(filePath);
        int n = (int) Math.min(size, maxBytes);
        byte[] buf = new byte[n];
        try (java.io.InputStream in = Files.newInputStream(filePath)) {
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            if (off == n) {
                return buf;
            }
            byte[] slim = new byte[off];
            System.arraycopy(buf, 0, slim, 0, off);
            return slim;
        }
    }
    
    public static void verifyFileSizes(Map<String, Path> files) throws IOException {
        logger.info("Verifying file sizes...");
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String fileName = entry.getKey();
            Path filePath = entry.getValue();
            String sizeName = fileName.substring(fileName.lastIndexOf('_') + 1);
            int sizeIndex = java.util.Arrays.asList(SIZE_NAMES).indexOf(sizeName);
            if (sizeIndex >= 0) {
                long expectedSize = FILE_SIZES[sizeIndex];
                long actualSize = Files.size(filePath);
                if (actualSize == expectedSize) {
                    logger.debug("File size verified for {}: {} bytes", fileName, actualSize);
                } else {
                    logger.warn("File size mismatch for {}: expected {} bytes, got {} bytes",
                            fileName, expectedSize, actualSize);
                }
            }
        }
    }

    public static Map<String, Path> generateLargeTestFiles(Path directory) throws IOException {
        Map<String, Path> files = new HashMap<>();
        for (int i = 4; i < FILE_SIZES.length; i++) {
            Path textPath = directory.resolve("text-" + SIZE_NAMES[i] + ".txt");
            ensureTextFile(textPath, FILE_SIZES[i]);
            files.put("text_" + SIZE_NAMES[i], textPath);
            Path binaryPath = directory.resolve("binary-" + SIZE_NAMES[i] + ".bin");
            ensureBinaryFile(binaryPath, FILE_SIZES[i]);
            files.put("binary_" + SIZE_NAMES[i], binaryPath);
        }
        return files;
    }

    /**
     * @param args {@code large} — 1GB/5GB; {@code smoke} — 1MB/10MB/100MB;
     *             {@code force} — delete matching fixtures first; default = all sizes.
     */
    public static void main(String[] args) {
        try {
            Path testFilesDir = Paths.get(System.getProperty("user.home"), ".altastata", "test-files");
            if (!Files.exists(testFilesDir)) {
                Files.createDirectories(testFilesDir);
                logger.info("Created test files directory: {}", testFilesDir);
            }

            boolean largeOnly = false;
            boolean smokeOnly = false;
            boolean force = false;
            if (args != null) {
                for (String raw : args) {
                    if (raw == null) {
                        continue;
                    }
                    for (String a : raw.trim().split("\\s+")) {
                        if ("large".equalsIgnoreCase(a)) {
                            largeOnly = true;
                        }
                        if ("smoke".equalsIgnoreCase(a)) {
                            smokeOnly = true;
                        }
                        if ("force".equalsIgnoreCase(a)) {
                            force = true;
                        }
                    }
                }
            }

            int from = 0;
            int to = FILE_SIZES.length;
            if (largeOnly) {
                from = 4;
                to = FILE_SIZES.length;
            } else if (smokeOnly) {
                from = 1; // 1MB
                to = 4;   // exclusive of 1GB
            }

            if (force) {
                for (int i = from; i < to; i++) {
                    Files.deleteIfExists(testFilesDir.resolve("text-" + SIZE_NAMES[i] + ".txt"));
                    Files.deleteIfExists(testFilesDir.resolve("binary-" + SIZE_NAMES[i] + ".bin"));
                }
                logger.info("Force: deleted fixtures for sizes {}..{}", SIZE_NAMES[from], SIZE_NAMES[to - 1]);
            }

            Map<String, Path> files = new HashMap<>();
            for (int i = from; i < to; i++) {
                Path textPath = testFilesDir.resolve("text-" + SIZE_NAMES[i] + ".txt");
                ensureTextFile(textPath, FILE_SIZES[i]);
                files.put("text_" + SIZE_NAMES[i], textPath);
                Path binaryPath = testFilesDir.resolve("binary-" + SIZE_NAMES[i] + ".bin");
                ensureBinaryFile(binaryPath, FILE_SIZES[i]);
                files.put("binary_" + SIZE_NAMES[i], binaryPath);
            }
            
            verifyFileSizes(files);
            logger.info("Successfully generated {} test files in {}", files.size(), testFilesDir);
            
        } catch (IOException e) {
            logger.error("Failed to generate test files", e);
            System.exit(1);
        }
    }
}
