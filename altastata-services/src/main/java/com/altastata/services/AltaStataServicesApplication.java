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

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import py4j.GatewayServer;

import java.io.OutputStream;
import java.io.PrintStream;
import java.security.Security;

/**
 * Single Micronaut application that hosts altastata-grpc, altastata-s3-gateway,
 * and altastata-mcp as opt-in libraries. See {@code ALTASTATA_SERVICES_UBER_DESIGN.md}
 * for the motivation (deduplicated per-account caches via the JVM-wide static
 * {@link com.altastata.api.AccountRegistry}, single uber jar, one JVM-wide
 * BouncyCastle provider / JIT code cache / Netty event loops).
 *
 * <p>Each embedded service is gated on a system property so the same uber jar
 * powers several deployments:
 * <ul>
 *   <li><b>Python wheel</b> — gRPC + py4j on, S3/MCP off (default).</li>
 *   <li><b>Releases / co-hosted</b> — gRPC + S3 on (Web Console on :9877,
 *       S3 on :9876). Session credentials are issued by gRPC
 *       {@code LoginV2} → {@code IssueCredentials} in this process.
 *       Set {@code -Daltastata.services.s3gateway.enabled=true}.</li>
 *   <li><b>MCP stdio</b> — {@code --mcp-stdio}: MCP on, gRPC/S3/py4j off,
 *       logs on stderr (stdout is the MCP JSON-RPC wire).</li>
 * </ul>
 *
 * <p>Listening ports (only those whose gate is enabled actually bind):
 * <ul>
 *   <li>Armeria :9877 — gRPC, gRPC-Web, and the Web Console
 *       (gate: {@code altastata.services.grpc.enabled}).</li>
 *   <li>Micronaut HTTP :9876 — S3-compatible REST API
 *       (gate: {@code altastata.services.s3gateway.enabled}).</li>
 *   <li>py4j :25333 — Python bridge
 *       (gate: {@code altastata.services.py4j.enabled}).</li>
 *   <li>MCP — stdio JSON-RPC (gate: {@code altastata.services.mcp.enabled}).</li>
 * </ul>
 */
public final class AltaStataServicesApplication {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AltaStataServicesApplication.class);

    /**
     * Private constructor to enforce non-instantiability.
     */
    private AltaStataServicesApplication() {
        throw new AssertionError("Not instantiable");
    }

    /**
     * Main entry point for the AltaStata Services Application.
     * Registers cryptographic providers, boots Micronaut application context, and launches optional py4j server.
     *
     * @param args command-line arguments
     * @throws InterruptedException if thread sleep is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        // Register BouncyCastle once for the JVM. AltaStataFileSystem expects
        // BC to be authenticated for PEM decryption; this used to run in each
        // service's own main().
        Security.addProvider(new BouncyCastleProvider());

        ApplicationContext ctx = runMicronautContext(args);
        Runtime.getRuntime().addShutdownHook(new Thread(ctx::close, "altastata-services-ctx-shutdown"));

        GatewayServer py4j = startPy4jIfEnabled(ctx);
        if (py4j != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(py4j::shutdown, "altastata-services-py4j-shutdown"));
        }

        // Block forever — Micronaut starts its event loops in daemon threads, so without
        // a non-daemon hold the JVM would exit immediately after main() returns. The shutdown
        // hooks above stop the context (and py4j) cleanly on SIGTERM / SIGINT.
        Thread.currentThread().join();
    }

    /**
     * Boots the Micronaut {@link ApplicationContext}. In MCP stdio mode, redirects
     * {@link System#out} to {@link System#err} during context initialization so
     * Micronaut's ASCII banner does not corrupt the MCP JSON-RPC wire.
     */
    private static ApplicationContext runMicronautContext(String[] args) {
        boolean mcpStdio = "true".equalsIgnoreCase(System.getProperty("altastata.services.mcp.enabled"))
                && "stdio".equalsIgnoreCase(System.getProperty("altastata.mcp.transport", ""));
        if (mcpStdio) {
            LOGGER.info("MCP stdio mode (configured by AltaStataServicesLauncher)");
        }

        // Micronaut's ASCII banner writes to System.out and would corrupt the MCP
        // JSON-RPC wire. Park stdout on stderr for bootstrap only; MCP SDK keeps
        // its own reference to the original stdout from StdioServerTransportProvider.
        PrintStream originalOut = System.out;
        if (mcpStdio) {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    System.err.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    System.err.write(b, off, len);
                }

                @Override
                public void flush() {
                    System.err.flush();
                }
            }, true));
        }
        try {
            return Micronaut.run(AltaStataServicesApplication.class, args);
        } finally {
            if (mcpStdio) {
                System.setOut(originalOut);
            }
        }
    }

    /**
     * Starts the py4j GatewayServer on the configured port iff
     * {@code altastata.services.py4j.enabled} resolves to {@code true} via
     * the Micronaut {@link ApplicationContext} (default: true). Reading
     * through the context — instead of {@link System#getProperty} — means a
     * single env var ({@code ALTASTATA_SERVICES_PY4J_ENABLED}) flips py4j the
     * same way it flips the gRPC and S3 gates (both of which use Micronaut
     * {@code @Requires(property=...)}). Returns {@code null} when py4j is
     * disabled so callers can skip registering shutdown hooks.
     */
    private static GatewayServer startPy4jIfEnabled(ApplicationContext ctx) {
        boolean enabled = ctx.getProperty(
                        "altastata.services.py4j.enabled", Boolean.class)
                .orElse(true);
        if (!enabled) {
            LOGGER.info("py4j disabled via altastata.services.py4j.enabled=false");
            return null;
        }
        int port = ctx.getProperty("py4j.port", Integer.class).orElse(25333);
        // Entry-point object is null — Python-side picks up the JVM-wide
        // AccountRegistry via static accessor, not via py4j entry-point reference.
        GatewayServer gw = new GatewayServer(null, port);
        gw.start();
        LOGGER.info("py4j gateway listening on port {}", port);
        return gw;
    }
}
