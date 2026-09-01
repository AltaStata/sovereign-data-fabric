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

import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-tool and path policy gate for MCP {@code tools/call}.
 */
@Singleton
public class McpPolicy {

    private final Set<String> enabledTools;
    private final List<String> readOnlyRoots;
    private final long maxReadBytes;

    public McpPolicy(McpPolicyConfig config) {
        this.enabledTools = new LinkedHashSet<>();
        for (String t : config.getEnabledTools()) {
            if (t != null && !t.isBlank()) {
                enabledTools.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.readOnlyRoots = List.copyOf(config.getReadOnlyRoots());
        this.maxReadBytes = Math.max(1L, config.getMaxReadBytes());
    }

    /** Package-visible for unit tests without Micronaut. */
    McpPolicy(Set<String> enabledTools, List<String> readOnlyRoots, long maxReadBytes) {
        this.enabledTools = new LinkedHashSet<>();
        for (String t : enabledTools) {
            this.enabledTools.add(t.toLowerCase(Locale.ROOT));
        }
        this.readOnlyRoots = List.copyOf(readOnlyRoots);
        this.maxReadBytes = Math.max(1L, maxReadBytes);
    }

    public boolean isToolEnabled(String toolName) {
        if (toolName == null) {
            return false;
        }
        return enabledTools.contains(toolName.toLowerCase(Locale.ROOT));
    }

    public Set<String> enabledTools() {
        return Set.copyOf(enabledTools);
    }

    public long maxReadBytes() {
        return maxReadBytes;
    }

    /**
     * Returns null when the path is allowed; otherwise a human-readable denial.
     */
    public String denyReasonForPath(String path) {
        if (readOnlyRoots.isEmpty()) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return "path is required";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String root : readOnlyRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String r = root.replace('\\', '/');
            if (normalized.equals(r) || normalized.startsWith(r.endsWith("/") ? r : r + "/")) {
                return null;
            }
        }
        return "path '" + path + "' is outside configured read_only_roots";
    }
}
