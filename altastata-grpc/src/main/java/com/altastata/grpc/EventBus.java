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
import com.altastata.grpc.proto.EventGapEvent;
import com.google.protobuf.Timestamp;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory pub/sub for typed {@link Event} messages, keyed by recipient
 * {@code userName}.
 *
 * <p>Per {@code SESSION_AND_EVENTS_DESIGN.md §7}, every active session opens
 * exactly one {@code Watch} stream and registers as one {@link EventBusSubscriber}
 * here; the bus fans every published event out to every subscriber for that
 * recipient userName, with no cross-session deduplication. Each session gets
 * its own independent copy.
 *
 * <p>State held per userName:
 *
 * <ul>
 *   <li>{@code seq} — monotonic counter, starts at 1, never reused.</li>
 *   <li>{@code buffer} — bounded ring of recent events for replay.</li>
 *   <li>{@code subs} — concurrent set of active subscribers.</li>
 * </ul>
 *
 * <p>Replay rules on {@link #subscribe(String, String, EventBusSubscriber, long)}
 * when {@code sinceSequence > 0}:
 *
 * <ul>
 *   <li>If the buffer is empty, no replay (the user has no history yet).</li>
 *   <li>If {@code buffer.oldestSequence() > sinceSequence + 1}, the requested
 *       cursor is older than what the bus still holds, so an
 *       {@code EventGapEvent} is delivered first to tell the client to
 *       reload state from scratch.</li>
 *   <li>Otherwise the buffered events with {@code sequence > sinceSequence}
 *       are delivered in order, and live events flow afterwards.</li>
 * </ul>
 *
 * <p>The bus owns no threads of its own — publish is synchronous on the
 * caller's thread, and per-subscriber dispatch is the subscriber's
 * responsibility (bounded queue + drain thread for the gRPC path).
 */
@Singleton
public class EventBus {
    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    static final int DEFAULT_RING_SIZE = 1000;
    static final Duration REAPER_INTERVAL = Duration.ofMinutes(1);

    private final Map<String, AtomicLong>      seqByUser    = new ConcurrentHashMap<>();
    private final Map<String, EventRingBuffer> bufferByUser = new ConcurrentHashMap<>();
    private final Map<String, Set<EventBusSubscriber>> subsByUser =
            new ConcurrentHashMap<>();
    /**
     * Secondary index from session token to the {@link Subscription} objects
     * that one session has open. Lets {@link #evictSession(String, String)}
     * close every Watch stream owned by a single session in O(1) instead of
     * scanning {@link #subsByUser} per call. A given session typically owns
     * exactly one subscription (one Watch stream) but the design leaves room
     * for more in case future RPCs subscribe again.
     */
    private final Map<String, Set<Subscription>> subsBySession =
            new ConcurrentHashMap<>();

    private final int ringSize;
    private final Clock clock;
    private final ScheduledExecutorService reaper;

    /**
     * Constructs a new EventBus.
     *
     * @param ringSize The size of the ring buffer for events
     */
    public EventBus(@Value("${grpcgateway.events.ring-size:1000}") int ringSize) {
        this(ringSize, Clock.systemUTC(), true);
    }

    /** Test seam: package-private with injectable {@link Clock} and ring size. */
    EventBus(int ringSize, Clock clock) {
        this(ringSize, clock, false);
    }

    /**
     * Test seam: lets a test opt into the production reaper to verify it
     * runs, but defaults to {@code false} so the existing fast unit tests
     * are not coupled to a daemon thread.
     */
    EventBus(int ringSize, Clock clock, boolean enableReaper) {
        this.ringSize = ringSize > 0 ? ringSize : DEFAULT_RING_SIZE;
        this.clock = clock;
        if (enableReaper) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "altastata-eventbus-reaper");
                t.setDaemon(true);
                return t;
            });
            long ms = REAPER_INTERVAL.toMillis();
            reaper.scheduleWithFixedDelay(this::reapDeadSubscribers, ms, ms, TimeUnit.MILLISECONDS);
            logger.info("EventBus reaper enabled, interval={}", REAPER_INTERVAL);
        } else {
            this.reaper = null;
        }
    }

    /**
     * Shuts down the EventBus, stopping the background session-reaper thread executor.
     */
    @PreDestroy
    void shutdown() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }

    /**
     * Register a subscriber for {@code userName} and replay any buffered
     * events newer than {@code sinceSequence}. The {@code sessionToken} is
     * the {@code sess-...} bearer of the calling session; it is recorded on
     * the {@link Subscription} so {@link #evictSession(String, String)} can
     * later close every stream owned by that one session in O(1).
     *
     * <p>Returns a {@link Subscription} handle the caller passes to
     * {@link #unsubscribe(Subscription)} on shutdown.
     */
    public Subscription subscribe(
            String userName,
            String sessionToken,
            EventBusSubscriber subscriber,
            long sinceSequence) {
        if (userName == null || userName.isEmpty()) {
            throw new IllegalArgumentException("userName must be non-empty");
        }
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        String token = sessionToken == null ? "" : sessionToken;
        Subscription sub = new Subscription(userName, token, subscriber);

        Set<EventBusSubscriber> set = subsByUser.computeIfAbsent(
                userName, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        set.add(subscriber);
        if (!token.isEmpty()) {
            subsBySession
                    .computeIfAbsent(token, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                    .add(sub);
        }

        if (sinceSequence > 0) {
            EventRingBuffer buf = bufferByUser.get(userName);
            if (buf != null && buf.size() > 0) {
                long oldest = buf.oldestSequence();
                if (oldest > sinceSequence + 1) {
                    subscriber.deliver(Event.newBuilder()
                            .setEventGap(EventGapEvent.newBuilder()
                                    .setServerOldestSequence(oldest))
                            .build());
                }
                List<Event> toReplay = buf.eventsAfter(sinceSequence);
                for (Event e : toReplay) {
                    subscriber.deliver(e);
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("Watch replay for userName={} since_seq={} -> oldest={} replayed={}",
                            userName, sinceSequence, oldest, toReplay.size());
                }
            }
        }

        return sub;
    }

    /** Drop a subscription. Idempotent. */
    public void unsubscribe(Subscription subscription) {
        if (subscription == null) {
            return;
        }
        Set<EventBusSubscriber> set = subsByUser.get(subscription.userName());
        if (set != null) {
            set.remove(subscription.subscriber());
            if (set.isEmpty()) {
                // Best-effort GC of empty entries; concurrent subscribe
                // may immediately recreate, which is fine.
                subsByUser.remove(subscription.userName(), set);
            }
        }
        String token = subscription.sessionToken();
        if (!token.isEmpty()) {
            Set<Subscription> sset = subsBySession.get(token);
            if (sset != null) {
                sset.remove(subscription);
                if (sset.isEmpty()) {
                    subsBySession.remove(token, sset);
                }
            }
        }
    }

    /**
     * Tear down every active subscription owned by {@code sessionToken} and
     * tell each one to close its transport with a non-retryable status. Used
     * by {@code AuthService.Login} when a fresh login from the same logical
     * client (same {@code userName + clientHint}) supersedes a prior session,
     * leaving the prior session's {@code Watch} stream as a zombie that would
     * otherwise hold an EventBus subscriber open until the 8h session TTL.
     *
     * <p>No-op for unknown / blank tokens. Returns the number of subscribers
     * that were torn down (useful for tests).
     */
    public int evictSession(String sessionToken, String reason) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            return 0;
        }
        Set<Subscription> sset = subsBySession.remove(sessionToken);
        if (sset == null || sset.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Subscription sub : sset) {
            Set<EventBusSubscriber> userSet = subsByUser.get(sub.userName());
            if (userSet != null) {
                userSet.remove(sub.subscriber());
                if (userSet.isEmpty()) {
                    subsByUser.remove(sub.userName(), userSet);
                }
            }
            try {
                sub.subscriber().closeOnEviction(reason);
            } catch (Exception e) {
                logger.warn("Subscriber.closeOnEviction threw for sessionToken={}", sessionToken, e);
            }
            n++;
        }
        if (n > 0) {
            logger.warn("EventBus.evictSession: closed {} subscriber(s) for sessionToken (reason='{}')",
                    n, reason);
        }
        return n;
    }

    /**
     * Publish one event to every subscriber for {@code userName}. The bus
     * stamps {@code sequence}, {@code occurred_at}, and
     * {@code origin_session_hash} on the supplied builder before fan-out.
     *
     * @return the fully-stamped event that was actually delivered (useful for
     *         tests and for callers that want to log the assigned sequence).
     */
    public Event publish(String userName, Event.Builder payload, String originSessionHash) {
        if (userName == null || userName.isEmpty()) {
            throw new IllegalArgumentException("userName must be non-empty");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        long seq = seqByUser.computeIfAbsent(userName, k -> new AtomicLong()).incrementAndGet();
        Instant now = clock.instant();
        Event event = payload
                .setSequence(seq)
                .setOccurredAt(toProto(now))
                .setOriginSessionHash(originSessionHash == null ? "" : originSessionHash)
                .build();

        bufferByUser.computeIfAbsent(userName, k -> new EventRingBuffer(ringSize)).add(event);

        Set<EventBusSubscriber> subs = subsByUser.get(userName);
        int fanOut = 0;
        if (subs != null) {
            EventBusSubscriber[] snapshot = subs.toArray(new EventBusSubscriber[0]);
            fanOut = snapshot.length;
            // Snapshot keeps a misbehaving subscriber's deliver() exception
            // from corrupting iteration over a concurrent set.
            for (EventBusSubscriber s : snapshot) {
                try {
                    s.deliver(event);
                } catch (Exception e) {
                    logger.warn("Subscriber.deliver threw for userName={} seq={}; dropping subscriber",
                            userName, seq, e);
                    subs.remove(s);
                }
            }
        }
        logger.warn("EventBus.publish userName={} seq={} fanOut={} payloadCase={}",
                userName, seq, fanOut, event.getPayloadCase());
        return event;
    }

    /**
     * Sweep every active subscription and drop the ones whose underlying
     * transport is no longer alive (per
     * {@link EventBusSubscriber#isAlive()}). Defence-in-depth against the
     * gRPC {@code setOnCancelHandler} race: HTTP/2 keepalive can flip the
     * server observer's {@code isCancelled} bit without the
     * cancel-handler ever firing — typically when the peer's TCP died
     * silently (suspended laptop, kill -9, NAT timeout). Without this,
     * those subscribers would only be cleaned up on the next
     * {@link #publish} that overflowed the per-subscriber queue (256
     * events later, possibly hours).
     *
     * <p>Visible for tests; production wiring schedules it on the daemon
     * reaper thread every {@link #REAPER_INTERVAL}.
     */
    int reapDeadSubscribers() {
        int reaped = 0;
        try {
            // Snapshot keys so we can mutate subsByUser without ConcurrentModification.
            for (Map.Entry<String, Set<EventBusSubscriber>> entry : subsByUser.entrySet()) {
                String userName = entry.getKey();
                EventBusSubscriber[] snap = entry.getValue().toArray(new EventBusSubscriber[0]);
                for (EventBusSubscriber sub : snap) {
                    boolean alive;
                    try {
                        alive = sub.isAlive();
                    } catch (Exception e) {
                        // A misbehaving isAlive() is treated as "not alive": keeping
                        // a throwing subscriber registered is worse than reaping it.
                        logger.warn("Subscriber.isAlive threw for userName={}; reaping",
                                userName, e);
                        alive = false;
                    }
                    if (alive) continue;
                    entry.getValue().remove(sub);
                    try {
                        sub.closeOnEviction("Watch transport closed (reaper)");
                    } catch (Exception e) {
                        logger.debug("Subscriber.closeOnEviction threw during reap", e);
                    }
                    reaped++;
                }
                if (entry.getValue().isEmpty()) {
                    subsByUser.remove(userName, entry.getValue());
                }
            }
            // Symmetric cleanup of the per-session index. We rely on
            // Subscription identity to remove only entries pointing at
            // already-dropped subscribers.
            for (Map.Entry<String, Set<Subscription>> e : subsBySession.entrySet()) {
                e.getValue().removeIf(s -> {
                    try { return !s.subscriber().isAlive(); } catch (Exception ex) { return true; }
                });
                if (e.getValue().isEmpty()) {
                    subsBySession.remove(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable t) {
            // Do not let a single bad subscriber take down the reaper thread.
            logger.warn("EventBus reaper crashed; will retry next interval", t);
        }
        if (reaped > 0) {
            logger.warn("EventBus reaper dropped {} dead subscriber(s)", reaped);
        }
        return reaped;
    }

    /** Visible for tests / metrics. */
    int subscriberCount(String userName) {
        Set<EventBusSubscriber> set = subsByUser.get(userName);
        return set == null ? 0 : set.size();
    }

    /** Visible for tests / metrics. */
    long lastSequence(String userName) {
        AtomicLong seq = seqByUser.get(userName);
        return seq == null ? 0L : seq.get();
    }

    /** Visible for tests. */
    EventRingBuffer bufferFor(String userName) {
        return bufferByUser.get(userName);
    }

    /**
     * Converts a Java Instant timestamp to a Protobuf Timestamp representation.
     *
     * @param instant the source Instant
     * @return constructed Protobuf Timestamp
     */
    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /** Opaque handle returned by {@link #subscribe} and consumed by {@link #unsubscribe}. */
    public static final class Subscription {
        private final String userName;
        private final String sessionToken;
        private final EventBusSubscriber subscriber;

        Subscription(String userName, String sessionToken, EventBusSubscriber subscriber) {
            this.userName = userName;
            this.sessionToken = sessionToken == null ? "" : sessionToken;
            this.subscriber = subscriber;
        }

        String userName() {
            return userName;
        }

        String sessionToken() {
            return sessionToken;
        }

        EventBusSubscriber subscriber() {
            return subscriber;
        }
    }
}
