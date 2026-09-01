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

import io.grpc.Context;

public final class GrpcGatewayAuthContext {
    /**
     * Private constructor to prevent instantiation of authentication context utility class.
     */
    private GrpcGatewayAuthContext() {}

    public static final Context.Key<GrpcUserData> USER_DATA = Context.key("altastata.userData");
    public static final Context.Key<String> ACCOUNT_KEY = Context.key("altastata.accountKey");

    /**
     * The {@link Session} resolved from the request's {@code sess-...} bearer
     * token (see {@link SessionRegistry}). Always present on authenticated
     * RPCs now that the legacy {@code local-...} and {@code access-...}
     * bearer paths have been removed; used by AuthService.Logout / Refresh
     * to identify the calling session.
     */
    public static final Context.Key<Session> SESSION = Context.key("altastata.session");
}
