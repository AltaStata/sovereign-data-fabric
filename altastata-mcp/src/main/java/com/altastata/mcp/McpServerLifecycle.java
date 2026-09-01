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

package com.altastata.mcp;

import com.altastata.mcp.policy.McpPolicyConfig;
import com.altastata.mcp.tools.McpToolRegistry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots the MCP server when {@code altastata.services.mcp.enabled=true}.
 * v1 transport is stdio (Claude Desktop / Cursor). Streamable HTTP is a follow-up.
 *
 * <p>{@link Context} forces eager creation at Micronaut startup (same pattern as
 * {@code GrpcGatewayServer}); without it the singleton stays lazy and never starts.
 */
@Context
@Singleton
@Requires(property = "altastata.services.mcp.enabled", value = "true", defaultValue = "false")
public class McpServerLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerLifecycle.class);

    private final McpPolicyConfig config;
    private final McpToolRegistry toolRegistry;
    private volatile McpSyncServer server;

    public McpServerLifecycle(McpPolicyConfig config, McpToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        start();
    }

    private void start() {
        String transport = config.getTransport() == null ? "stdio" : config.getTransport().trim().toLowerCase();
        if (!"stdio".equals(transport)) {
            LOGGER.warn("altastata.mcp.transport={} is not implemented yet; only stdio is supported in v1",
                    transport);
            return;
        }
        LOGGER.info("Starting AltaStata MCP server (stdio), tools={}",
                toolRegistry.enabledTools().stream().map(t -> t.tool().name()).toList());

        // Use captured process streams — System.out may be diverted to stderr
        // during Micronaut bootstrap so the ASCII banner does not break JSON-RPC.
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(
                new ObjectMapper(),
                McpStdioStreams.stdin(),
                McpStdioStreams.stdout());
        server = McpServer.sync(transportProvider)
                .serverInfo("altastata-mcp", "0.1.0")
                .instructions(
                        "AltaStata MCP: encrypted sovereign storage tools. "
                                + "Reads/writes go through one bound AltaStata identity "
                                + "(per-recipient encryption + revocable grants). "
                                + "grant_access/revoke_access are off unless enabled in policy.")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(toolRegistry.enabledTools())
                .immediateExecution(true)
                .build();

        LOGGER.info("AltaStata MCP server ready on stdio");
    }

    @PreDestroy
    void stop() {
        McpSyncServer s = server;
        server = null;
        if (s != null) {
            try {
                s.closeGracefully();
            } catch (Exception e) {
                LOGGER.warn("MCP shutdown: {}", e.toString());
                try {
                    s.close();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }
}
