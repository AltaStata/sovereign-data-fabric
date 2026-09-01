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
import com.altastata.grpc.proto.EventsServiceGrpc;
import com.altastata.grpc.proto.WatchRequest;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC implementation of {@code EventsService} (see {@code events.proto}).
 *
 * <p>Single RPC: {@link #watch(WatchRequest, StreamObserver)} — typed,
 * per-session, backed by {@link EventBus}. Each call registers a
 * {@link WatchSubscriber} that drains a bounded queue onto the gRPC stream;
 * on overflow the stream is closed with {@code RESOURCE_EXHAUSTED} and the
 * client is expected to reconnect with {@code WatchRequest.since_sequence}
 * set to its last seen sequence (the bus replays from the ring buffer or
 * emits {@code EventGapEvent}). See {@code SESSION_AND_EVENTS_DESIGN.md}
 * §7.5 / §7.6.
 *
 * <p>The pre-EventBus untyped {@code Subscribe} RPC was removed once both
 * first-party clients (Console UI and altastata-python-package) had
 * migrated to {@code Watch}; see {@code SESSION_AND_EVENTS_DESIGN.md}
 * §11 (PR-7) for the migration history.
 *
 * <p>Event-flow breadcrumbs are at WARN because runtime {@code logger.info}
 * from gRPC service singletons is silently dropped in this build (root cause
 * still unresolved; tracked separately).
 */
@Singleton
public class EventsGrpcService extends EventsServiceGrpc.EventsServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(EventsGrpcService.class);

    /** Per-subscriber queue size; matches §7.6 of the design doc. */
    static final int SUBSCRIBER_QUEUE_CAPACITY = 256;

    private final EventBus eventBus;

    /**
     * Constructs the EventsGrpcService with the specified EventBus instance.
     *
     * @param eventBus the EventBus to register subscribers on
     */
    public EventsGrpcService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * NEW: typed per-session event stream. The auth interceptor has already
     * validated the {@code sess-...} bearer and put {@link GrpcUserData} on
     * the context, so this method only needs to wire a subscriber to the bus.
     */
    @Override
    public void watch(WatchRequest request, StreamObserver<Event> responseObserver) {
        String userName;
        try {
            userName = GrpcServiceUtil.currentUserData().getAccountKey();
        } catch (Exception e) {
            // Should not happen — interceptor would have rejected the call.
            // Defensive only.
            responseObserver.onError(e);
            return;
        }
        if (userName == null || userName.isEmpty()) {
            responseObserver.onError(GrpcServiceUtil.unauthenticated(
                    "Watch requires an authenticated user"));
            return;
        }

        if (!(responseObserver instanceof ServerCallStreamObserver)) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Unsupported stream observer; gRPC server-streaming context expected")
                    .asRuntimeException());
            return;
        }
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<Event> serverObserver =
                (ServerCallStreamObserver<Event>) responseObserver;

        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        String sessionToken = session == null ? "" : session.token();

        WatchSubscriber subscriber = new WatchSubscriber(userName, serverObserver);
        EventBus.Subscription subscription = eventBus.subscribe(
                userName, sessionToken, subscriber, request.getSinceSequence());

        serverObserver.setOnCancelHandler(() -> {
            LOG.warn("EventsService.Watch: client cancelled, userName={}", userName);
            eventBus.unsubscribe(subscription);
            subscriber.shutdown();
        });

        subscriber.start();
        LOG.warn("EventsService.Watch: subscribed userName={} since_seq={}",
                userName, request.getSinceSequence());
    }

    /**
     * One open {@code Watch} stream. Owns a bounded {@link ArrayBlockingQueue}
     * fed by {@link #deliver(Event)} (called from the publisher thread) and
     * drained by a dedicated daemon thread that writes to the gRPC observer.
     *
     * <p>Decoupling publisher from drain lets a slow consumer (network blip,
     * paused tab) backpressure on its own queue without blocking other
     * sessions' deliveries; on overflow we close the stream with
     * {@code RESOURCE_EXHAUSTED} and the client recovers via
     * {@code since_sequence} replay.
     */
    static final class WatchSubscriber implements EventBusSubscriber {
        private final String userName;
        private final ServerCallStreamObserver<Event> observer;
        private final BlockingQueue<Event> queue =
                new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final AtomicBoolean overflowed = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread drainThread;

        WatchSubscriber(String userName, ServerCallStreamObserver<Event> observer) {
            this.userName = userName;
            this.observer = observer;
        }

        /**
         * Delivers an event to the subscriber.
         *
         * @param event The event to deliver
         */
        @Override
        public void deliver(Event event) {
            if (closed.get()) {
                return;
            }
            if (!queue.offer(event)) {
                if (overflowed.compareAndSet(false, true)) {
                    LOG.warn("WatchSubscriber overflow for userName={} (queue cap={}); closing stream",
                            userName, SUBSCRIBER_QUEUE_CAPACITY);
                }
                Thread t = drainThread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }

        /**
         * Starts the drain loop thread.
         */
        void start() {
            Thread t = new Thread(this::drainLoop, "altastata-watch-" + userName);
            t.setDaemon(true);
            this.drainThread = t;
            t.start();
        }

        /**
         * Shuts down the subscriber and its thread.
         */
        void shutdown() {
            if (closed.compareAndSet(false, true)) {
                Thread t = drainThread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }

        /**
         * Close the stream with {@code UNAUTHENTICATED} when the owning
         * session was superseded by a fresher Login from the same logical
         * client. {@code UNAUTHENTICATED} (status=16) is the same code the
         * auth interceptor emits for an invalid {@code sess-...} bearer, so
         * existing client retry logic naturally falls back to the bootstrap
         * + Login path with the new credentials instead of looping on a
         * dead token.
         */
        @Override
        public void closeOnEviction(String reason) {
            closeWithStatus(Status.UNAUTHENTICATED.withDescription(
                    reason == null || reason.isEmpty()
                            ? "Session evicted by newer login from same client"
                            : reason));
            Thread t = drainThread;
            if (t != null) {
                t.interrupt();
            }
        }

        /**
         * The transport is considered alive as long as we have not closed it
         * ourselves and gRPC's own cancel detection has not flipped. Once
         * HTTP/2 keepalive notices a dead peer it sets
         * {@link ServerCallStreamObserver#isCancelled} to {@code true}, even
         * if {@code setOnCancelHandler} did not fire (race with already-
         * completed observers). The {@link EventBus} reaper uses this to
         * drop the subscriber proactively rather than waiting for a 256-event
         * queue overflow.
         */
        @Override
        public boolean isAlive() {
            return !closed.get() && !observer.isCancelled();
        }

        /**
         * The loop that continuously drains events from the queue and sends them to the observer.
         */
        private void drainLoop() {
            try {
                while (!closed.get()) {
                    Event event = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (overflowed.get()) {
                        closeWithStatus(Status.RESOURCE_EXHAUSTED
                                .withDescription("Per-session event queue overflowed; reconnect with since_sequence"));
                        return;
                    }
                    if (event == null) {
                        continue;
                    }
                    if (observer.isCancelled() || closed.get()) {
                        return;
                    }
                    synchronized (observer) {
                        if (observer.isCancelled() || closed.get()) {
                            return;
                        }
                        observer.onNext(event);
                    }
                }
            } catch (InterruptedException ie) {
                if (overflowed.get()) {
                    closeWithStatus(Status.RESOURCE_EXHAUSTED
                            .withDescription("Per-session event queue overflowed; reconnect with since_sequence"));
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warn("WatchSubscriber drain loop crashed for userName={}", userName, e);
                closeWithStatus(Status.INTERNAL.withDescription("Watch drain failed: " + e.getClass().getSimpleName()));
            }
        }

        /**
         * Closes the subscriber with the given status.
         *
         * @param status The status to close with
         */
        private void closeWithStatus(Status status) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                synchronized (observer) {
                    if (!observer.isCancelled()) {
                        observer.onError(status.asRuntimeException());
                    }
                }
            } catch (Exception e) {
                LOG.debug("WatchSubscriber.close: observer.onError threw for userName={}", userName, e);
            }
        }
    }
}
