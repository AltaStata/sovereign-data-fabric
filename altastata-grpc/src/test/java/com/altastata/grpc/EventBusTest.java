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

import com.altastata.grpc.proto.Event;
import com.altastata.grpc.proto.FileSharedEvent;
import com.altastata.grpc.proto.FileUnsharedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class EventBusTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Simple in-memory subscriber that just appends every delivered event. */
    private static final class CapturingSubscriber implements EventBusSubscriber {
        final List<Event> received = new CopyOnWriteArrayList<>();
        volatile String evictionReason;
        volatile int evictionCalls;

        @Override
        public void deliver(Event event) {
            received.add(event);
        }

        @Override
        public void closeOnEviction(String reason) {
            this.evictionReason = reason;
            this.evictionCalls++;
        }
    }

    private static EventBus busWithFixedClock() {
        return new EventBus(EventBus.DEFAULT_RING_SIZE, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static Event.Builder fileShared(String fileId) {
        return Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId(fileId).setFilePath("/x/" + fileId).setSharedBy("alice"));
    }

    private static Event.Builder fileUnshared(String fileId) {
        return Event.newBuilder().setFileUnshared(
                FileUnsharedEvent.newBuilder().setFileId(fileId).setUnsharedBy("alice"));
    }

    @Test
    void publishStampsMonotonicSequencePerUserAndFillsTimestamp() {
        EventBus bus = busWithFixedClock();

        Event e1 = bus.publish("bob", fileShared("f1"), "");
        Event e2 = bus.publish("bob", fileShared("f2"), "");
        Event e3 = bus.publish("alice", fileShared("f3"), "");

        Assertions.assertEquals(1, e1.getSequence());
        Assertions.assertEquals(2, e2.getSequence());
        Assertions.assertEquals(1, e3.getSequence(),
                "sequence is per-userName, alice's first event must be 1");
        Assertions.assertEquals(T0.getEpochSecond(), e1.getOccurredAt().getSeconds());
    }

    @Test
    void publishFanOutsToAllSubscribersForSameUser() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber a = new CapturingSubscriber();
        CapturingSubscriber b = new CapturingSubscriber();
        CapturingSubscriber c = new CapturingSubscriber();

        bus.subscribe("bob", "sess-a", a, 0);
        bus.subscribe("bob", "sess-b", b, 0);
        bus.subscribe("bob", "sess-c", c, 0);

        bus.publish("bob", fileShared("f1"), "hash-tab-1");

        Assertions.assertEquals(1, a.received.size());
        Assertions.assertEquals(1, b.received.size());
        Assertions.assertEquals(1, c.received.size());
        Assertions.assertEquals("hash-tab-1", a.received.get(0).getOriginSessionHash());
        Assertions.assertEquals("hash-tab-1", b.received.get(0).getOriginSessionHash());
    }

    @Test
    void subscribersAreIsolatedAcrossUserNames() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber bobSub = new CapturingSubscriber();
        CapturingSubscriber aliceSub = new CapturingSubscriber();

        bus.subscribe("bob", "sess-bob", bobSub, 0);
        bus.subscribe("alice", "sess-alice", aliceSub, 0);

        bus.publish("bob", fileShared("f1"), "");
        bus.publish("alice", fileShared("f2"), "");
        bus.publish("alice", fileUnshared("f3"), "");

        Assertions.assertEquals(1, bobSub.received.size());
        Assertions.assertEquals("f1", bobSub.received.get(0).getFileShared().getFileId());

        Assertions.assertEquals(2, aliceSub.received.size());
        Assertions.assertEquals("f2", aliceSub.received.get(0).getFileShared().getFileId());
        Assertions.assertTrue(aliceSub.received.get(1).hasFileUnshared());
    }

    @Test
    void unsubscribeStopsFurtherDelivery() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber sub = new CapturingSubscriber();
        EventBus.Subscription subscription = bus.subscribe("bob", "sess-bob", sub, 0);

        bus.publish("bob", fileShared("f1"), "");
        bus.unsubscribe(subscription);
        bus.publish("bob", fileShared("f2"), "");

        Assertions.assertEquals(1, sub.received.size());
        Assertions.assertEquals(0, bus.subscriberCount("bob"));
    }

    @Test
    void subscribeWithSinceSequenceReplaysBufferedEvents() {
        EventBus bus = busWithFixedClock();

        // Publish without subscribers — events still go into the ring buffer.
        bus.publish("bob", fileShared("f1"), "");
        bus.publish("bob", fileShared("f2"), "");
        bus.publish("bob", fileShared("f3"), "");

        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 1);   // resume after seq=1

        Assertions.assertEquals(2, sub.received.size(), "should replay seq 2 and 3, not 1");
        Assertions.assertEquals(2, sub.received.get(0).getSequence());
        Assertions.assertEquals(3, sub.received.get(1).getSequence());

        // Live events continue from seq=4.
        bus.publish("bob", fileShared("f4"), "");
        Assertions.assertEquals(3, sub.received.size());
        Assertions.assertEquals(4, sub.received.get(2).getSequence());
    }

    @Test
    void subscribeWithSinceSequenceOlderThanRingEmitsEventGapFirst() {
        EventBus bus = new EventBus(3, Clock.fixed(T0, ZoneOffset.UTC));   // tiny ring

        // 5 publishes into a 3-slot ring -> oldest seq held is 3.
        for (int i = 1; i <= 5; i++) {
            bus.publish("bob", fileShared("f" + i), "");
        }

        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 1);   // requested cursor older than oldest=3

        Assertions.assertFalse(sub.received.isEmpty());
        Assertions.assertTrue(sub.received.get(0).hasEventGap(),
                "first delivery must be EventGapEvent when since_sequence is older than the ring");
        Assertions.assertEquals(3L, sub.received.get(0).getEventGap().getServerOldestSequence());

        // Followed by replay of seq 3, 4, 5.
        Assertions.assertEquals(4, sub.received.size());
        Assertions.assertEquals(3, sub.received.get(1).getSequence());
        Assertions.assertEquals(4, sub.received.get(2).getSequence());
        Assertions.assertEquals(5, sub.received.get(3).getSequence());
    }

    @Test
    void subscribeWithSinceSequenceZeroDoesNotReplay() {
        EventBus bus = busWithFixedClock();
        bus.publish("bob", fileShared("f1"), "");
        bus.publish("bob", fileShared("f2"), "");

        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 0);

        Assertions.assertTrue(sub.received.isEmpty(),
                "since_sequence=0 means live-only; no replay");
    }

    @Test
    void subscribeWithSinceSequenceAtOrAboveLatestNoReplay() {
        EventBus bus = busWithFixedClock();
        bus.publish("bob", fileShared("f1"), "");
        bus.publish("bob", fileShared("f2"), "");

        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("bob", "sess-bob", sub, 2);   // exactly the latest

        Assertions.assertTrue(sub.received.isEmpty(),
                "no events should replay when since_sequence >= latest");
    }

    @Test
    void aMisbehavingSubscriberIsRemovedAndDoesNotBlockOthers() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber good = new CapturingSubscriber();
        EventBusSubscriber bad = (e) -> {
            throw new RuntimeException("subscriber crash");
        };

        bus.subscribe("bob", "sess-bad", bad, 0);
        bus.subscribe("bob", "sess-good", good, 0);

        bus.publish("bob", fileShared("f1"), "");
        bus.publish("bob", fileShared("f2"), "");

        Assertions.assertEquals(2, good.received.size(),
                "good subscriber must keep receiving despite a sibling subscriber throwing");
        Assertions.assertEquals(1, bus.subscriberCount("bob"),
                "throwing subscriber must be auto-removed");
    }

    @Test
    void publishToUserWithNoSubscribersStillBuffersForFutureReplay() {
        EventBus bus = busWithFixedClock();

        bus.publish("ghost", fileShared("f1"), "");
        bus.publish("ghost", fileShared("f2"), "");

        Assertions.assertEquals(2L, bus.lastSequence("ghost"));
        Assertions.assertNotNull(bus.bufferFor("ghost"));
        Assertions.assertEquals(2, bus.bufferFor("ghost").size());

        CapturingSubscriber sub = new CapturingSubscriber();
        bus.subscribe("ghost", "sess-ghost", sub, 0);
        Assertions.assertTrue(sub.received.isEmpty(), "since=0 = live-only, no replay");

        // But replay does work.
        CapturingSubscriber replaying = new CapturingSubscriber();
        bus.subscribe("ghost", "sess-ghost-2", replaying, 1);
        Assertions.assertEquals(1, replaying.received.size());
        Assertions.assertEquals(2, replaying.received.get(0).getSequence());
    }

    @Test
    void subscribeRejectsNullOrEmptyArguments() {
        EventBus bus = busWithFixedClock();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.subscribe("", "sess-x", e -> {}, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.subscribe("bob", "sess-x", null, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.publish("", fileShared("f1"), ""));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.publish("bob", null, ""));
    }

    @Test
    void unsubscribeIsIdempotentAndNullSafe() {
        EventBus bus = busWithFixedClock();
        bus.unsubscribe(null);   // no NPE

        CapturingSubscriber sub = new CapturingSubscriber();
        EventBus.Subscription subscription = bus.subscribe("bob", "sess-bob", sub, 0);

        bus.unsubscribe(subscription);
        bus.unsubscribe(subscription);   // second call is a no-op

        Assertions.assertEquals(0, bus.subscriberCount("bob"));
    }

    @Test
    void evictSessionClosesAllItsSubscribersAndDropsThemFromTheBus() {
        EventBus bus = busWithFixedClock();
        CapturingSubscriber priorBob = new CapturingSubscriber();
        CapturingSubscriber survivor = new CapturingSubscriber();
        CapturingSubscriber otherUser = new CapturingSubscriber();

        // Two sessions for bob (two browser tabs of the same user) and one
        // unrelated session for alice. Evicting bob's prior session must
        // close *only* that one subscriber and leave the others untouched.
        bus.subscribe("bob", "sess-bob-prior", priorBob, 0);
        bus.subscribe("bob", "sess-bob-new",   survivor, 0);
        bus.subscribe("alice", "sess-alice",   otherUser, 0);

        int closed = bus.evictSession("sess-bob-prior", "newer login");

        Assertions.assertEquals(1, closed);
        Assertions.assertEquals(1, priorBob.evictionCalls);
        Assertions.assertEquals("newer login", priorBob.evictionReason);
        Assertions.assertEquals(0, survivor.evictionCalls);
        Assertions.assertEquals(0, otherUser.evictionCalls);

        bus.publish("bob", fileShared("f-after"), "");
        Assertions.assertTrue(priorBob.received.isEmpty(),
                "evicted subscriber must not receive further events");
        Assertions.assertEquals(1, survivor.received.size(),
                "the surviving session for the same user keeps receiving");
        Assertions.assertEquals(1, bus.subscriberCount("bob"));
    }

    @Test
    void evictSessionIsNullAndUnknownTokenSafe() {
        EventBus bus = busWithFixedClock();
        Assertions.assertEquals(0, bus.evictSession(null, "x"));
        Assertions.assertEquals(0, bus.evictSession("", "x"));
        Assertions.assertEquals(0, bus.evictSession("sess-never-seen", "x"));
    }

    /** Subscriber whose isAlive() flag is flipped by the test. */
    private static final class FlakySubscriber implements EventBusSubscriber {
        final List<Event> received = new CopyOnWriteArrayList<>();
        volatile boolean alive = true;
        volatile int evictionCalls;

        @Override public void deliver(Event event) { received.add(event); }
        @Override public boolean isAlive() { return alive; }
        @Override public void closeOnEviction(String reason) { evictionCalls++; }
    }

    @Test
    void reaperDropsSubscribersWhoseTransportDiedSilently() {
        EventBus bus = busWithFixedClock();
        FlakySubscriber alive = new FlakySubscriber();
        FlakySubscriber dead = new FlakySubscriber();

        bus.subscribe("bob", "sess-alive", alive, 0);
        bus.subscribe("bob", "sess-dead",  dead,  0);
        Assertions.assertEquals(2, bus.subscriberCount("bob"));

        // Simulate HTTP/2 keepalive flipping isCancelled on the dead one's
        // observer without setOnCancelHandler firing.
        dead.alive = false;

        int reaped = bus.reapDeadSubscribers();

        Assertions.assertEquals(1, reaped);
        Assertions.assertEquals(1, dead.evictionCalls,
                "dead subscriber must be told to close its transport");
        Assertions.assertEquals(0, alive.evictionCalls,
                "still-alive subscriber must be left alone");
        Assertions.assertEquals(1, bus.subscriberCount("bob"));

        bus.publish("bob", fileShared("f-after"), "");
        Assertions.assertEquals(1, alive.received.size());
        Assertions.assertTrue(dead.received.isEmpty(),
                "reaped subscriber must not receive further events");
    }

    @Test
    void reaperSurvivesAMisbehavingIsAliveAndDropsTheSubscriber() {
        EventBus bus = busWithFixedClock();
        FlakySubscriber sane = new FlakySubscriber();
        EventBusSubscriber throwing = new EventBusSubscriber() {
            @Override public void deliver(Event event) { }
            @Override public boolean isAlive() { throw new RuntimeException("boom"); }
        };

        bus.subscribe("bob", "sess-sane", sane, 0);
        bus.subscribe("bob", "sess-throws", throwing, 0);

        int reaped = bus.reapDeadSubscribers();

        Assertions.assertEquals(1, reaped,
                "throwing isAlive() is treated as dead, sane stays put");
        Assertions.assertEquals(1, bus.subscriberCount("bob"));
    }

    @Test
    void reaperIsNoOpWhenAllSubscribersAreAlive() {
        EventBus bus = busWithFixedClock();
        bus.subscribe("bob", "sess-bob", new FlakySubscriber(), 0);
        bus.subscribe("alice", "sess-alice", new FlakySubscriber(), 0);

        Assertions.assertEquals(0, bus.reapDeadSubscribers());
        Assertions.assertEquals(1, bus.subscriberCount("bob"));
        Assertions.assertEquals(1, bus.subscriberCount("alice"));
    }

    @Test
    void concurrentPublishersProduceUniqueIncrementingSequences() throws Exception {
        EventBus bus = busWithFixedClock();
        int producers = 8;
        int perProducer = 200;

        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            threads.add(new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    bus.publish("bob", fileShared("x"), "");
                }
            }));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        Assertions.assertEquals((long) producers * perProducer, bus.lastSequence("bob"),
                "every publish must mint a unique sequence");
    }
}
