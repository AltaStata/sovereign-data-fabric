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
import com.altastata.api.CloudFileOperationStatus;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks in-flight chunked uploads addressed by an opaque upload id.
 *
 * <p>Each upload session is owned by one authenticated gRPC session token.
 * Calls from a different bearer token are rejected.
 */
@Singleton
public class UploadRegistry {
    private static final Logger logger = LoggerFactory.getLogger(UploadRegistry.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);
    static final int DEFAULT_CHUNK_SIZE_BYTES = 8 * 1024 * 1024;

    private final Duration ttl;
    private final Clock clock;
    private final ScheduledExecutorService sweeper;
    private final Map<String, UploadSession> byId = new ConcurrentHashMap<>();

    /**
      * Constructs a new UploadRegistry.
      * @param ttl upload session time-to-live
      */
    public UploadRegistry(@Value("${grpcgateway.upload.session-ttl:PT10M}") Duration ttl) {
        this(ttl, Clock.systemUTC(), true);
    }

    UploadRegistry(Duration ttl, Clock clock, boolean enableSweeper) {
        this.ttl = ttl;
        this.clock = clock;
        if (enableSweeper) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "altastata-upload-sweeper");
                t.setDaemon(true);
                return t;
            });
            long ms = SWEEP_INTERVAL.toMillis();
            sweeper.scheduleWithFixedDelay(this::sweep, ms, ms, TimeUnit.MILLISECONDS);
            logger.info("UploadRegistry: ttl={}, sweep every {}", ttl, SWEEP_INTERVAL);
        } else {
            this.sweeper = null;
        }
    }

    /**
     * Begins a multipart file upload session.
     *
     * @param ownerSessionToken gateway session token initiating the upload
     * @param cloudPath target cloud storage file path
     * @param fs active filesystem context
     * @return BeginResult containing unique upload ID and recommended chunk size
     * @throws IOException if any I/O errors occur during file creation or stream initialization
     */
    BeginResult begin(String ownerSessionToken, String cloudPath, AltaStataFileSystem fs) throws IOException {
        if (ownerSessionToken == null || ownerSessionToken.isBlank()) {
            throw GrpcServiceUtil.unauthenticated("missing owner session token");
        }
        if (cloudPath == null || cloudPath.isBlank()) {
            throw GrpcServiceUtil.failedPrecondition("cloud_path cannot be empty");
        }

        CloudFileOperationStatus created = fs.createFile(cloudPath, new byte[0]);
        Long snapshot = parseSnapshot(created);
        if (snapshot == null) {
            throw new IOException("cannot resolve snapshot for newly created file: " + cloudPath);
        }

        OutputStream out = fs.getFileOutputStream(cloudPath, snapshot, true);
        String uploadId = mintUploadId();
        UploadSession session = new UploadSession(uploadId, ownerSessionToken, cloudPath, created, fs, out, clock.instant());
        byId.put(uploadId, session);
        return new BeginResult(uploadId, DEFAULT_CHUNK_SIZE_BYTES);
    }

    /**
     * Uploads and writes a single chunk payload to the active file stream.
     *
     * @param ownerSessionToken gateway session token
     * @param uploadId target multipart upload session ID
     * @param offset expected write byte offset
     * @param data chunk payload bytes
     * @return current total bytes received so far
     * @throws IOException if offset mismatch or stream write failures occur
     */
    long uploadChunk(String ownerSessionToken, String uploadId, long offset, byte[] data) throws IOException {
        UploadSession session = require(uploadId, ownerSessionToken);
        if (data == null) data = new byte[0];

        synchronized (session) {
            if (offset != session.bytesReceived) {
                throw new IOException("offset mismatch: expected=" + session.bytesReceived + " got=" + offset);
            }
            if (data.length > 0) {
                session.out.write(data);
                session.bytesReceived += data.length;
            }
            session.touch(clock.instant());
            return session.bytesReceived;
        }
    }

    /**
     * Completes and finalizes the multipart upload session.
     *
     * @param ownerSessionToken gateway session token
     * @param uploadId target multipart upload session ID
     * @return CompleteResult containing the final cloud path and status
     * @throws IOException if final flush or close stream operations fail
     */
    CompleteResult complete(String ownerSessionToken, String uploadId) throws IOException {
        UploadSession session = require(uploadId, ownerSessionToken);
        byId.remove(uploadId, session);

        synchronized (session) {
            try {
                session.out.flush();
                session.out.close();
            } catch (IOException e) {
                abortInternal(session, true);
                throw e;
            }
            session.closed = true;
            return new CompleteResult(session.cloudPath, session.createdStatus);
        }
    }

    /**
     * Aborts the multipart upload, closing and discarding any partially written data.
     *
     * @param ownerSessionToken gateway session token
     * @param uploadId target multipart upload session ID
     * @return true if successfully aborted
     */
    boolean abort(String ownerSessionToken, String uploadId) {
        UploadSession session = require(uploadId, ownerSessionToken);
        byId.remove(uploadId, session);
        return abortInternal(session, true);
    }

    /**
     * Shuts down the background sweeper and aborts all active upload sessions.
     */
    @PreDestroy
    void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        for (UploadSession session : byId.values()) {
            abortInternal(session, false);
        }
        byId.clear();
    }

    /**
     * Periodically cleans up and discards expired inactive upload sessions.
     */
    void sweep() {
        Instant now = clock.instant();
        for (UploadSession session : byId.values()) {
            if (!session.isExpired(now, ttl)) continue;
            if (byId.remove(session.uploadId, session)) {
                logger.info("Aborting expired upload session {} for {}", session.uploadId, session.cloudPath);
                abortInternal(session, false);
            }
        }
    }

    /**
     * Gets current active upload sessions count.
     *
     * @return registry sessions count
     */
    int size() {
        return byId.size();
    }

    /**
     * Resolves and verifies ownership of the given upload ID.
     *
     * @param uploadId upload session ID
     * @param ownerSessionToken gateway session token
     * @return retrieved active upload session
     */
    private UploadSession require(String uploadId, String ownerSessionToken) {
        if (ownerSessionToken == null || ownerSessionToken.isBlank()) {
            throw GrpcServiceUtil.unauthenticated("Missing bearer token");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw GrpcServiceUtil.failedPrecondition("upload_id cannot be empty");
        }
        UploadSession session = byId.get(uploadId);
        if (session == null) {
            throw GrpcServiceUtil.failedPrecondition("unknown upload_id: " + uploadId);
        }
        if (!session.ownerSessionToken.equals(ownerSessionToken)) {
            throw GrpcServiceUtil.unauthenticated("upload session belongs to another bearer session");
        }
        return session;
    }

    /**
     * Implements internal abort logic for closing streams and deleting partially uploaded versions.
     *
     * @param session target upload session
     * @param deleteCloudVersion whether partial files should be cleaned from cloud storage
     * @return true if aborted successfully
     */
    private boolean abortInternal(UploadSession session, boolean deleteCloudVersion) {
        synchronized (session) {
            if (!session.closed) {
                try {
                    session.out.close();
                } catch (IOException e) {
                    logger.debug("Upload close during abort failed ({}): {}", session.uploadId, e.getMessage());
                }
                session.closed = true;
            }
            if (deleteCloudVersion) {
                String versionPath = session.createdStatus.getCloudFileVersionPath();
                if (versionPath != null && !versionPath.isBlank()) {
                    try {
                        session.fs.delete(versionPath, false, null, null);
                    } catch (Exception e) {
                        logger.warn("Failed to delete partial upload version {}: {}", versionPath, e.getMessage());
                    }
                }
            }
            return true;
        }
    }

    /**
     * Parses snapshot timestamp creation ID from operation status metadata.
     *
     * @param created file creation status
     * @return numeric timestamp, or null if unparseable
     */
    private static Long parseSnapshot(CloudFileOperationStatus created) {
        if (created == null) return null;
        String createdAt = created.getCloudFileCreateTime();
        if (createdAt == null || createdAt.isBlank()) return null;
        try {
            return Long.parseLong(createdAt);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Mints a cryptographically secure, unique multipart upload session ID.
     *
     * @return unique upload ID string
     */
    private static String mintUploadId() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return "upl-" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Data record summarizing multipart upload start results.
     *
     * @param uploadId multipart upload ID
     * @param chunkSize default recommended chunk size
     */
    record BeginResult(String uploadId, int chunkSize) {}

    /**
     * Data record summarizing finished upload completion results.
     *
     * @param cloudPath final uploaded file path
     * @param status final operation status metadata
     */
    record CompleteResult(String cloudPath, CloudFileOperationStatus status) {}

    private static final class UploadSession {
        private final String uploadId;
        private final String ownerSessionToken;
        private final String cloudPath;
        private final CloudFileOperationStatus createdStatus;
        private final AltaStataFileSystem fs;
        private final OutputStream out;

        private volatile Instant lastTouchedAt;
        private long bytesReceived;
        private boolean closed;

        private UploadSession(
                String uploadId,
                String ownerSessionToken,
                String cloudPath,
                CloudFileOperationStatus createdStatus,
                AltaStataFileSystem fs,
                OutputStream out,
                Instant now
        ) {
            this.uploadId = uploadId;
            this.ownerSessionToken = ownerSessionToken;
            this.cloudPath = cloudPath;
            this.createdStatus = createdStatus;
            this.fs = fs;
            this.out = out;
            this.bytesReceived = 0;
            this.closed = false;
            this.lastTouchedAt = now;
        }

        private void touch(Instant now) {
            this.lastTouchedAt = now;
        }

        /**
          * Checks if a time instant is expired based on a TTL.
          * @param now current time
          * @param ttl time-to-live
          * @return true if expired
          */
        private boolean isExpired(Instant now, Duration ttl) {
            return !now.isBefore(lastTouchedAt.plus(ttl));
        }
    }
}
