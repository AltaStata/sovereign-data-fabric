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

package com.altastata.mcp.session;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.mcp.policy.McpPolicyConfig;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds the MCP server to exactly one AltaStata identity via {@link AccountRegistry}.
 * Same trust model as the S3 gateway: the agent can only see what this identity can decrypt.
 */
@Singleton
public class McpAccountSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpAccountSession.class);

    private final McpPolicyConfig config;
    private volatile AltaStataFileSystem fileSystem;
    private volatile String bindError;

    public McpAccountSession(McpPolicyConfig config) {
        this.config = config;
    }

    /**
     * Lazily opens and unlocks the configured account. Safe to call repeatedly.
     *
     * @return unlocked filesystem
     * @throws IllegalStateException if account dir/password are missing or unlock fails
     */
    public synchronized AltaStataFileSystem requireFileSystem() {
        if (fileSystem != null) {
            return fileSystem;
        }
        if (bindError != null) {
            throw new IllegalStateException(bindError);
        }

        String dir = firstNonBlank(
                config.getAccountDir(),
                System.getenv("ALTASTATA_MCP_ACCOUNT_DIR"),
                System.getenv("ALTASTATA_ACCOUNT_DIR"));
        if (dir == null || dir.isBlank()) {
            bindError = "MCP account not configured: set altastata.mcp.account-dir "
                    + "or ALTASTATA_MCP_ACCOUNT_DIR";
            throw new IllegalStateException(bindError);
        }

        try {
            AltaStataFileSystem fs = AccountRegistry.getOrCreateFromDir(dir.trim());
            String password = firstNonBlank(
                    config.getPassword(),
                    System.getenv("ALTASTATA_MCP_PASSWORD"),
                    System.getenv("ALTASTATA_PASSWORD"));
            if (password != null && !password.isEmpty()) {
                fs.setPassword(password);
            } else if (fs.getAccount() != null && fs.getAccount().requiresLocalPassword()) {
                bindError = "MCP account requires a password: set altastata.mcp.password "
                        + "or ALTASTATA_MCP_PASSWORD";
                AccountRegistry.invalidate(fs);
                throw new IllegalStateException(bindError);
            } else {
                // HSM/HPCS: setPassword(null/empty) initializes managers + event loop.
                fs.setPassword("");
            }
            this.fileSystem = fs;
            LOGGER.info("MCP bound to account {} ({})",
                    fs.getAccount().MY_USER(), fs.getAccountId());
            return fs;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            bindError = "MCP account unlock failed: " + e.getMessage();
            LOGGER.error(bindError, e);
            throw new IllegalStateException(bindError, e);
        }
    }

    public boolean isBound() {
        return fileSystem != null;
    }

    @PreDestroy
    void shutdown() {
        AltaStataFileSystem fs = fileSystem;
        fileSystem = null;
        if (fs != null) {
            try {
                AccountRegistry.invalidate(fs);
            } catch (Exception e) {
                LOGGER.warn("MCP account invalidate failed: {}", e.toString());
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
