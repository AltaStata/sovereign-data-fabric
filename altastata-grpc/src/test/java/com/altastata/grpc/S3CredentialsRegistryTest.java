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

import com.altastata.api.AccountId;
import com.altastata.api.AltaStataFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3CredentialsRegistryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private SessionRegistry sessions;
    private S3CredentialsRegistry registry;

    @BeforeEach
    void setUp() {
        sessions = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false);
        registry = new S3CredentialsRegistry(sessions, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void issueAndResolveReturnsFilesystemAndSecret() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Session session = sessions.create("bob", "tab");

        S3CredentialsRegistry.IssuedCredential issued = registry.issue(
                session.token(), "bob", fs, "etl");

        Optional<S3CredentialsRegistry.S3ResolveResult> resolved =
                registry.resolveForS3(issued.accessKeyId());

        assertTrue(resolved.isPresent());
        assertEquals(fs, resolved.get().fileSystem());
        assertEquals("bob", resolved.get().userName());
        assertEquals(issued.secretAccessKey(), resolved.get().secretAccessKey());
    }

    @Test
    void resolveFailsAfterSessionInvalidated() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Session session = sessions.create("bob", "tab");
        S3CredentialsRegistry.IssuedCredential issued = registry.issue(
                session.token(), "bob", fs, "");

        sessions.invalidate(session.token());

        assertTrue(registry.resolveForS3(issued.accessKeyId()).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void revokeAllForSessionRemovesOnlyMatchingCredentials() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Session bob = sessions.create("bob", "a");
        Session alice = sessions.create("alice", "b");

        S3CredentialsRegistry.IssuedCredential bobCred = registry.issue(
                bob.token(), "bob", fs, "");
        registry.issue(alice.token(), "alice", fs, "");

        registry.revokeAllForSession(bob.token());

        assertTrue(registry.resolveForS3(bobCred.accessKeyId()).isEmpty());
        assertEquals(1, registry.size());
    }

    @Test
    void revokeForSessionRejectsForeignSession() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Session owner = sessions.create("bob", "tab");
        Session other = sessions.create("alice", "tab");
        S3CredentialsRegistry.IssuedCredential issued = registry.issue(
                owner.token(), "bob", fs, "");

        assertFalse(registry.revokeForSession(other.token(), issued.accessKeyId()));
        assertTrue(registry.resolveForS3(issued.accessKeyId()).isPresent());
    }

    @Test
    void listForSessionTokenReturnsOnlyOwnedCredentials() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        Session bob = sessions.create("bob", "a");
        Session alice = sessions.create("alice", "b");

        registry.issue(bob.token(), "bob", fs, "one");
        registry.issue(bob.token(), "bob", fs, "two");
        registry.issue(alice.token(), "alice", fs, "other");

        assertEquals(2, registry.listForSessionToken(bob.token()).size());
        assertEquals(1, registry.listForSessionToken(alice.token()).size());
    }
}
