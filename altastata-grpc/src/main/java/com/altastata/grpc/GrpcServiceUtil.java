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

import com.altastata.api.AltaStataFileSystem;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public final class GrpcServiceUtil {
    /**
     * Private constructor to prevent instantiation of static helper class.
     */
    private GrpcServiceUtil() {}

    /**
     * Resolves the current GrpcUserData on the active gRPC Context, throwing unauthenticated if missing.
     *
     * @return current authenticated user data
     */
    public static GrpcUserData currentUserData() {
        GrpcUserData userData = GrpcGatewayAuthContext.USER_DATA.get(Context.current());
        if (userData == null) {
            throw unauthenticated("No authenticated user context");
        }
        return userData;
    }

    /**
     * Resolves the current AltaStataFileSystem context instance, throwing a failed precondition if missing.
     *
     * @return current active filesystem
     */
    public static AltaStataFileSystem currentFileSystem() {
        GrpcUserData userData = currentUserData();
        AltaStataFileSystem fs = userData.getAltaStataFileSystem();
        if (fs == null) {
            throw failedPrecondition("User is not initialized. Call setPassword first.");
        }
        return fs;
    }

    /**
     * Resolves the current Session context instance, throwing unauthenticated if missing.
     *
     * @return current active session
     */
    public static Session currentSession() {
        Session session = GrpcGatewayAuthContext.SESSION.get(Context.current());
        if (session == null) {
            throw unauthenticated("No authenticated session context");
        }
        return session;
    }

    /**
     * Builds a standard gRPC StatusRuntimeException for unauthenticated errors.
     *
     * @param message error description
     * @return unauthenticated exception
     */
    public static StatusRuntimeException unauthenticated(String message) {
        return Status.UNAUTHENTICATED.withDescription(message).asRuntimeException();
    }

    /**
     * Builds a standard gRPC StatusRuntimeException for failed preconditions.
     *
     * @param message error description
     * @return failed precondition exception
     */
    public static StatusRuntimeException failedPrecondition(String message) {
        return Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException();
    }

    /**
     * Normalizes and validates path syntax constraints to block directory traversals or redundant slashes.
     *
     * @param path target path to check
     */
    public static void validatePath(String path) {
        if (path != null && (path.contains("..") || path.contains("//") || path.contains("./"))) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid path structure").asRuntimeException();
        }
    }
}
