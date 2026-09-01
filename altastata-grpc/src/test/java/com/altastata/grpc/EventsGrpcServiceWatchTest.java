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

import com.altastata.api.AltaStataFileSystem;
import com.altastata.grpc.proto.Event;
import com.altastata.grpc.proto.EventsServiceGrpc;
import com.altastata.grpc.proto.FileSharedEvent;
import com.altastata.grpc.proto.WatchRequest;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end test for {@code EventsService.Watch} via a real gRPC in-process
 * transport. Wires:
 *
 * <ul>
 *   <li>Real {@link EventBus}</li>
 *   <li>Real {@link EventsGrpcService} (including {@code WatchSubscriber} +
 *       drain thread + bounded queue)</li>
 *   <li>A passthrough auth interceptor that pre-installs a fake
 *       {@link GrpcUserData} on the gRPC {@link io.grpc.Context}, mimicking
 *       what {@link GrpcGatewayAuthInterceptor} would do for a valid
 *       {@code sess-...} bearer.</li>
 * </ul>
 *
 * <p>This test verifies the wire-level behaviour that the unit tests cannot:
 * the gRPC stream actually carries proto-encoded {@code Event} messages from
 * a publisher on the server side to a real client stub, and the
 * {@code setOnCancelHandler} unsubscribe path runs when the client cancels.
 */
class EventsGrpcServiceWatchTest {

    private Server server;
    private ManagedChannel channel;
    private EventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        bus = new EventBus(EventBus.DEFAULT_RING_SIZE, java.time.Clock.systemUTC());

        EventsGrpcService eventsService = new EventsGrpcService(bus);

        // Passthrough auth: install a real-looking GrpcUserData("bob") so
        // EventsGrpcService.watch can resolve currentUserData from the
        // gRPC Context exactly like production does.
        GrpcUserData bob = new GrpcUserData("bob");
        // Stub fs so currentUserData() doesn't need it; only userName matters.
        bob.setAltaStataFileSystem(org.mockito.Mockito.mock(AltaStataFileSystem.class));
        ServerInterceptor authPassthrough = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                Context ctx = Context.current()
                        .withValue(GrpcGatewayAuthContext.USER_DATA, bob)
                        .withValue(GrpcGatewayAuthContext.ACCOUNT_KEY, "bob");
                return Contexts.interceptCall(ctx, call, headers, next);
            }
        };

        String name = "events-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(eventsService, authPassthrough))
                .directExecutor()
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void watchDeliversPublishedEventsToTheClientStreamInOrder() throws Exception {
        EventsServiceGrpc.EventsServiceStub stub = EventsServiceGrpc.newStub(channel);

        CountDownLatch threeEvents = new CountDownLatch(3);
        CopyOnWriteArrayList<Event> received = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        stub.watch(WatchRequest.newBuilder().setSinceSequence(0).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(Event value) {
                        received.add(value);
                        threeEvents.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        // Give the server a moment to register the subscription before we publish.
        for (int i = 0; i < 50 && bus.subscriberCount("bob") == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        Assertions.assertEquals(1, bus.subscriberCount("bob"),
                "Watch must register exactly one subscriber on the bus");

        bus.publish("bob", Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId("f1")), "");
        bus.publish("bob", Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId("f2")), "");
        bus.publish("bob", Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId("f3")), "");

        Assertions.assertTrue(threeEvents.await(5, TimeUnit.SECONDS),
                "client should have received all 3 events; received=" + received.size()
                        + " error=" + error.get());
        Assertions.assertNull(error.get(), () -> "client error: " + error.get());
        Assertions.assertEquals(3, received.size());
        Assertions.assertEquals("f1", received.get(0).getFileShared().getFileId());
        Assertions.assertEquals(1, received.get(0).getSequence());
        Assertions.assertEquals(2, received.get(1).getSequence());
        Assertions.assertEquals(3, received.get(2).getSequence());
    }

    @Test
    void watchSinceSequenceReplaysFromTheBufferAndContinuesLive() throws Exception {
        // Pre-publish three events before any subscriber exists.
        for (int i = 1; i <= 3; i++) {
            bus.publish("bob", Event.newBuilder().setFileShared(
                    FileSharedEvent.newBuilder().setFileId("f" + i)), "");
        }

        EventsServiceGrpc.EventsServiceStub stub = EventsServiceGrpc.newStub(channel);

        // since=1 -> replay seq 2 and 3 (the 2 events strictly after cursor 1).
        // Plus one live event published after subscription => 3 client onNexts.
        CountDownLatch threeEvents = new CountDownLatch(3);
        CopyOnWriteArrayList<Event> received = new CopyOnWriteArrayList<>();

        stub.watch(WatchRequest.newBuilder().setSinceSequence(1).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(Event value) {
                        received.add(value);
                        threeEvents.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        for (int i = 0; i < 50 && bus.subscriberCount("bob") == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        bus.publish("bob", Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId("f4")), "");

        Assertions.assertTrue(threeEvents.await(5, TimeUnit.SECONDS),
                "should replay seq 2,3 and live-deliver seq 4; received=" + received.size());
        Assertions.assertEquals(3, received.size());
        Assertions.assertEquals(2, received.get(0).getSequence());
        Assertions.assertEquals(3, received.get(1).getSequence());
        Assertions.assertEquals(4, received.get(2).getSequence(),
                "live event seq=4 must follow the two replayed events");
    }

    @Test
    void clientCancelTriggersServerSideUnsubscribe() throws Exception {
        EventsServiceGrpc.EventsServiceStub stub = EventsServiceGrpc.newStub(channel);

        AtomicReference<io.grpc.Context.CancellableContext> cancelHandle = new AtomicReference<>();
        CountDownLatch firstEvent = new CountDownLatch(1);
        CopyOnWriteArrayList<Event> received = new CopyOnWriteArrayList<>();

        io.grpc.Context.CancellableContext clientContext =
                io.grpc.Context.current().withCancellation();
        cancelHandle.set(clientContext);

        clientContext.run(() ->
                stub.watch(WatchRequest.newBuilder().build(), new StreamObserver<>() {
                    @Override
                    public void onNext(Event value) {
                        received.add(value);
                        firstEvent.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                }));

        for (int i = 0; i < 50 && bus.subscriberCount("bob") == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        Assertions.assertEquals(1, bus.subscriberCount("bob"));

        bus.publish("bob", Event.newBuilder().setFileShared(
                FileSharedEvent.newBuilder().setFileId("f1")), "");
        Assertions.assertTrue(firstEvent.await(5, TimeUnit.SECONDS));

        // Cancel the client side — server's setOnCancelHandler must unsubscribe.
        cancelHandle.get().cancel(new RuntimeException("client cancelled"));

        for (int i = 0; i < 100 && bus.subscriberCount("bob") != 0; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        Assertions.assertEquals(0, bus.subscriberCount("bob"),
                "server-side unsubscribe must run on client cancel");
    }
}
