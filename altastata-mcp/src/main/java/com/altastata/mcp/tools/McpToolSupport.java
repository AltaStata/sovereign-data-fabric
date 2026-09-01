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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class McpToolSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private McpToolSupport() {
    }

    static String argString(Map<String, Object> args, String key, String defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(args.get(key));
    }

    static boolean argBool(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object v = args.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    static long argLong(Map<String, Object> args, String key, long defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static int argInt(Map<String, Object> args, String key, int defaultValue) {
        long v = argLong(args, key, defaultValue);
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) v;
    }

    @SuppressWarnings("unchecked")
    static List<String> argStringList(Map<String, Object> args, String key) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return List.of();
        }
        Object v = args.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v));
    }

    static McpSchema.CallToolResult okText(String text) {
        return new McpSchema.CallToolResult(text, false);
    }

    static McpSchema.CallToolResult errorText(String text) {
        return new McpSchema.CallToolResult(text, true);
    }

    static McpSchema.CallToolResult okJson(Object value) {
        try {
            return okText(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (Exception e) {
            return errorText("JSON encode failed: " + e.getMessage());
        }
    }

    static String sha256Hex(Object value) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(value == null ? Map.of() : value);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    static boolean looksLikeUtf8(byte[] data) {
        if (data == null) {
            return true;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean matchesSimplePattern(String path, String pattern) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return true;
        }
        String name = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            name = path.substring(slash + 1);
        }
        if (pattern.startsWith("*.") && !pattern.substring(2).contains("*")) {
            return name.toLowerCase().endsWith(pattern.substring(1).toLowerCase());
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return name.equals(pattern) || path.equals(pattern);
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("?", ".")
                .replace("*", ".*");
        return name.matches(regex) || path.matches(regex);
    }
}
