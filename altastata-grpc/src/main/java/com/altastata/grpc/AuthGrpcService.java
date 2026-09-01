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

import com.altastata.grpc.proto.AuthServiceGrpc;
import com.altastata.grpc.proto.LoginV2Request;
import com.altastata.grpc.proto.LoginV2Response;
import com.altastata.grpc.proto.LogoutRequest;
import com.altastata.grpc.proto.LogoutResponse;
import com.altastata.grpc.proto.RefreshRequest;
import com.altastata.grpc.proto.RefreshResponse;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * gRPC implementation of {@code AuthService} (see {@code auth.proto}).
 *
 * <p>{@code LoginV2} validates the password through
 * {@link GrpcUserRegistry#installFromLoginV2} and issues a per-tab
 * {@code sess-<random>} token via {@link SessionRegistry}.
 *
 * <p>Design reference: {@code altastata-grpc/CONSOLE_ACCOUNT_SETUP_DESIGN.md §5.2}.
 */
/**
 * gRPC Service handling user authentication and token exchange.
 * 
 * Provides login flows (including multi-factor authentication, PKCS#11, and IBM Cloud HPCS),
 * token refreshing, and session management. It interfaces tightly with `AccountRegistry` to
 * load cryptographic materials (e.g., RSA/Kyber keys) after a successful login.
 */
@Singleton
public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(AuthGrpcService.class);

    private final GrpcUserRegistry registry;
    private final SessionRegistry sessionRegistry;
    private final EventBus eventBus;
    private final S3CredentialsRegistry s3CredentialsRegistry;
    private final LoginV2Support loginV2Support;

    public AuthGrpcService(GrpcUserRegistry registry,
                           SessionRegistry sessionRegistry,
                           EventBus eventBus,
                           S3CredentialsRegistry s3CredentialsRegistry) {
        this(registry, sessionRegistry, eventBus, s3CredentialsRegistry, new LoginV2Support());
    }

    AuthGrpcService(GrpcUserRegistry registry, SessionRegistry sessionRegistry, EventBus eventBus) {
        this(registry, sessionRegistry, eventBus, null, new LoginV2Support());
    }

    AuthGrpcService(GrpcUserRegistry registry,
                    SessionRegistry sessionRegistry,
                    EventBus eventBus,
                    LoginV2Support loginV2Support) {
        this(registry, sessionRegistry, eventBus, null, loginV2Support);
    }

    AuthGrpcService(GrpcUserRegistry registry,
                    SessionRegistry sessionRegistry,
                    EventBus eventBus,
                    S3CredentialsRegistry s3CredentialsRegistry,
                    LoginV2Support loginV2Support) {
        this.registry = registry;
        this.sessionRegistry = sessionRegistry;
        this.eventBus = eventBus;
        this.s3CredentialsRegistry = s3CredentialsRegistry;
        this.loginV2Support = loginV2Support;
    }

    /**
     * Terminates the current active session, invalidates the session token, and revokes any associated S3 gateway credentials.
     *
     * @param request the logout request
     * @param responseObserver stream observer to send the LogoutResponse
     */
    @Override
    public void logout(LogoutRequest request, StreamObserver<LogoutResponse> responseObserver) {
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        if (session != null) {
            boolean removed = sessionRegistry.invalidate(session.token());
            if (removed) {
                revokeS3Credentials(session.token());
                logger.warn("Logout: invalidated session for account={}", session.accountKey());
            }
        }
        responseObserver.onNext(LogoutResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Authenticates a user (Login V2), setups dynamic cryptography parameters on the registry,
     * invalidates any prior conflicting sessions, and issues a new bearer session token.
     *
     * @param request the detailed login request
     * @param responseObserver stream observer to send the LoginV2Response
     */
    @Override
    public void loginV2(LoginV2Request request, StreamObserver<LoginV2Response> responseObserver) {
        try {
            LoginV2Support.Resolved resolved = loginV2Support.resolve(request);
            // Full AccountId — same myuser on different clouds must not collide.
            String accountKey = resolved.accountId().key();

            registry.installFromLoginV2(
                    accountKey,
                    resolved.userProperties(),
                    resolved.privateKeyPemForValidator(),
                    request.getPassword(),
                    resolved.fileSystemFactory());

            sessionRegistry
                    .findActiveByAccountAndClient(accountKey, request.getClientHint())
                    .ifPresent(prior -> evictPriorSession(prior, "LoginV2"));

            Session session = sessionRegistry.create(accountKey, request.getClientHint());
            logger.warn("LoginV2: issued session for account={} myuser={} (clientHint='{}')",
                    accountKey,
                    resolved.accountId().getMyUser(),
                    request.getClientHint() == null ? "" : request.getClientHint());
            responseObserver.onNext(LoginV2Response.newBuilder()
                    .setSessionToken(session.token())
                    .setExpiresAt(toProto(session.expiresAt()))
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SecurityException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            logger.warn("LoginV2: rejected (cause: {})", e.getClass().getSimpleName());
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Invalid credentials")
                    .asRuntimeException());
        }
    }

    /**
     * Refreshes the expiration timestamp of an active session.
     *
     * @param request the session refresh request
     * @param responseObserver stream observer to send the RefreshResponse
     */
    @Override
    public void refresh(RefreshRequest request, StreamObserver<RefreshResponse> responseObserver) {
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        if (session == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No active session token; only sess-... tokens can be refreshed")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(RefreshResponse.newBuilder()
                .setExpiresAt(toProto(session.expiresAt()))
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Evicts a prior active session when a newer login occurs from the same client, revoking S3 credentials
     * and closing any active event listeners.
     *
     * @param prior the prior active session
     * @param source the source triggering the eviction
     */
    private void evictPriorSession(Session prior, String source) {
        sessionRegistry.invalidate(prior.token());
        revokeS3Credentials(prior.token());
        int closed = eventBus.evictSession(
                prior.token(),
                "Session evicted by newer " + source + " from same client");
        logger.warn("{}: evicted prior session for account={} clientHint='{}' (watchClosed={})",
                source,
                prior.accountKey(),
                prior.clientHint(),
                closed);
    }

    /**
     * Revokes all active S3 gateway credentials issued for the specified session token.
     *
     * @param sessionToken the active session token to invalidate credentials for
     */
    private void revokeS3Credentials(String sessionToken) {
        if (s3CredentialsRegistry != null) {
            s3CredentialsRegistry.revokeAllForSession(sessionToken);
        }
    }

    /**
     * Converts a Java Instant timestamp to a gRPC Protobuf Timestamp representation.
     *
     * @param instant the source Instant
     * @return constructed Protobuf Timestamp
     */
    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
