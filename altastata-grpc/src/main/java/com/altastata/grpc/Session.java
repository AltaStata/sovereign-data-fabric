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

import java.time.Duration;
import java.time.Instant;

/**
 * One opaque-token session in the gRPC gateway. A session is the application-layer
 * proof that one client (typically one browser tab) has successfully completed
 * {@code AuthService.Login}.
 *
 * <p>Multiple sessions for the same {@code accountKey} share the underlying Java
 * {@link com.altastata.api.AltaStataFileSystem} held in {@link GrpcUserRegistry};
 * each session is its own gRPC-layer identity with its own TTL, its own
 * {@code Watch} stream, and its own {@code Logout}.
 *
 * <p>Mutated only through {@link SessionRegistry} so the volatile fields here are
 * read-after-write safe across the auth interceptor and the sweeper threads.
 */
final class Session {
    private final String token;
    private final String accountKey;
    private final String clientHint;
    private final Instant createdAt;

    private volatile Instant expiresAt;
    private volatile Instant lastSeenAt;

    Session(String token, String accountKey, String clientHint, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.accountKey = accountKey;
        this.clientHint = clientHint;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastSeenAt = createdAt;
    }

    /**
     * Gets the unique opaque bearer session token string.
     *
     * @return opaque token
     */
    String token() {
        return token;
    }

    /**
     * Gets the {@link com.altastata.api.AccountId#key()} this session authenticates
     * — not a bare {@code myuser}.
     *
     * @return account key
     */
    String accountKey() {
        return accountKey;
    }

    /**
     * Gets the optional client hint description string (e.g., user-agent representation).
     *
     * @return client hint string
     */
    String clientHint() {
        return clientHint;
    }

    /**
     * Gets the creation timestamp of this session.
     *
     * @return creation instant timestamp
     */
    Instant createdAt() {
        return createdAt;
    }

    /**
     * Gets the current absolute expiration timestamp of this session.
     *
     * @return expiration instant timestamp
     */
    Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Gets the last touch activity timestamp of this session.
     *
     * @return last seen instant timestamp
     */
    Instant lastSeenAt() {
        return lastSeenAt;
    }

    /**
     * Determines whether the given instant has passed this session's absolute expiration.
     *
     * @param now target verification instant
     * @return true if session has expired; false otherwise
     */
    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Slide the expiry forward; called on every successful auth-resolved RPC. */
    void touch(Instant now, Duration ttl) {
        this.lastSeenAt = now;
        this.expiresAt = now.plus(ttl);
    }
}
