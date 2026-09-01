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

/**
 * One open consumer of the {@link EventBus} for a single {@code userName}.
 * Production wiring is the {@code WatchSubscriber} inner class in
 * {@code EventsGrpcService}, which forwards events to a gRPC server-streaming
 * {@code StreamObserver} via a bounded queue and a drain thread. Tests can
 * implement this interface inline to assert delivery and ordering.
 *
 * <p>The bus calls {@link #deliver(Event)} synchronously from the publisher
 * thread, so implementations must not block — the typical implementation
 * offers the event to a bounded queue and returns immediately, signalling
 * overflow if the queue is full.
 */
public interface EventBusSubscriber {
    /**
     * Hand off one event for asynchronous delivery to the wire. Must not
     * block; on backpressure the implementation should detect overflow and
     * begin shutting itself down (e.g. by closing the gRPC stream with
     * {@code RESOURCE_EXHAUSTED}).
     */
    void deliver(Event event);

    /**
     * Called by {@link EventBus#evictSession} when the owning session has
     * been replaced by a newer Login from the same {@code (userName, clientHint)}.
     * Implementations should close their underlying transport (e.g. the gRPC
     * server-streaming observer) with a non-retryable status so the client
     * stops reconnecting on the now-invalid token. Must not block.
     *
     * <p>Default is a no-op so test fakes implementing only {@link #deliver}
     * keep compiling.
     */
    default void closeOnEviction(String reason) {}

    /**
     * Best-effort liveness probe used by the {@link EventBus} reaper to drop
     * subscribers whose underlying transport has died without
     * {@link io.grpc.stub.ServerCallStreamObserver#setOnCancelHandler}
     * firing — the typical case is a TCP connection that lost its peer
     * silently (laptop suspend, kill -9, broken NAT) and only got noticed by
     * HTTP/2 keepalive. Implementations should return {@code false} as soon
     * as the transport is unusable; the bus will then call
     * {@link #closeOnEviction(String)} (idempotent) and unsubscribe. Must
     * not block.
     *
     * <p>Default is {@code true} so in-memory test subscribers that have no
     * "transport" semantics keep being delivered to.
     */
    default boolean isAlive() { return true; }
}
