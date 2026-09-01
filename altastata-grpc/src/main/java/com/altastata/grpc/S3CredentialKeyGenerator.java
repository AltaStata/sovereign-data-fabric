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

package com.altastata.grpc;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates opaque S3-style access/secret key pairs for
 * {@link S3CredentialsRegistry}.
 */
final class S3CredentialKeyGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ACCESS_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * Private constructor to prevent instantiation of utility key generator.
     */
    private S3CredentialKeyGenerator() {
    }

    /**
     * Generates a random 20-character S3-compatible Access Key ID starting with "AKIA".
     *
     * @return generated S3 access key ID
     */
    static String generateAccessKeyId() {
        // 20-char id similar to AWS access key ids (AKIA + 16 chars).
        char[] chars = new char[16];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ACCESS_ALPHABET.charAt(RNG.nextInt(ACCESS_ALPHABET.length()));
        }
        return "AKIA" + new String(chars);
    }

    /**
     * Generates a secure, random Base64-encoded S3-compatible Secret Access Key.
     *
     * @return generated S3 secret key
     */
    static String generateSecretAccessKey() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getEncoder().withoutPadding().encodeToString(raw);
    }
}
