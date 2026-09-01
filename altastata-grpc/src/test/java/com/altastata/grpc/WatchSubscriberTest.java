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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EventsGrpcService.WatchSubscriber}: drain-thread
 * delivery to the gRPC observer, cancellation, and overflow handling.
 *
 * <p>Tests use a Mockito {@link ServerCallStreamObserver} double; they exercise
 * the same code path that the real gRPC server-streaming machinery drives,
 * but without a transport, so we can deterministically assert ordering and
 * onError semantics.
 */
class WatchSubscriberTest {

    private static Event ev(long seq) {
        return Event.newBuilder()
                .setSequence(seq)
                .setFileShared(FileSharedEvent.newBuilder().setFileId("f-" + seq))
                .build();
    }

    /** Capturing observer double: not cancelled, records every onNext / onError. */
    @SuppressWarnings("unchecked")
    private static ServerCallStreamObserver<Event> mockObserver(List<Event> deliveries) {
        ServerCallStreamObserver<Event> obs = mock(ServerCallStreamObserver.class);
        when(obs.isCancelled()).thenReturn(false);
        doAnswer(inv -> {
            deliveries.add(inv.getArgument(0));
            return null;
        }).when(obs).onNext(any());
        return obs;
    }

    @Test
    void deliverEnqueuesEventsAndDrainThreadForwardsThemInOrder() throws InterruptedException {
        List<Event> delivered = new CopyOnWriteArrayList<>();
        ServerCallStreamObserver<Event> obs = mockObserver(delivered);

        EventsGrpcService.WatchSubscriber sub = new EventsGrpcService.WatchSubscriber("bob", obs);
        sub.start();
        try {
            sub.deliver(ev(1));
            sub.deliver(ev(2));
            sub.deliver(ev(3));

            verify(obs, timeout(2_000).times(3)).onNext(any());

            Assertions.assertEquals(3, delivered.size());
            Assertions.assertEquals(1, delivered.get(0).getSequence());
            Assertions.assertEquals(2, delivered.get(1).getSequence());
            Assertions.assertEquals(3, delivered.get(2).getSequence());
            verify(obs, never()).onError(any());
        } finally {
            sub.shutdown();
        }
    }

    @Test
    void shutdownStopsTheDrainThreadAndDropsLaterDeliveries() throws InterruptedException {
        List<Event> delivered = new CopyOnWriteArrayList<>();
        ServerCallStreamObserver<Event> obs = mockObserver(delivered);

        EventsGrpcService.WatchSubscriber sub = new EventsGrpcService.WatchSubscriber("bob", obs);
        sub.start();
        sub.deliver(ev(1));
        verify(obs, timeout(2_000).times(1)).onNext(any());

        sub.shutdown();
        TimeUnit.MILLISECONDS.sleep(100);   // let drain loop observe shutdown

        sub.deliver(ev(2));
        TimeUnit.MILLISECONDS.sleep(200);

        Assertions.assertEquals(1, delivered.size(),
                "no further events must be delivered after shutdown");
        verify(obs, never()).onError(any());
    }

    @Test
    void cancelledObserverPreventsOnNextCalls() throws InterruptedException {
        List<Event> delivered = new CopyOnWriteArrayList<>();
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<Event> obs = mock(ServerCallStreamObserver.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(obs.isCancelled()).thenAnswer(inv -> cancelled.get());
        doAnswer(inv -> {
            delivered.add(inv.getArgument(0));
            return null;
        }).when(obs).onNext(any());

        EventsGrpcService.WatchSubscriber sub = new EventsGrpcService.WatchSubscriber("bob", obs);
        sub.start();
        try {
            sub.deliver(ev(1));
            verify(obs, timeout(2_000).times(1)).onNext(any());

            cancelled.set(true);
            sub.deliver(ev(2));
            sub.deliver(ev(3));
            TimeUnit.MILLISECONDS.sleep(300);

            Assertions.assertEquals(1, delivered.size(),
                    "no onNext after observer is cancelled");
        } finally {
            sub.shutdown();
        }
    }

    @Test
    void overflowingTheBoundedQueueClosesStreamWithResourceExhausted() throws InterruptedException {
        // Block the drain thread inside onNext so the queue can fill up.
        CountDownLatch holdDrain = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<Event> obs = mock(ServerCallStreamObserver.class);
        when(obs.isCancelled()).thenReturn(false);
        doAnswer(inv -> {
            holdDrain.await();
            return null;
        }).when(obs).onNext(any());

        EventsGrpcService.WatchSubscriber sub = new EventsGrpcService.WatchSubscriber("bob", obs);
        sub.start();
        try {
            // Push more than the queue capacity (256) plus the one held by drain.
            int totalPushes = EventsGrpcService.SUBSCRIBER_QUEUE_CAPACITY + 8;
            for (int i = 0; i < totalPushes; i++) {
                sub.deliver(ev(i + 1));
            }

            // Release the drain so it observes overflow and emits onError.
            holdDrain.countDown();

            ArgumentCaptor<Throwable> err = ArgumentCaptor.forClass(Throwable.class);
            verify(obs, timeout(2_000)).onError(err.capture());

            StatusRuntimeException sre = (StatusRuntimeException) err.getValue();
            Assertions.assertEquals(Status.Code.RESOURCE_EXHAUSTED, sre.getStatus().getCode());
            Assertions.assertNotNull(sre.getStatus().getDescription());
            Assertions.assertTrue(sre.getStatus().getDescription().contains("since_sequence"),
                    "overflow error should hint clients to reconnect with since_sequence");
        } finally {
            sub.shutdown();
        }
    }

    @Test
    void deliverIsNonBlockingFromTheCallerSPerspective() {
        List<Event> delivered = new CopyOnWriteArrayList<>();
        ServerCallStreamObserver<Event> obs = mockObserver(delivered);

        EventsGrpcService.WatchSubscriber sub = new EventsGrpcService.WatchSubscriber("bob", obs);
        // No start() — drain thread never runs, queue cannot drain.
        try {
            // Fill exactly to capacity.
            for (int i = 0; i < EventsGrpcService.SUBSCRIBER_QUEUE_CAPACITY; i++) {
                sub.deliver(ev(i + 1));
            }
            // Past capacity: must NOT block — overflow path is a non-blocking
            // offer + flag set + interrupt.
            long before = System.nanoTime();
            sub.deliver(ev(9999));
            long elapsedMs = (System.nanoTime() - before) / 1_000_000;
            Assertions.assertTrue(elapsedMs < 250,
                    "deliver must not block on overflow; elapsed=" + elapsedMs + "ms");
        } finally {
            sub.shutdown();
        }
    }
}
