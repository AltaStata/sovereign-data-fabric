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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GrpcGatewayAuthInterceptor}'s token-prefix dispatch.
 *
 * <p>After the cleanup that removed the {@code local-<userName>} and
 * {@code access-<accessKey>} bearer paths, the interceptor accepts only
 * {@code sess-<token>} (issued by {@code AuthService.LoginV2}) and bootstrap RPCs.
 */
class GrpcGatewayAuthInterceptorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    /** Auth wiring: real {@link GrpcUserRegistry} + real {@link SessionRegistry}. */
    private static final class Wiring {
        final GrpcUserRegistry users;
        final SessionRegistry sessions;
        final GrpcGatewayAuthInterceptor interceptor;

        Wiring() {
            users = new GrpcUserRegistry();
            sessions = new SessionRegistry(Duration.ofHours(8),
                    Clock.fixed(T0, ZoneOffset.UTC), false);
            interceptor = new GrpcGatewayAuthInterceptor(users, sessions);
        }

        /** Bootstrap a "logged-in" user with an installed live fs and accessKey. */
        GrpcUserData bootstrap(String userName) {
            // Build a synthetic but valid identity for this userName so
            // that AccountId.fromUserProperties succeeds inside
            // installFromLoginV2. Two different userNames must
            // resolve to different AccountIds so the interceptor test
            // suite can switch users without picking up the previous
            // mock's fs by accident.
            com.altastata.api.AccountId id = new com.altastata.api.AccountId(
                    "altastata-test-" + userName + "-",
                    userName,
                    "amazon-s3-secure");
            String props =
                    "acccontainer-prefix=" + id.getContainerPrefix() + "\n"
                  + "myuser=" + id.getMyUser() + "\n"
                  + "accounttype=" + id.getAccountType() + "\n";

            // Pre-populate the JVM-wide static AccountRegistry with a
            // fresh mock fs that self-reports {@code id}. The production
            // code path in installFromLoginV2 derives the same
            // AccountId from {@code props} and retrieves the mock
            // without invoking the (package-private)
            // AltaStataFileSystem constructor.
            AltaStataFileSystem mockFs = mock(AltaStataFileSystem.class);
            when(mockFs.getAccountId()).thenReturn(id);
            AccountRegistry.putForTesting(id, mockFs);

            users.installFromLoginV2(userName, props, "key", "p", () -> mockFs);
            return users.getByAccountKey(userName);
        }
    }

    /** Captures the request context and headers passed into the next handler. */
    private static final class CapturingHandler implements ServerCallHandler<Object, Object> {
        final AtomicReference<io.grpc.Context> capturedContext = new AtomicReference<>();
        boolean called = false;

        @Override
        public ServerCall.Listener<Object> startCall(ServerCall<Object, Object> call, Metadata headers) {
            called = true;
            capturedContext.set(io.grpc.Context.current());
            return new ServerCall.Listener<>() {};
        }
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> mockCall(String fullMethodName) {
        ServerCall<Object, Object> call = mock(ServerCall.class);
        MethodDescriptor<Object, Object> md = mock(MethodDescriptor.class);
        when(md.getFullMethodName()).thenReturn(fullMethodName);
        when(call.getMethodDescriptor()).thenReturn(md);
        return call;
    }

    private static Metadata bearer(String token) {
        Metadata m = new Metadata();
        m.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return m;
    }

    @Test
    void sessTokenResolvesAndPopulatesContextWithSessionAndUserData() {
        Wiring w = new Wiring();
        GrpcUserData bob = w.bootstrap("bob");
        Session bobSession = w.sessions.create("bob", "Chrome");

        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, bearer(bobSession.token()), next);

        Assertions.assertTrue(next.called, "next handler must be called for valid sess- token");
        verify(call, never()).close(any(), any());
        Assertions.assertSame(bob, GrpcGatewayAuthContext.USER_DATA.get(next.capturedContext.get()));
        Assertions.assertEquals("bob", GrpcGatewayAuthContext.ACCOUNT_KEY.get(next.capturedContext.get()));
        Assertions.assertSame(bobSession, GrpcGatewayAuthContext.SESSION.get(next.capturedContext.get()),
                "valid sess- token must put the Session on the context for AuthService.Logout/Refresh");
    }

    @Test
    void unknownSessTokenIsRejectedWithUnauthenticated() {
        Wiring w = new Wiring();
        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, bearer("sess-totally-unknown-XYZ"), next);

        Assertions.assertFalse(next.called, "next must NOT be called for an unknown sess- token");
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call, times(1)).close(status.capture(), any());
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, status.getValue().getCode());
    }

    @Test
    void accessTokenIsRejected() {
        // The access-<accessKey> path was removed alongside the local- path.
        // Even with a bootstrapped user whose accessKey is known to the server,
        // a Bearer access-<key> request must now fail UNAUTHENTICATED so any
        // client still using it gets a clear migration error instead of a
        // silent privilege grant.
        Wiring w = new Wiring();
        GrpcUserData bob = w.bootstrap("bob");
        String accessKey = bob.getAccessKey();
        Assertions.assertNotNull(accessKey);

        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/ListUsers");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, bearer("access-" + accessKey), next);

        Assertions.assertFalse(next.called, "next must NOT be called for a removed access- token");
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call, times(1)).close(status.capture(), any());
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, status.getValue().getCode());
    }

    @Test
    void localTokenIsRejected() {
        // The local-<userName> path was removed so a non-loopback caller
        // cannot impersonate any in-JVM user just by knowing their name.
        Wiring w = new Wiring();
        w.bootstrap("bob");

        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();
        w.interceptor.interceptCall(call, bearer("local-bob"), next);

        Assertions.assertFalse(next.called, "next must NOT be called for a removed local- token");
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call, times(1)).close(status.capture(), any());
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, status.getValue().getCode());
    }

    @Test
    void unknownTokenPrefixIsRejected() {
        Wiring w = new Wiring();
        w.bootstrap("bob");
        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, bearer("totally-not-a-known-prefix-bob"), next);

        Assertions.assertFalse(next.called);
        verify(call, times(1)).close(any(), any());
    }

    @Test
    void missingBearerHeaderIsRejected() {
        Wiring w = new Wiring();
        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, new Metadata(), next);

        Assertions.assertFalse(next.called);
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call, times(1)).close(status.capture(), any());
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, status.getValue().getCode());
    }

    @Test
    void bootstrapMethodsAreExemptFromAuth() {
        Wiring w = new Wiring();
        // No setup — these methods must reach the service without any token.
        // SetPasswordForUser is no longer in the bootstrap set: it was removed
        // alongside the local-/access- token paths. AuthService.LoginV2 is the
        // single password-validating bootstrap path.
        for (String method : new String[]{
                "altastata.v1.AuthService/LoginV2",
                "altastata.v1.AccountSetupService/GenerateKeys",
                "altastata.v1.AccountSetupService/ChangePassword",
                "altastata.v1.AccountSetupService/GetSupportedAccountTypes",
        }) {
            ServerCall<Object, Object> call = mockCall(method);
            CapturingHandler next = new CapturingHandler();

            w.interceptor.interceptCall(call, new Metadata(), next);

            Assertions.assertTrue(next.called, "bootstrap method " + method + " must skip auth");
            verify(call, never()).close(any(), any());
        }
    }

    @Test
    void sessTokenForUserNotInRegistryIsRejected() {
        // Edge case: a session was issued, then the user was wiped from the
        // registry (e.g. JVM restart restored sessions but lost user data —
        // doesn't happen with current in-memory only setup, but we exercise
        // the defensive branch in the interceptor).
        Wiring w = new Wiring();
        Session ghostSession = w.sessions.create("ghost-user-not-in-registry", "");

        ServerCall<Object, Object> call = mockCall("altastata.v1.UsersService/GetMyAccount");
        CapturingHandler next = new CapturingHandler();

        w.interceptor.interceptCall(call, bearer(ghostSession.token()), next);

        Assertions.assertFalse(next.called);
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call, times(1)).close(status.capture(), any());
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, status.getValue().getCode());
    }
}
