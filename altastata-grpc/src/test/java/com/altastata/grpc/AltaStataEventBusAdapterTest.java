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

import com.altastata.api.AltaStataEvent;
import com.altastata.grpc.proto.Event;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unit tests for {@link AltaStataEventBusAdapter}: maps the legacy untyped
 * {@link AltaStataEvent} stream from {@code SecureCloudEventProcessor}
 * (in {@code altastata-core}) onto the typed {@link EventBus} surface that
 * {@code EventsService.Watch} consumes.
 */
class AltaStataEventBusAdapterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Captures every event published into the bus for "bob" during the test. */
    private static final class CapturingSubscriber implements EventBusSubscriber {
        final List<Event> received = new CopyOnWriteArrayList<>();

        @Override
        public void deliver(Event event) {
            received.add(event);
        }
    }

    private static EventBus busWithFixedClock() {
        return new EventBus(EventBus.DEFAULT_RING_SIZE, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void shareEventBecomesFileSharedEventOnTheBus() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);
        AltaStataEventBusAdapter adapter = new AltaStataEventBusAdapter("bob", bus);

        adapter.notify(new AltaStataEvent("SHARE", "/users/alice/shared/report.pdf"));

        Assertions.assertEquals(1, sub.received.size());
        Event ev = sub.received.get(0);
        Assertions.assertTrue(ev.hasFileShared(),
                "SHARE event must map to FileSharedEvent");
        Assertions.assertEquals("/users/alice/shared/report.pdf", ev.getFileShared().getFileId());
        Assertions.assertEquals("/users/alice/shared/report.pdf", ev.getFileShared().getFilePath());
        Assertions.assertEquals("", ev.getFileShared().getSharedBy(),
                "shared_by is intentionally empty until enrichment lands; see adapter Javadoc");
        Assertions.assertEquals(1, ev.getSequence(), "EventBus stamps sequence 1 for the first publish");
    }

    @Test
    void deleteEventBecomesFileUnsharedEventOnTheBus() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);
        AltaStataEventBusAdapter adapter = new AltaStataEventBusAdapter("bob", bus);

        adapter.notify(new AltaStataEvent("DELETE", "/users/alice/shared/old.pdf"));

        Assertions.assertEquals(1, sub.received.size());
        Event ev = sub.received.get(0);
        Assertions.assertTrue(ev.hasFileUnshared(),
                "DELETE event maps to FileUnsharedEvent (today's 'deleted'='unshared' semantics)");
        Assertions.assertEquals("/users/alice/shared/old.pdf", ev.getFileUnshared().getFileId());
        Assertions.assertEquals("", ev.getFileUnshared().getUnsharedBy());
    }

    @Test
    void unknownEventNamesAreIgnored() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);
        AltaStataEventBusAdapter adapter = new AltaStataEventBusAdapter("bob", bus);

        adapter.notify(new AltaStataEvent("ADDREADER", "ignored"));
        adapter.notify(new AltaStataEvent("REMOVEREADER", "ignored"));
        adapter.notify(new AltaStataEvent("ADD_USERDATA", "ignored"));
        adapter.notify(new AltaStataEvent("totally-made-up", "ignored"));

        Assertions.assertEquals(0, sub.received.size(),
                "adapter must not publish anything for event names we do not yet handle");
        Assertions.assertEquals(0L, bus.lastSequence("bob"),
                "no sequence should have been minted");
    }

    @Test
    void nullEventOrNullEventNameIsTolerated() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);
        AltaStataEventBusAdapter adapter = new AltaStataEventBusAdapter("bob", bus);

        adapter.notify(null);
        adapter.notify(new AltaStataEvent(null, "x"));

        Assertions.assertEquals(0, sub.received.size());
    }

    @Test
    void nullDataIsCoercedToEmptyString() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);
        AltaStataEventBusAdapter adapter = new AltaStataEventBusAdapter("bob", bus);

        adapter.notify(new AltaStataEvent("SHARE", null));

        Assertions.assertEquals(1, sub.received.size());
        Assertions.assertEquals("", sub.received.get(0).getFileShared().getFileId());
        Assertions.assertEquals("", sub.received.get(0).getFileShared().getFilePath());
    }

    @Test
    void adapterFansOutOnlyToItsOwnUserName() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber bobSub = new CapturingSubscriber();
        CapturingSubscriber aliceSub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", bobSub, 0);
        bus.subscribe("alice", "sess-alice", aliceSub, 0);

        AltaStataEventBusAdapter forBob = new AltaStataEventBusAdapter("bob", bus);
        forBob.notify(new AltaStataEvent("SHARE", "/x/f1"));

        Assertions.assertEquals(1, bobSub.received.size());
        Assertions.assertEquals(0, aliceSub.received.size(),
                "an adapter for userName=bob must never deliver to alice");
    }

    @Test
    void rejectsNullOrEmptyUserNameAndNullEventBus() {
        EventBus bus = busWithFixedClock();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AltaStataEventBusAdapter(null, bus));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AltaStataEventBusAdapter("", bus));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AltaStataEventBusAdapter("bob", null));
    }
}
