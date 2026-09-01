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

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory registry of opaque session tokens issued by {@code AuthService.Login}.
 *
 * <p>Each session is a per-tab gRPC-layer identity that resolves to a single
 * {@code accountKey}. Sessions for the same account share the underlying Java
 * {@link com.altastata.api.AltaStataFileSystem} held in {@link GrpcUserRegistry}
 * (see {@code SESSION_AND_EVENTS_DESIGN.md §2 / §3}); they only differ in their
 * gRPC-layer lifecycle: each can be invalidated independently via {@code Logout},
 * each has its own sliding-TTL expiry.
 *
 * <p>Sliding TTL: every successful {@link #resolve} call pushes {@code expiresAt}
 * forward by {@code ttl} from {@code now}. Idle sessions expire at {@code now + ttl}.
 *
 * <p>Configuration: the TTL comes from {@code grpcgateway.session.ttl} (Micronaut
 * config) or {@code ALTASTATA_GRPC_SESSION_TTL} (environment), as an ISO-8601
 * duration. Default {@code PT8H} (8 hours).
 *
 * <p>State is in-memory only — restarting the JVM invalidates every active session.
 * That is acceptable for the single-host Phase 1/2 deployment targeted by
 * {@code SESSION_AND_EVENTS_DESIGN.md}; multi-host HA is a Phase 3 concern.
 */
@Singleton
public class SessionRegistry {
    static final String TOKEN_PREFIX = "sess-";

    private static final Logger logger = LoggerFactory.getLogger(SessionRegistry.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final ScheduledExecutorService sweeper;

    /**
      * Constructs a new SessionRegistry.
      * @param ttl session time-to-live
      */
    public SessionRegistry(@Value("${grpcgateway.session.ttl:PT8H}") Duration ttl) {
        this(ttl, Clock.systemUTC(), true);
    }

    /**
     * Test seam: package-private constructor with injectable {@link Clock} and the
     * option to disable the periodic sweeper (so tests can call {@link #sweep()}
     * deterministically). Production wiring uses the public no-arg constructor.
     */
    SessionRegistry(Duration ttl, Clock clock, boolean enableSweeper) {
        this.ttl = ttl;
        this.clock = clock;
        if (enableSweeper) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "altastata-session-sweeper");
                t.setDaemon(true);
                return t;
            });
            long ms = SWEEP_INTERVAL.toMillis();
            sweeper.scheduleWithFixedDelay(this::sweep, ms, ms, TimeUnit.MILLISECONDS);
            logger.info("SessionRegistry: ttl={}, sweep every {}", ttl, SWEEP_INTERVAL);
        } else {
            this.sweeper = null;
        }
    }

    /**
     * Gracefully halts the background sweeper thread pool.
     */
    @PreDestroy
    void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /** Mint a new session for {@code accountKey}. The returned token is the only handle. */
    public Session create(String accountKey, String clientHint) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Session s = new Session(token, accountKey, clientHint == null ? "" : clientHint, now, now.plus(ttl));
        byToken.put(token, s);
        return s;
    }

    /**
     * Look up the most-recently-touched <em>active</em> session for the given
     * {@code (userName, clientHint)} pair, ignoring expired entries. Used by
     * {@code AuthService.Login} to evict the prior session of the same logical
     * client before issuing a fresh token: a single browser tab (or any other
     * caller using a stable {@code clientHint}) only ever owns one live session
     * at a time, so a re-Login from the same tab kills the zombie {@code Watch}
     * stream that the previous session left dangling on the server.
     *
     * <p>Sessions are equal under {@code (accountKey, clientHint)} on purpose:
     * two browser tabs sharing the default {@code clientHint=altastata-console-web}
     * will evict each other on Login. To run two tabs side-by-side, the
     * frontend should append a per-tab id to {@code clientHint} (e.g.
     * {@code "altastata-console-web/<tabId>"}) so they live in distinct keys.
     *
     * <p>Empty / null {@code clientHint} is normalised to {@code ""} so it
     * still de-duplicates correctly: two anonymous clients of the same account
     * <em>do</em> evict each other, which is the safe default.
     */
    public Optional<Session> findActiveByAccountAndClient(String accountKey, String clientHint) {
        if (accountKey == null || accountKey.isEmpty()) {
            return Optional.empty();
        }
        String hint = clientHint == null ? "" : clientHint;
        Instant now = clock.instant();
        Session best = null;
        for (Session s : byToken.values()) {
            if (!accountKey.equals(s.accountKey())) continue;
            if (!hint.equals(s.clientHint())) continue;
            if (s.isExpired(now)) continue;
            if (best == null || s.lastSeenAt().isAfter(best.lastSeenAt())) {
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Look up a session by its token, slide expiry forward on a hit, and remove
     * expired entries lazily. Returns {@link Optional#empty()} for unknown
     * tokens, malformed tokens, or expired tokens.
     */
    public Optional<Session> resolve(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Session s = byToken.get(token);
        if (s == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (s.isExpired(now)) {
            byToken.remove(token, s);
            return Optional.empty();
        }
        s.touch(now, ttl);
        return Optional.of(s);
    }

    /** Drop one session. Returns {@code true} if a session was actually removed. */
    public boolean invalidate(String token) {
        return token != null && byToken.remove(token) != null;
    }

    /**
     * Active session tokens for {@code accountKey}. Used when revoking every
     * session-owned resource (S3 credentials, Watch streams) on account delete.
     */
    public java.util.List<String> tokensForAccount(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) {
            return java.util.List.of();
        }
        Instant now = clock.instant();
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (Session s : byToken.values()) {
            if (accountKey.equals(s.accountKey()) && !s.isExpired(now)) {
                tokens.add(s.token());
            }
        }
        return tokens;
    }

    /**
     * Drop every session that resolves to {@code accountKey}. Used for "Logout
     * everywhere" / admin revoke flows. Returns the number of sessions removed.
     */
    public int invalidateAccount(String accountKey) {
        if (accountKey == null) {
            return 0;
        }
        int n = 0;
        for (Iterator<Session> it = byToken.values().iterator(); it.hasNext(); ) {
            if (accountKey.equals(it.next().accountKey())) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    /** Visible for tests; also invoked periodically by the sweeper. */
    void sweep() {
        Instant now = clock.instant();
        byToken.values().removeIf(s -> s.isExpired(now));
    }

    /** Visible for tests. */
    int size() {
        return byToken.size();
    }
}
