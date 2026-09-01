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

import com.altastata.api.AltaStataFileSystem;
import com.altastata.mcp.policy.McpPolicy;
import com.altastata.mcp.session.McpAccountSession;
import com.altastata.utils.Account;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Registers MCP tools that delegate to in-process {@link AltaStataFileSystem}
 * (same APIs the gRPC controllers use — no network hop).
 */
@Singleton
public class McpToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpPolicy policy;
    private final McpAccountSession session;

    public McpToolRegistry(McpPolicy policy, McpAccountSession session) {
        this.policy = policy;
        this.session = session;
    }

    public List<McpServerFeatures.SyncToolSpecification> enabledTools() {
        List<McpServerFeatures.SyncToolSpecification> out = new ArrayList<>();
        addIfEnabled(out, "list_files",
                "List AltaStata cloud file versions under a path prefix.",
                """
                {"type":"object","properties":{
                  "root":{"type":"string","description":"Cloud path prefix"},
                  "pattern":{"type":"string","description":"Optional simple glob, e.g. *.csv"},
                  "recursive":{"type":"boolean","default":true}
                },"required":["root"]}
                """,
                this::listFiles);

        addIfEnabled(out, "read_file",
                "Read file bytes (UTF-8 text when possible; otherwise Base64).",
                """
                {"type":"object","properties":{
                  "path":{"type":"string"},
                  "snapshot_time":{"type":"integer","description":"Optional version createTime millis"},
                  "start":{"type":"integer","default":0},
                  "size":{"type":"integer","description":"Max bytes to return"}
                },"required":["path"]}
                """,
                this::readFile);

        addIfEnabled(out, "get_attributes",
                "Read named metadata attributes for a cloud file.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string"},
                  "names":{"type":"array","items":{"type":"string"},
                           "description":"Attribute names; default [size,readers,owner]"}
                },"required":["path"]}
                """,
                this::getAttributes);

        addIfEnabled(out, "list_versions",
                "List version rows for a cloud path prefix.",
                """
                {"type":"object","properties":{
                  "path_prefix":{"type":"string"},
                  "recursive":{"type":"boolean","default":true},
                  "time_interval_start":{"type":"string"},
                  "time_interval_end":{"type":"string"}
                },"required":["path_prefix"]}
                """,
                this::listVersions);

        addIfEnabled(out, "list_grants_for_file",
                "List current readers (grants) for a file via the readers attribute.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string"}
                },"required":["path"]}
                """,
                this::listGrants);

        addIfEnabled(out, "grant_access",
                "Share paths with readers (disabled by default in policy).",
                """
                {"type":"object","properties":{
                  "paths":{"type":"array","items":{"type":"string"}},
                  "readers":{"type":"array","items":{"type":"string"}},
                  "recursive":{"type":"boolean","default":true}
                },"required":["paths","readers"]}
                """,
                this::grantAccess);

        addIfEnabled(out, "revoke_access",
                "Revoke reader access on paths (disabled by default in policy).",
                """
                {"type":"object","properties":{
                  "paths":{"type":"array","items":{"type":"string"}},
                  "readers":{"type":"array","items":{"type":"string"}},
                  "recursive":{"type":"boolean","default":true}
                },"required":["paths","readers"]}
                """,
                this::revokeAccess);

        addIfEnabled(out, "list_recent_events",
                "Return recent in-process user messages / event breadcrumbs.",
                """
                {"type":"object","properties":{
                  "limit":{"type":"integer","default":50}
                }}
                """,
                this::listRecentEvents);

        addIfEnabled(out, "get_serviceid_status",
                "Return the bound MCP identity and known users on the fabric.",
                """
                {"type":"object","properties":{
                  "serviceid":{"type":"string","description":"Optional; defaults to bound identity"}
                }}
                """,
                this::getServiceIdStatus);

        return out;
    }

    private void addIfEnabled(
            List<McpServerFeatures.SyncToolSpecification> out,
            String name,
            String description,
            String inputSchemaJson,
            BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        if (!policy.isToolEnabled(name)) {
            return;
        }
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchemaJson)
                .build();
        out.add(new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            String digest = McpToolSupport.sha256Hex(args);
            try {
                McpSchema.CallToolResult result = handler.apply(exchange, args == null ? Map.of() : args);
                LOGGER.info("MCP tool={} result={} argsDigest={}",
                        name,
                        Boolean.TRUE.equals(result.isError()) ? "ERROR" : "OK",
                        digest);
                return result;
            } catch (Exception e) {
                LOGGER.warn("MCP tool={} ERROR argsDigest={}: {}", name, digest, e.toString());
                return McpToolSupport.errorText(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }));
    }

    private McpSchema.CallToolResult listFiles(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        String root = McpToolSupport.argString(args, "root", "");
        String deny = policy.denyReasonForPath(root);
        if (deny != null) {
            return McpToolSupport.errorText(deny);
        }
        String pattern = McpToolSupport.argString(args, "pattern", "*");
        boolean recursive = McpToolSupport.argBool(args, "recursive", true);
        AltaStataFileSystem fs = session.requireFileSystem();
        Set<String> paths = new LinkedHashSet<>();
        Iterator<String[]> it = fs.listCloudFilesVersions(root, recursive, null, null);
        while (it.hasNext()) {
            String[] row = it.next();
            if (row == null || row.length == 0) {
                continue;
            }
            String path = row[0];
            if (McpToolSupport.matchesSimplePattern(path, pattern)) {
                paths.add(path);
            }
        }
        return McpToolSupport.okJson(paths);
    }

    private McpSchema.CallToolResult readFile(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        String path = McpToolSupport.argString(args, "path", "");
        String deny = policy.denyReasonForPath(path);
        if (deny != null) {
            return McpToolSupport.errorText(deny);
        }
        long start = McpToolSupport.argLong(args, "start", 0L);
        int size = McpToolSupport.argInt(args, "size", (int) Math.min(policy.maxReadBytes(), Integer.MAX_VALUE));
        if (size > policy.maxReadBytes()) {
            return McpToolSupport.errorText("size exceeds max_read_bytes=" + policy.maxReadBytes());
        }
        Long snapshot = null;
        long snapshotArg = McpToolSupport.argLong(args, "snapshot_time", -1L);
        if (snapshotArg > 0) {
            snapshot = snapshotArg;
        }
        try {
            AltaStataFileSystem fs = session.requireFileSystem();
            byte[] data = fs.getBuffer(path, snapshot, start, 4, size);
            if (data == null) {
                return McpToolSupport.errorText("empty read for path=" + path);
            }
            if (McpToolSupport.looksLikeUtf8(data)) {
                return McpToolSupport.okText(new String(data, StandardCharsets.UTF_8));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("encoding", "base64");
            payload.put("bytes", data.length);
            payload.put("content", Base64.getEncoder().encodeToString(data));
            return McpToolSupport.okJson(payload);
        } catch (Exception e) {
            return McpToolSupport.errorText(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private McpSchema.CallToolResult getAttributes(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        String path = McpToolSupport.argString(args, "path", "");
        String deny = policy.denyReasonForPath(path);
        if (deny != null) {
            return McpToolSupport.errorText(deny);
        }
        List<String> names = McpToolSupport.argStringList(args, "names");
        if (names.isEmpty()) {
            names = List.of("size", "readers", "owner");
        }
        AltaStataFileSystem fs = session.requireFileSystem();
        Map<String, String> attrs = fs.getFileAttributes(path, null, names);
        return McpToolSupport.okJson(attrs);
    }

    private McpSchema.CallToolResult listVersions(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        String prefix = McpToolSupport.argString(args, "path_prefix", "");
        String deny = policy.denyReasonForPath(prefix);
        if (deny != null) {
            return McpToolSupport.errorText(deny);
        }
        boolean recursive = McpToolSupport.argBool(args, "recursive", true);
        String start = McpToolSupport.argString(args, "time_interval_start", null);
        String end = McpToolSupport.argString(args, "time_interval_end", null);
        if (start != null && start.isBlank()) {
            start = null;
        }
        if (end != null && end.isBlank()) {
            end = null;
        }
        AltaStataFileSystem fs = session.requireFileSystem();
        List<List<String>> rows = new ArrayList<>();
        Iterator<String[]> it = fs.listCloudFilesVersions(prefix, recursive, start, end);
        while (it.hasNext()) {
            String[] row = it.next();
            if (row != null) {
                rows.add(List.of(row));
            }
        }
        return McpToolSupport.okJson(rows);
    }

    private McpSchema.CallToolResult listGrants(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        String path = McpToolSupport.argString(args, "path", "");
        String deny = policy.denyReasonForPath(path);
        if (deny != null) {
            return McpToolSupport.errorText(deny);
        }
        AltaStataFileSystem fs = session.requireFileSystem();
        String readers = fs.getFileAttribute(path, null, "readers");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("readers", readers == null ? "" : readers);
        return McpToolSupport.okJson(out);
    }

    private McpSchema.CallToolResult grantAccess(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        List<String> paths = McpToolSupport.argStringList(args, "paths");
        List<String> readers = McpToolSupport.argStringList(args, "readers");
        boolean recursive = McpToolSupport.argBool(args, "recursive", true);
        if (paths.isEmpty() || readers.isEmpty()) {
            return McpToolSupport.errorText("paths and readers are required");
        }
        for (String path : paths) {
            String deny = policy.denyReasonForPath(path);
            if (deny != null) {
                return McpToolSupport.errorText(deny);
            }
        }
        AltaStataFileSystem fs = session.requireFileSystem();
        String[] readerArr = readers.toArray(String[]::new);
        List<Object> statuses = new ArrayList<>();
        for (String path : paths) {
            statuses.add(fs.share(path, recursive, null, null, readerArr));
        }
        return McpToolSupport.okJson(Map.of("status", "ok", "results", statuses.toString()));
    }

    private McpSchema.CallToolResult revokeAccess(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        List<String> paths = McpToolSupport.argStringList(args, "paths");
        List<String> readers = McpToolSupport.argStringList(args, "readers");
        boolean recursive = McpToolSupport.argBool(args, "recursive", true);
        if (paths.isEmpty() || readers.isEmpty()) {
            return McpToolSupport.errorText("paths and readers are required");
        }
        for (String path : paths) {
            String deny = policy.denyReasonForPath(path);
            if (deny != null) {
                return McpToolSupport.errorText(deny);
            }
        }
        AltaStataFileSystem fs = session.requireFileSystem();
        String[] readerArr = readers.toArray(String[]::new);
        List<Object> statuses = new ArrayList<>();
        for (String path : paths) {
            statuses.add(fs.revokeReaderAccess(path, recursive, null, null, readerArr));
        }
        return McpToolSupport.okJson(Map.of("status", "ok", "results", statuses.toString()));
    }

    private McpSchema.CallToolResult listRecentEvents(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        int limit = McpToolSupport.argInt(args, "limit", 50);
        AltaStataFileSystem fs = session.requireFileSystem();
        Account account = fs.getAccount();
        List<String> messages = new ArrayList<>();
        if (account != null && account.userMsgs() != null) {
            List<String> all = account.userMsgs();
            int from = Math.max(0, all.size() - Math.max(1, limit));
            messages.addAll(all.subList(from, all.size()));
        }
        return McpToolSupport.okJson(messages);
    }

    private McpSchema.CallToolResult getServiceIdStatus(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        AltaStataFileSystem fs = session.requireFileSystem();
        Account account = fs.getAccount();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bound_user", account.MY_USER());
        out.put("account_id", String.valueOf(fs.getAccountId()));
        out.put("account_type", account.userProps().getProperty("accounttype"));
        String requested = McpToolSupport.argString(args, "serviceid", null);
        if (requested != null && !requested.isBlank()) {
            out.put("requested_serviceid", requested);
            out.put("matches_bound", requested.equals(account.MY_USER()));
        }
        try {
            out.put("users", fs.listUsers());
        } catch (Exception e) {
            out.put("users_error", e.getMessage());
        }
        return McpToolSupport.okJson(out);
    }
}
