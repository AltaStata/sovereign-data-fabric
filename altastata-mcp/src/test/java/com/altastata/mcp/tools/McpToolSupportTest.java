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

package com.altastata.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolSupportTest {

    @Test
    void argParsingHandlesTypesAndDefaults() {
        Map<String, Object> args = Map.of(
                "str", "hello",
                "bool", true,
                "num", 42L,
                "list", List.of("a", "b")
        );

        assertEquals("hello", McpToolSupport.argString(args, "str", "default"));
        assertEquals("default", McpToolSupport.argString(args, "missing", "default"));

        assertTrue(McpToolSupport.argBool(args, "bool", false));
        assertFalse(McpToolSupport.argBool(args, "missing", false));

        assertEquals(42L, McpToolSupport.argLong(args, "num", 0L));
        assertEquals(42, McpToolSupport.argInt(args, "num", 0));

        assertEquals(List.of("a", "b"), McpToolSupport.argStringList(args, "list"));
        assertTrue(McpToolSupport.argStringList(args, "missing").isEmpty());
    }

    @Test
    void utf8AndSha256Detection() {
        byte[] validUtf8 = "Hello AltaStata".getBytes(StandardCharsets.UTF_8);
        assertTrue(McpToolSupport.looksLikeUtf8(validUtf8));

        String hash = McpToolSupport.sha256Hex(Map.of("key", "value"));
        assertNotNull(hash);
        assertFalse(hash.isBlank());
    }

    @Test
    void simplePatternMatching() {
        assertTrue(McpToolSupport.matchesSimplePattern("data/reports/q1.csv", "*.csv"));
        assertTrue(McpToolSupport.matchesSimplePattern("data/reports/q1.csv", "*"));
        assertFalse(McpToolSupport.matchesSimplePattern("data/reports/q1.csv", "*.pdf"));
    }

    @Test
    void okAndErrorResults() {
        McpSchema.CallToolResult ok = McpToolSupport.okText("success");
        assertFalse(ok.isError());

        McpSchema.CallToolResult err = McpToolSupport.errorText("failed");
        assertTrue(err.isError());
    }
}
