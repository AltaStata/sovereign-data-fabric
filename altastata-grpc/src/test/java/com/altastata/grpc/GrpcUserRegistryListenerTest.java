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
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataEvent;
import com.altastata.api.AltaStataEventListener;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.grpc.proto.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link GrpcUserRegistry} attaches the event-bus listener to
 * the live {@link AltaStataFileSystem} at exactly the right moment: once,
 * on the first successful login, and never again on subsequent re-logins
 * or on a failed first login. Also pins down the integration with a real
 * {@link AltaStataEventBusAdapter} so that altastata-core SHARE/DELETE
 * events end up on the {@link EventBus} for the user.
 *
 * <p>Mocks are injected via {@link AccountRegistry#putForTesting}, with
 * the mock stubbed to return a matching {@link AccountId}.
 */
class GrpcUserRegistryListenerTest {

    private static final AccountId BOB = new AccountId(
            "altastata-org-bob-", "bob", "amazon-s3-secure");

    private static final String PROPS_BOB =
            "acccontainer-prefix=altastata-org-bob-\n"
          + "myuser=bob\n"
          + "accounttype=amazon-s3-secure\n";

    private static void prePopulate(AccountId id, AltaStataFileSystem mockFs) {
        when(mockFs.getAccountId()).thenReturn(id);
        AccountRegistry.putForTesting(id, mockFs);
    }

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    @Test
    void firstSuccessfulLoginAttachesListenerToLiveFileSystem() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        prePopulate(BOB, fs);
        AltaStataEventListener listener = event -> {};
        AtomicInteger factoryCalls = new AtomicInteger();
        GrpcUserRegistry registry = new GrpcUserRegistry(
                userName -> {
                    Assertions.assertEquals("bob", userName,
                            "factory must be invoked with the userName whose fs is being installed");
                    factoryCalls.incrementAndGet();
                    return listener;
                });
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> fs);

        Assertions.assertEquals(1, factoryCalls.get());
        verify(fs, times(1)).addAltaStataEventListener(listener);
    }

    @Test
    void factoryReturningNullSkipsListenerRegistrationCleanly() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        prePopulate(BOB, fs);
        GrpcUserRegistry registry = new GrpcUserRegistry(userName -> null);
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> fs);

        verify(fs, never()).addAltaStataEventListener(any());
    }

    @Test
    void reLoginWithCorrectPasswordDoesNotDoubleRegister() {
        // Re-Login no longer creates a probe AltaStataFileSystem at all
        // (see GrpcUserRegistry post-mortem about the parasite SQS poller).
        // The AccountRegistry returns the cached fs on the second call,
        // so the listener factory must be invoked exactly once.
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        prePopulate(BOB, live);
        AtomicInteger factoryCalls = new AtomicInteger();
        GrpcUserRegistry registry = new GrpcUserRegistry(
                userName -> {
                    factoryCalls.incrementAndGet();
                    return event -> {};
                });
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);

        Assertions.assertEquals(1, factoryCalls.get(),
                "listener factory must be called exactly once across all logins for the same user");
        verify(live, times(1)).addAltaStataEventListener(any());
    }

    @Test
    void firstLoginWithWrongPasswordDoesNotInvokeFactory() {
        AltaStataFileSystem probe = mock(AltaStataFileSystem.class);
        doThrow(new RuntimeException("invalid password"))
                .when(probe).setPassword(anyString());
        prePopulate(BOB, probe);
        AtomicInteger factoryCalls = new AtomicInteger();
        GrpcUserRegistry registry = new GrpcUserRegistry(
                userName -> {
                    factoryCalls.incrementAndGet();
                    return event -> {};
                });

        Assertions.assertThrows(RuntimeException.class,
                () -> registry.installFromLoginV2("bob", PROPS_BOB, "key", "wrong", () -> probe));

        Assertions.assertEquals(0, factoryCalls.get(),
                "factory must not be invoked when probe.setPassword throws");
        verify(probe, never()).addAltaStataEventListener(any());
    }

    @Test
    void wrongPasswordOnConcurrentReLoginDoesNotDoubleRegister() {
        // Same as above, but the second login uses the wrong password and
        // is rejected by the lightweight PEM validator (modelled here by
        // a PasswordValidator that throws on "wrong"). The live fs is
        // never replaced, the AccountRegistry never builds a second fs,
        // and the listener factory must not be invoked again.
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        prePopulate(BOB, live);
        GrpcUserRegistry.PasswordValidator validator = (pem, pwd) -> {
            if (new String(pwd).equals("wrong")) {
                throw new RuntimeException("invalid password");
            }
        };
        AtomicInteger factoryCalls = new AtomicInteger();
        GrpcUserRegistry registry = new GrpcUserRegistry(
                userName -> {
                    factoryCalls.incrementAndGet();
                    return event -> { };
                },
                validator);
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);
        Assertions.assertThrows(RuntimeException.class,
                () -> registry.installFromLoginV2("bob", PROPS_BOB, "key", "wrong", () -> live));

        Assertions.assertEquals(1, factoryCalls.get());
        verify(live, times(1)).addAltaStataEventListener(any());
    }

    @Test
    void endToEndProductionAdapterMakesShareEventsAppearOnEventBus() {
        // Realistic wiring: a real EventBus + a real AltaStataEventBusAdapter
        // attached to a mocked AltaStataFileSystem. We verify that an event
        // fired by altastata-core (simulated by directly invoking the
        // captured listener) lands on the EventBus for the right userName.
        EventBus bus = new EventBus(EventBus.DEFAULT_RING_SIZE,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        List<Event> received = new CopyOnWriteArrayList<>();
        bus.subscribe("bob", "sess-bob", received::add, 0);

        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        prePopulate(BOB, fs);
        // Capture whatever the registry attaches.
        java.util.concurrent.atomic.AtomicReference<AltaStataEventListener> attached =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            attached.set(inv.getArgument(0));
            return null;
        }).when(fs).addAltaStataEventListener(any());

        GrpcUserRegistry registry = new GrpcUserRegistry(
                userName -> new AltaStataEventBusAdapter(userName, bus));
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> fs);

        Assertions.assertNotNull(attached.get(),
                "registry must have attached a listener");

        // Simulate altastata-core firing a SHARE for bob.
        attached.get().notify(new AltaStataEvent("SHARE", "/from-alice/report.pdf"));

        Assertions.assertEquals(1, received.size());
        Assertions.assertTrue(received.get(0).hasFileShared());
        Assertions.assertEquals("/from-alice/report.pdf",
                received.get(0).getFileShared().getFileId());
        Assertions.assertEquals(1L, bus.lastSequence("bob"));
    }
}
