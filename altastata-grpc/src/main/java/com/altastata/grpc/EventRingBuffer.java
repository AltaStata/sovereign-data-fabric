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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded FIFO buffer of {@link Event}s for a single {@code userName}, used to
 * service {@code WatchRequest.since_sequence} replay after a client reconnects.
 *
 * <p>Capacity is fixed at construction. {@link #add(Event)} evicts the oldest
 * entry when full, so the buffer always holds the most recent {@code capacity}
 * events for that user. If a client's {@code since_sequence} is older than
 * {@link #oldestSequence()}, the consumer is expected to emit a synthetic
 * {@code EventGapEvent} before live delivery resumes — see
 * {@code SESSION_AND_EVENTS_DESIGN.md §7.2}.
 *
 * <p>All access is guarded by {@code synchronized} on this instance. The
 * buffer is small (a few KB to a few MB depending on capacity and event size)
 * and contention is bounded by the number of subscribers for that userName,
 * so a single intrinsic lock is sufficient and keeps the implementation small.
 */
final class EventRingBuffer {
    private final int capacity;
    private final ArrayDeque<Event> buffer;

    EventRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    /** Append the latest event, evicting the oldest if the buffer is full. */
    synchronized void add(Event event) {
        if (buffer.size() >= capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(event);
    }

    /**
     * Sequence number of the oldest event still buffered, or {@code 0} when
     * empty. Compared against an incoming {@code WatchRequest.since_sequence}
     * to detect gaps.
     */
    synchronized long oldestSequence() {
        Event first = buffer.peekFirst();
        return first == null ? 0L : first.getSequence();
    }

    /**
     * Snapshot of buffered events with {@code sequence > sinceSequence}, in
     * insertion order. Returns a fresh list so the caller can iterate without
     * holding the lock.
     */
    synchronized List<Event> eventsAfter(long sinceSequence) {
        List<Event> out = new ArrayList<>();
        for (Event event : buffer) {
            if (event.getSequence() > sinceSequence) {
                out.add(event);
            }
        }
        return out;
    }

    /**
     * Resolves the current size of the event ring buffer.
     *
     * @return current number of items buffered
     */
    synchronized int size() {
        return buffer.size();
    }

    /**
     * Resolves the maximum capacity of the event ring buffer.
     *
     * @return the maximum capacity limit
     */
    int capacity() {
        return capacity;
    }
}
