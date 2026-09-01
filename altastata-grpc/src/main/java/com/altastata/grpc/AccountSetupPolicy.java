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

package com.altastata.grpc;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Guards bootstrap {@code AccountSetupService.GenerateKeys} per
 * {@code CONSOLE_ACCOUNT_SETUP_DESIGN.md §8.1}: loopback bind by default,
 * or {@code altastata.local-mode.allow-account-setup=true}.
 */
final class AccountSetupPolicy {

    private final boolean accountSetupPermitted;

    AccountSetupPolicy() {
        this(isAccountSetupPermitted());
    }

    AccountSetupPolicy(boolean accountSetupPermitted) {
        this.accountSetupPermitted = accountSetupPermitted;
    }

    /**
     * Enforces that account setup is allowed on this gateway, throwing a SecurityException if it is disabled.
     */
    void requireAccountSetupPermitted() {
        if (!accountSetupPermitted) {
            throw new SecurityException(
                    "AccountSetupService.GenerateKeys is disabled on this gateway; "
                            + "set altastata.local-mode.allow-account-setup=true "
                            + "or bind to loopback");
        }
    }

    /**
     * Checks if account setup operations are permitted based on configuration flags or loopback bindings.
     *
     * @return true if permitted; false otherwise
     */
    static boolean isAccountSetupPermitted() {
        if (isAllowAccountSetupFlag()) {
            return true;
        }
        String bind = System.getenv("ALTASTATA_GRPC_BIND_ADDRESS");
        if (bind == null || bind.trim().isEmpty()) {
            return true;
        }
        bind = bind.trim();
        if ("127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind) || "::1".equals(bind)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(bind);
            return address.isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolves the allow-account-setup configuration flag from system properties or environment variables.
     *
     * @return true if explicitly enabled; false otherwise
     */
    private static boolean isAllowAccountSetupFlag() {
        String prop = System.getProperty("altastata.local-mode.allow-account-setup");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        String env = System.getenv("ALTASTATA_LOCAL_MODE_ALLOW_ACCOUNT_SETUP");
        return env != null && Boolean.parseBoolean(env);
    }
}
