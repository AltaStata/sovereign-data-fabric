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

package com.altastata.cloud.ibm;

import com.altastata.utils.Account;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Prevents accidental overwrite of existing HPCS key files in an account directory.
 *
 * <p>Sandbox experiments should use {@link #DEFAULT_SANDBOX_ACCOUNT_DIR}
 * ({@code amazon.rsa.hpcs.hpcsdev}). Keygen refuses to replace an existing
 * {@code public.key} + {@code hpcs-privkey.blob} pair unless
 * {@code ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true}.
 */
public final class HpcsAccountGuard {

    /** Default directory for local HPCS keygen / sandbox experiments. */
    public static final String DEFAULT_SANDBOX_ACCOUNT_DIR = "amazon.rsa.hpcs.hpcsdev";

    /** HPCS key label / myuser for the sandbox account (last segment of dir name). */
    public static final String DEFAULT_SANDBOX_USER = "hpcsdev";

    private static final String ALLOW_PROTECTED_ENV = "ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT";

    /**
     * Private constructor to prevent instantiation.
     */
    private HpcsAccountGuard() {
    }

    /**
     * Gets the absolute directory path of the default HPCS sandbox account.
     *
     * @return the sandbox account directory path
     */
    public static String sandboxAccountDir() {
        return Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + DEFAULT_SANDBOX_ACCOUNT_DIR;
    }

    /**
     * Refuse to overwrite existing GREP11 key files unless
     * {@code ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true}.
     */
    public static void assertSafeToWriteKeyFiles(String accountDir) {
        if (accountDir == null || accountDir.trim().isEmpty()) {
            throw new IllegalArgumentException("accountDir is required");
        }
        if (hasExistingGrep11KeyFiles(accountDir) && !protectedAccountWritesAllowed()) {
            throw new IllegalStateException(
                    "Refusing to overwrite existing HPCS key files in: "
                            + accountDir + "\n"
                            + "Use the sandbox instead:\n"
                            + "  " + sandboxAccountDir() + "  (user/label: " + DEFAULT_SANDBOX_USER + ")\n"
                            + "  ./altastata-core/scripts/run-hpcs-create-sandbox-key.sh\n"
                            + "To replace local blobs deliberately, set " + ALLOW_PROTECTED_ENV + "=true");
        }
    }

    /**
     * Checks if writes that replace existing key files are explicitly allowed
     * via environment flags.
     *
     * @return true if overwrite of existing key files is allowed; false otherwise
     */
    public static boolean protectedAccountWritesAllowed() {
        return "true".equalsIgnoreCase(System.getenv(ALLOW_PROTECTED_ENV))
                || "1".equals(System.getenv(ALLOW_PROTECTED_ENV));
    }

    /**
     * True when the account dir already has GREP11 key material (would be overwritten).
     */
    public static boolean hasExistingGrep11KeyFiles(String accountDir) {
        Path dir = Paths.get(accountDir);
        return Files.isRegularFile(dir.resolve("public.key"))
                && Files.isRegularFile(dir.resolve("hpcs-privkey.blob"));
    }
}
