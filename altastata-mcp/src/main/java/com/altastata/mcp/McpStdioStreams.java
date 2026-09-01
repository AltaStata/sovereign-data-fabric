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

import java.io.InputStream;
import java.io.PrintStream;

/**
 * Captures the real process stdout/stdin before Micronaut bootstrap redirects
 * {@link System#out} (banner / noisy libraries). The MCP stdio transport must
 * keep writing JSON-RPC to the original stdout.
 */
public final class McpStdioStreams {

    private static volatile InputStream stdin = System.in;
    private static volatile PrintStream stdout = System.out;

    private McpStdioStreams() {
    }

    /** Call once at process start, before any {@code System.setOut}. */
    public static void capture() {
        stdin = System.in;
        stdout = System.out;
    }

    public static InputStream stdin() {
        return stdin;
    }

    public static PrintStream stdout() {
        return stdout;
    }
}
