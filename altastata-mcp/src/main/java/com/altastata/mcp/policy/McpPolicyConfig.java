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

package com.altastata.mcp.policy;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Micronaut configuration for the MCP front-end ({@code altastata.mcp.*}).
 */
@ConfigurationProperties("altastata.mcp")
public class McpPolicyConfig {

    /** Account directory used when MCP binds a single identity at boot. */
    private String accountDir = "";

    /** Unlock password for RSA/PQC accounts; empty for HSM/HPCS. */
    private String password = "";

    /**
     * Tools advertised by {@code tools/list}. Default is read-only + events.
     * {@code grant_access} / {@code revoke_access} must be listed explicitly.
     */
    private List<String> enabledTools = new ArrayList<>(List.of(
            "list_files",
            "read_file",
            "get_attributes",
            "list_versions",
            "list_grants_for_file",
            "list_recent_events",
            "get_serviceid_status"
    ));

    /** Optional path prefixes the agent may read (empty = no path restriction). */
    private List<String> readOnlyRoots = new ArrayList<>();

    /** Cap for {@code read_file} payload size in bytes. */
    private long maxReadBytes = 50_000_000L;

    /** Transport: {@code stdio} (default for --mcp-stdio) or {@code none}. */
    private String transport = "stdio";

    public String getAccountDir() {
        return accountDir;
    }

    public void setAccountDir(String accountDir) {
        this.accountDir = accountDir;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools != null ? enabledTools : new ArrayList<>();
    }

    public List<String> getReadOnlyRoots() {
        return readOnlyRoots;
    }

    public void setReadOnlyRoots(List<String> readOnlyRoots) {
        this.readOnlyRoots = readOnlyRoots != null ? readOnlyRoots : new ArrayList<>();
    }

    public long getMaxReadBytes() {
        return maxReadBytes;
    }

    public void setMaxReadBytes(long maxReadBytes) {
        this.maxReadBytes = maxReadBytes;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }
}
