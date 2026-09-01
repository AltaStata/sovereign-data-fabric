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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Unit tests for {@link SessionRegistry}. The sweeper is disabled in all tests
 * so that {@link SessionRegistry#sweep()} can be invoked deterministically; the
 * production scheduling itself is exercised live in integration / running the
 * gateway.
 */
class SessionRegistryTest {

    /** Mutable {@link Clock} so we can simulate time passing in deterministic tests. */
    private static final class TickingClock extends Clock {
        private Instant now;

        TickingClock(Instant start) {
            this.now = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static SessionRegistry registry(Duration ttl, TickingClock clock) {
        return new SessionRegistry(ttl, clock, false);
    }

    @Test
    void createMintsTokenWithSessPrefixAndStoresSession() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        Session s = r.create("bob", "Chrome 121");

        Assertions.assertTrue(s.token().startsWith("sess-"),
                "token must start with sess- prefix");
        Assertions.assertEquals("bob", s.accountKey());
        Assertions.assertEquals("Chrome 121", s.clientHint());
        Assertions.assertEquals(clock.instant(), s.createdAt());
        Assertions.assertEquals(clock.instant().plus(Duration.ofHours(8)), s.expiresAt());
        Assertions.assertEquals(1, r.size());
    }

    @Test
    void resolveReturnsSessionAndSlidesExpiryForward() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        Session created = r.create("bob", "");
        Instant initialExpiry = created.expiresAt();

        // Activity 5 hours in.
        clock.advance(Duration.ofHours(5));
        Optional<Session> resolved = r.resolve(created.token());

        Assertions.assertTrue(resolved.isPresent());
        Assertions.assertSame(created, resolved.get(),
                "resolve must return the same Session instance, not a copy");
        Assertions.assertTrue(resolved.get().expiresAt().isAfter(initialExpiry),
                "resolve on an active token must slide expiresAt forward");
        Assertions.assertEquals(clock.instant().plus(Duration.ofHours(8)),
                resolved.get().expiresAt(),
                "expiresAt must be exactly now + ttl after a successful resolve");
    }

    @Test
    void resolveTreatsExpiredTokenAsMissAndDropsItFromTheRegistry() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofMinutes(30), clock);

        Session s = r.create("bob", "");
        clock.advance(Duration.ofMinutes(31));   // past TTL

        Assertions.assertTrue(r.resolve(s.token()).isEmpty(),
                "expired token must resolve as empty");
        Assertions.assertEquals(0, r.size(),
                "expired token must be lazily removed from the registry on resolve");
    }

    @Test
    void resolveRejectsNullBlankAndUnknownPrefixedTokens() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);
        r.create("bob", "");

        Assertions.assertTrue(r.resolve(null).isEmpty(), "null token must resolve empty");
        Assertions.assertTrue(r.resolve("").isEmpty(), "empty token must resolve empty");
        Assertions.assertTrue(r.resolve("local-bob").isEmpty(),
                "non-sess- prefixed tokens must not resolve via SessionRegistry");
        Assertions.assertTrue(r.resolve("sess-totally-unknown").isEmpty(),
                "unknown sess- token must resolve empty");
    }

    @Test
    void invalidateRemovesOneSessionImmediately() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);
        Session a = r.create("bob", "");
        Session b = r.create("bob", "");

        Assertions.assertTrue(r.invalidate(a.token()));
        Assertions.assertFalse(r.invalidate(a.token()), "second invalidate must be a no-op");
        Assertions.assertTrue(r.resolve(a.token()).isEmpty(), "invalidated session must not resolve");
        Assertions.assertTrue(r.resolve(b.token()).isPresent(), "other sessions must not be affected");
        Assertions.assertEquals(1, r.size());
    }

    @Test
    void invalidateUserDropsAllSessionsForThatUserOnly() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);
        Session bob1 = r.create("bob", "tab1");
        Session bob2 = r.create("bob", "tab2");
        Session alice = r.create("alice", "tab1");

        int removed = r.invalidateAccount("bob");

        Assertions.assertEquals(2, removed);
        Assertions.assertTrue(r.resolve(bob1.token()).isEmpty());
        Assertions.assertTrue(r.resolve(bob2.token()).isEmpty());
        Assertions.assertTrue(r.resolve(alice.token()).isPresent(),
                "invalidateUser must not affect other users' sessions");
        Assertions.assertEquals(1, r.size());
    }

    @Test
    void sweepRemovesExpiredSessionsAndKeepsActiveOnes() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofMinutes(30), clock);
        Session early = r.create("bob", "");

        clock.advance(Duration.ofMinutes(20));
        Session active = r.create("alice", "");

        clock.advance(Duration.ofMinutes(15));   // early=35min (expired), active=15min (alive)
        r.sweep();

        Assertions.assertEquals(1, r.size());
        Assertions.assertTrue(r.resolve(early.token()).isEmpty(),
                "sweep must drop expired sessions");
        Assertions.assertTrue(r.resolve(active.token()).isPresent(),
                "sweep must keep active sessions");
    }

    @Test
    void issuedTokensAreUniqueAcrossManyCreates() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            Session s = r.create("user-" + i, "");
            Assertions.assertTrue(seen.add(s.token()),
                    "duplicate session token issued: " + s.token());
            Assertions.assertTrue(s.token().length() > "sess-".length() + 30,
                    "token must carry meaningful entropy, got " + s.token());
        }
    }

    @Test
    void clientHintNullIsCoercedToEmptyString() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        Session s = r.create("bob", null);

        Assertions.assertNotNull(s.clientHint());
        Assertions.assertEquals("", s.clientHint());
    }

    @Test
    void findActiveByAccountAndClientReturnsTheMatchingLiveSession() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        Session uiTab = r.create("bob", "altastata-console-web");
        Session pyKernel = r.create("bob", "altastata-python-package");

        Assertions.assertEquals(uiTab.token(),
                r.findActiveByAccountAndClient("bob", "altastata-console-web").get().token(),
                "must return the session whose clientHint matches");
        Assertions.assertEquals(pyKernel.token(),
                r.findActiveByAccountAndClient("bob", "altastata-python-package").get().token());
        Assertions.assertTrue(
                r.findActiveByAccountAndClient("alice", "altastata-console-web").isEmpty(),
                "must not match a different user");
        Assertions.assertTrue(
                r.findActiveByAccountAndClient("bob", "unknown-client").isEmpty());
    }

    @Test
    void findActiveByAccountAndClientPicksTheMostRecentlyTouchedAmongDuplicates() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        // Two zombies with the same (user, clientHint) - exactly the bug that
        // AuthService.Login eviction is meant to clear out.
        Session older = r.create("bob", "altastata-console-web");
        clock.advance(Duration.ofMinutes(10));
        Session newer = r.create("bob", "altastata-console-web");

        Optional<Session> hit = r.findActiveByAccountAndClient("bob", "altastata-console-web");
        Assertions.assertTrue(hit.isPresent());
        Assertions.assertEquals(newer.token(), hit.get().token(),
                "must return the most-recently-created/touched session");
        Assertions.assertNotEquals(older.token(), hit.get().token());
    }

    @Test
    void findActiveByAccountAndClientSkipsExpiredEntries() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofMinutes(30), clock);
        r.create("bob", "altastata-console-web");

        clock.advance(Duration.ofMinutes(45));   // past TTL

        Assertions.assertTrue(
                r.findActiveByAccountAndClient("bob", "altastata-console-web").isEmpty(),
                "expired session must not be reported as active");
    }

    @Test
    void findActiveByAccountAndClientNormalisesNullClientHintToEmptyString() {
        TickingClock clock = new TickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionRegistry r = registry(Duration.ofHours(8), clock);

        Session s = r.create("bob", null);

        Assertions.assertEquals(s.token(),
                r.findActiveByAccountAndClient("bob", null).get().token());
        Assertions.assertEquals(s.token(),
                r.findActiveByAccountAndClient("bob", "").get().token(),
                "null and empty must be treated as the same logical client");
    }
}
