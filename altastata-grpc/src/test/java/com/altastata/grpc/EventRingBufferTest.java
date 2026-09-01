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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class EventRingBufferTest {

    private static Event ev(long seq) {
        return Event.newBuilder()
                .setSequence(seq)
                .setFileShared(FileSharedEvent.newBuilder().setFileId("f-" + seq))
                .build();
    }

    @Test
    void emptyBufferHasZeroOldestAndNoEventsAfter() {
        EventRingBuffer buf = new EventRingBuffer(4);

        Assertions.assertEquals(0L, buf.oldestSequence());
        Assertions.assertEquals(0, buf.size());
        Assertions.assertTrue(buf.eventsAfter(0).isEmpty());
        Assertions.assertTrue(buf.eventsAfter(99).isEmpty());
    }

    @Test
    void addThenEventsAfterReturnsInsertionOrderForRequestedSuffix() {
        EventRingBuffer buf = new EventRingBuffer(8);
        buf.add(ev(1));
        buf.add(ev(2));
        buf.add(ev(3));

        List<Event> all = buf.eventsAfter(0);
        Assertions.assertEquals(3, all.size());
        Assertions.assertEquals(1, all.get(0).getSequence());
        Assertions.assertEquals(2, all.get(1).getSequence());
        Assertions.assertEquals(3, all.get(2).getSequence());

        Assertions.assertEquals(1, buf.eventsAfter(2).size());
        Assertions.assertEquals(3, buf.eventsAfter(2).get(0).getSequence());

        Assertions.assertTrue(buf.eventsAfter(3).isEmpty());
        Assertions.assertEquals(1L, buf.oldestSequence());
    }

    @Test
    void exceedingCapacityEvictsOldestAndAdvancesOldestSequence() {
        EventRingBuffer buf = new EventRingBuffer(3);
        buf.add(ev(1));
        buf.add(ev(2));
        buf.add(ev(3));
        buf.add(ev(4));   // evicts seq=1
        buf.add(ev(5));   // evicts seq=2

        Assertions.assertEquals(3, buf.size());
        Assertions.assertEquals(3L, buf.oldestSequence());

        List<Event> remaining = buf.eventsAfter(0);
        Assertions.assertEquals(3, remaining.size());
        Assertions.assertEquals(3, remaining.get(0).getSequence());
        Assertions.assertEquals(4, remaining.get(1).getSequence());
        Assertions.assertEquals(5, remaining.get(2).getSequence());
    }

    @Test
    void eventsAfterReturnsADefensiveSnapshotNotABackingView() {
        EventRingBuffer buf = new EventRingBuffer(4);
        buf.add(ev(1));
        buf.add(ev(2));

        List<Event> snapshot = buf.eventsAfter(0);
        buf.add(ev(3));

        Assertions.assertEquals(2, snapshot.size(),
                "eventsAfter must return a snapshot detached from the buffer");
        Assertions.assertEquals(3, buf.size());
    }

    @Test
    void capacityMustBePositive() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new EventRingBuffer(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new EventRingBuffer(-1));
    }
}
