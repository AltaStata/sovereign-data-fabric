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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;

import py4j.GatewayServer;

/**
 * Py4J Gateway Entry Point class to expose AltaStata FileSystem APIs to Python clients.
 *
 * This class acts as a bridge, running a Py4J GatewayServer and allowing Python scripts to
 * interact natively with an instantiated {@link AltaStataFileSystem} instance.
 */
public class AltaStataFileSystemEntryPoint {

    private AltaStataFileSystem altaStataFileSystem;

    /**
     * Constructs a new AltaStataFileSystemEntryPoint initialized with the specified account directory.
     *
     * @param accountDirPath the local path to the user's account configuration directory
     */
    public AltaStataFileSystemEntryPoint(String accountDirPath) {
        // AltaStataFileSystem constructors are package-private; route through
        // AccountRegistry (ALTASTATA_SERVICES_UBER_DESIGN.md §4).
        altaStataFileSystem = AccountRegistry.getOrCreateFromDir(accountDirPath);
    }

    /**
     * Gets the active AltaStataFileSystem instance.
     *
     * @return the active AltaStataFileSystem instance
     */
    public AltaStataFileSystem getAltaStataFileSystem() {
        return altaStataFileSystem;
    }

    /**
     * Main entry point to launch the Py4J GatewayServer.
     * Exposes the gateway on localhost for Python process communication.
     *
     * @param args command-line arguments where args[0] is the local path of the account directory
     */
    public static void main(String[] args) {
        GatewayServer gatewayServer = new GatewayServer(new AltaStataFileSystemEntryPoint(args[0]));
        gatewayServer.start();
        System.out.println("Gateway Server Started for: " + args[0]);
    }

}
