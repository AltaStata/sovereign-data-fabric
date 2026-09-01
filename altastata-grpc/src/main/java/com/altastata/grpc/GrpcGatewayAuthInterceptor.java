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
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * gRPC server interceptor that turns a Bearer token in the {@code Authorization}
 * header into an authenticated {@link GrpcUserData} on the request {@link Context}.
 *
 * <p>The only accepted token format is {@code sess-<random>} — an opaque
 * session token issued by {@code AuthService.LoginV2} and resolved through
 * {@link SessionRegistry} (sliding TTL on every successful resolve).
 *
 * <p>Bootstrap RPCs ({@link #isBootstrapMethod(String)}) skip auth so clients
 * can call {@code LoginV2}, {@code GenerateKeys}, and
 * {@code GetSupportedAccountTypes} without a prior session.
 */
@Singleton
public class GrpcGatewayAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final GrpcUserRegistry registry;
    private final SessionRegistry sessionRegistry;

    /**
     * Constructs the GrpcGatewayAuthInterceptor with specified user and session registries.
     *
     * @param registry user profiles registry
     * @param sessionRegistry bearer tokens session registry
     */
    public GrpcGatewayAuthInterceptor(GrpcUserRegistry registry, SessionRegistry sessionRegistry) {
        this.registry = registry;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        String authorization = headers.get(AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            if (token.startsWith(SessionRegistry.TOKEN_PREFIX)) {
                Optional<Session> resolved = sessionRegistry.resolve(token);
                if (resolved.isPresent()) {
                    Session session = resolved.get();
                    String accountKey = session.accountKey();
                    GrpcUserData userData = registry.getByAccountKey(accountKey);
                    if (userData != null) {
                        Context context = Context.current()
                                .withValue(GrpcGatewayAuthContext.USER_DATA, userData)
                                .withValue(GrpcGatewayAuthContext.ACCOUNT_KEY, accountKey)
                                .withValue(GrpcGatewayAuthContext.SESSION, session);
                        return Contexts.interceptCall(context, call, headers, next);
                    }
                }
            }
        }

        if (isBootstrapMethod(fullMethodName)) {
            return next.startCall(call, headers);
        }

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing Bearer token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
        return new ServerCall.Listener<>() {};
    }

    /**
     * Determines whether the specified gRPC method is a public bootstrap method that does not require
     * active session authentication.
     *
     * @param fullMethodName full method path descriptor name
     * @return true if it is a bootstrap method; false otherwise
     */
    private boolean isBootstrapMethod(String fullMethodName) {
        return "altastata.v1.AuthService/LoginV2".equals(fullMethodName)
                || "altastata.v1.AccountSetupService/GenerateKeys".equals(fullMethodName)
                || "altastata.v1.AccountSetupService/ChangePassword".equals(fullMethodName)
                || "altastata.v1.AccountSetupService/GetSupportedAccountTypes".equals(fullMethodName);
    }
}
