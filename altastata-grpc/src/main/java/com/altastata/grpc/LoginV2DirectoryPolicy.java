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

import com.altastata.utils.Account;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards {@code LoginV2.user_account_directory} per
 * {@code CONSOLE_ACCOUNT_SETUP_DESIGN.md §2} / §8.1: co-located clients
 * only, path must stay under the gateway's accounts root.
 */
final class LoginV2DirectoryPolicy {

    private final Path accountsRoot;
    private final boolean directoryLoginPermitted;

    LoginV2DirectoryPolicy() {
        this(resolveAccountsRoot(), isDirectoryLoginPermitted());
    }

    LoginV2DirectoryPolicy(Path accountsRoot, boolean directoryLoginPermitted) {
        this.accountsRoot = accountsRoot.toAbsolutePath().normalize();
        this.directoryLoginPermitted = directoryLoginPermitted;
    }

    /**
     * Enforces security constraints and normalizes the target login folder directory path.
     *
     * @param userAccountDirectory raw login directory path
     * @return validated canonical path string
     */
    String validateAndCanonicalize(String userAccountDirectory) {
        if (!directoryLoginPermitted) {
            throw new SecurityException(
                    "LoginV2 user_account_directory is disabled on this gateway; "
                            + "set altastata.local-mode.allow-account-directory=true "
                            + "or bind to loopback");
        }
        if (userAccountDirectory == null || userAccountDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("user_account_directory is required");
        }
        Path path = Paths.get(userAccountDirectory).toAbsolutePath().normalize();
        if (!path.startsWith(accountsRoot)) {
            throw new SecurityException(
                    "user_account_directory must be under " + accountsRoot);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }
        return path.toString();
    }

    /**
     * Resolves the default configured AltaStata account home path.
     *
     * @return Path representing accounts root
     */
    private static Path resolveAccountsRoot() {
        return Paths.get(Account.ALTASTATA_ACCOUNTS_HOME());
    }

    /**
     * Checks whether logging in via a local folder path is enabled based on network binding or flags.
     *
     * @return true if permitted; false otherwise
     */
    private static boolean isDirectoryLoginPermitted() {
        if (isAllowAccountDirectoryFlag()) {
            return true;
        }
        String bind = System.getenv("ALTASTATA_GRPC_BIND_ADDRESS");
        if (bind == null || bind.trim().isEmpty()) {
            return true;
        }
        bind = bind.trim();
        if ("127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind) || "::1".equals(bind)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(bind);
            return address.isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolves the allow-account-directory config flag from system properties or environment.
     *
     * @return true if explicitly enabled; false otherwise
     */
    private static boolean isAllowAccountDirectoryFlag() {
        String prop = System.getProperty("altastata.local-mode.allow-account-directory");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        String env = System.getenv("ALTASTATA_LOCAL_MODE_ALLOW_ACCOUNT_DIRECTORY");
        return env != null && Boolean.parseBoolean(env);
    }
}
