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
import com.altastata.api.AltaStataEventListener;
import com.altastata.grpc.proto.Event;
import com.altastata.grpc.proto.FileSharedEvent;
import com.altastata.grpc.proto.FileUnsharedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the legacy untyped {@link AltaStataEventListener} stream emitted from
 * {@code SecureCloudEventProcessor} (in {@code altastata-core}) to the new typed
 * {@link EventBus}, so that {@code EventsService.Watch} subscribers receive
 * production cross-user events.
 *
 * <p>One adapter is created per {@code userName} when the user's
 * {@code AltaStataFileSystem} is first installed in {@link GrpcUserRegistry}, and
 * registered as a listener on that filesystem's event-listener slot. From that
 * point on, every {@link AltaStataEvent} fired by altastata-core for this user
 * is translated into an {@link Event} on the bus:
 *
 * <ul>
 *   <li>{@code "SHARE"} ({@link com.altastata.utils.Constants#EVENT_SHARE()}) →
 *       {@link FileSharedEvent} — Alice gave this user access to a file.</li>
 *   <li>{@code "DELETE"} ({@link com.altastata.utils.Constants#EVENT_DELETE()}) →
 *       {@link FileUnsharedEvent} — Alice revoked this user's access (today's
 *       legacy "deleted" semantics, see {@code SESSION_AND_EVENTS_DESIGN.md}
 *       §7.3 / PR-3 trim notes).</li>
 *   <li>Anything else is logged at {@code DEBUG} and ignored. We deliberately do
 *       not enrich the bus with event types that have no consumer (see
 *       {@code SESSION_AND_EVENTS_DESIGN.md} §7.1, the {@code FileCreatedEvent}
 *       / {@code FileModifiedEvent} / {@code UserGroupChangedEvent} were
 *       removed from the proto for the same reason).</li>
 * </ul>
 *
 * <p><strong>Known limitation: {@code shared_by} / {@code unshared_by} are
 * empty.</strong> The {@link AltaStataEvent} fired by altastata-core today
 * carries only {@code (eventName, objectPath)}; the originator userName is
 * available inside {@code SecureCloudEventProcessor.processChange} (parsed
 * from the change-object path) but never propagated to the listener. Filling
 * the originator field would require either (a) widening
 * {@link AltaStataEvent} in altastata-core, or (b) a metadata lookup in this
 * adapter against {@code storageObjectMetadata.storageAttrs.dataOwner}. Both
 * are deliberately out of scope for PR-4; the frontend can show "Someone
 * shared a file with you" until enrichment lands.
 */
final class AltaStataEventBusAdapter implements AltaStataEventListener {
    private static final Logger logger = LoggerFactory.getLogger(AltaStataEventBusAdapter.class);

    /**
     * Mirror of {@code com.altastata.utils.Constants.EVENT_SHARE} / {@code EVENT_DELETE}.
     * Hardcoded as Java string literals because those constants are defined on a
     * Scala {@code object} (cross-language access is awkward) and the values are
     * stable wire-level identifiers, not configuration. If altastata-core ever
     * renames them, this adapter will silently stop translating — which will
     * surface immediately in the {@code unknown event name} DEBUG log and in
     * any integration test that publishes through a real fs.
     */
    static final String EVENT_NAME_SHARE = "SHARE";
    static final String EVENT_NAME_DELETE = "DELETE";

    private final String userName;
    private final EventBus eventBus;

    AltaStataEventBusAdapter(String userName, EventBus eventBus) {
        if (userName == null || userName.isEmpty()) {
            throw new IllegalArgumentException("userName must be non-empty");
        }
        if (eventBus == null) {
            throw new IllegalArgumentException("eventBus must not be null");
        }
        this.userName = userName;
        this.eventBus = eventBus;
    }

    /**
     * Receives notifications of file operations (such as file sharing or deletion events)
     * and publishes them onto the gateway-wide gRPC EventBus.
     *
     * @param event the triggered AltaStataEvent
     */
    @Override
    public void notify(AltaStataEvent event) {
        if (event == null) {
            logger.warn("AltaStataEventBusAdapter.notify(null) for userName={}", userName);
            return;
        }
        String eventName = event.getEventName();
        if (eventName == null) {
            logger.warn("AltaStataEventBusAdapter.notify with null eventName for userName={}", userName);
            return;
        }
        String path = stringifyData(event.getData());
        logger.warn("AltaStataEventBusAdapter.notify userName={} name='{}' data='{}'",
                userName, eventName, path);

        switch (eventName) {
            case EVENT_NAME_SHARE:
                eventBus.publish(userName, Event.newBuilder()
                                .setFileShared(FileSharedEvent.newBuilder()
                                        .setFileId(path)
                                        .setFilePath(path)),
                        "");
                logger.warn("AltaStataEventBusAdapter: published FileSharedEvent userName={} path='{}'",
                        userName, path);
                break;

            case EVENT_NAME_DELETE:
                eventBus.publish(userName, Event.newBuilder()
                                .setFileUnshared(FileUnsharedEvent.newBuilder()
                                        .setFileId(path)),
                        "");
                logger.warn("AltaStataEventBusAdapter: published FileUnsharedEvent userName={} path='{}'",
                        userName, path);
                break;

            default:
                logger.warn("AltaStataEventBusAdapter: ignoring unknown event name='{}' for userName={}",
                        eventName, userName);
                break;
        }
    }

    /**
     * Converts event payload data securely into a String representation.
     *
     * @param data the raw data payload
     * @return string representation of data, or empty string if null
     */
    private static String stringifyData(Object data) {
        return data == null ? "" : String.valueOf(data);
    }
}
