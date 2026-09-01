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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPolicyTest {

    @Test
    void defaultsEnableReadToolsButNotGrantRevoke() {
        McpPolicyConfig config = new McpPolicyConfig();
        McpPolicy policy = new McpPolicy(config);
        assertTrue(policy.isToolEnabled("list_files"));
        assertTrue(policy.isToolEnabled("read_file"));
        assertFalse(policy.isToolEnabled("grant_access"));
        assertFalse(policy.isToolEnabled("revoke_access"));
    }

    @Test
    void pathRootsAreEnforcedWhenConfigured() {
        McpPolicy policy = new McpPolicy(
                Set.of("list_files"),
                List.of("data/quarterly"),
                1_000_000L);
        assertNull(policy.denyReasonForPath("data/quarterly/q1.csv"));
        assertTrue(policy.denyReasonForPath("secrets/keys.pem").contains("outside"));
    }
}
