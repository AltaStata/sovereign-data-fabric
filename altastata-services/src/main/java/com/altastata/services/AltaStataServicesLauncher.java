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

package com.altastata.services;

import com.altastata.mcp.McpStdioStreams;

import java.util.Arrays;

/**
 * Process entry point. Applies {@code --mcp-stdio} JVM properties
 * <em>before</em> {@link AltaStataServicesApplication} (and Logback) initialize,
 * so MCP JSON-RPC on stdout is not polluted by console logging.
 */
public final class AltaStataServicesLauncher {

    private AltaStataServicesLauncher() {
        throw new AssertionError("Not instantiable");
    }

    /**
     * Launches AltaStata services, optionally configuring MCP stdio mode first.
     *
     * @param args command-line arguments
     * @throws InterruptedException if the main thread is interrupted while waiting
     */
    public static void main(String[] args) throws InterruptedException {
        // Capture real stdout before any bootstrap redirect (Micronaut banner).
        McpStdioStreams.capture();
        if (Arrays.asList(args).contains("--mcp-stdio")) {
            System.setProperty("altastata.services.mcp.enabled", "true");
            System.setProperty("altastata.mcp.transport", "stdio");
            System.setProperty("altastata.services.grpc.enabled", "false");
            System.setProperty("altastata.services.s3gateway.enabled", "false");
            System.setProperty("altastata.services.py4j.enabled", "false");
            System.setProperty("micronaut.server.enabled", "false");
            System.setProperty("micronaut.application.banner-enabled", "false");
            if (System.getProperty("logback.configurationFile") == null
                    && System.getenv("LOGBACK_CONFIGURATION_FILE") == null) {
                System.setProperty("logback.configurationFile", "logback-mcp-stdio.xml");
            }
        }
        AltaStataServicesApplication.main(args);
    }
}
