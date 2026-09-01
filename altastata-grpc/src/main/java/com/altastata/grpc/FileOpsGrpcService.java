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
import com.altastata.utils.Constants;
import com.altastata.grpc.proto.AppendBufferToFileRequest;
import com.altastata.grpc.proto.AppendBufferToFileResponse;
import com.altastata.grpc.proto.AbortUploadRequest;
import com.altastata.grpc.proto.AbortUploadResponse;
import com.altastata.grpc.proto.BeginUploadRequest;
import com.altastata.grpc.proto.BeginUploadResponse;
import com.altastata.grpc.proto.CompleteUploadRequest;
import com.altastata.grpc.proto.CompleteUploadResponse;
import com.altastata.grpc.proto.CopyFileRequest;
import com.altastata.grpc.proto.CopyFileResponse;
import com.altastata.grpc.proto.CreateFileRequest;
import com.altastata.grpc.proto.CreateFileResponse;
import com.altastata.grpc.proto.DeleteByPathsRequest;
import com.altastata.grpc.proto.DeleteRequest;
import com.altastata.grpc.proto.DeleteResponse;
import com.altastata.grpc.proto.DownloadDirectoryAsZipChunk;
import com.altastata.grpc.proto.DownloadDirectoryAsZipRequest;
import com.altastata.grpc.proto.FileOpsServiceGrpc;
import com.altastata.grpc.proto.FileStatus;
import com.altastata.grpc.proto.GetBufferRequest;
import com.altastata.grpc.proto.GetBufferResponse;
import com.altastata.grpc.proto.ListVersionsRequest;
import com.altastata.grpc.proto.ReadStreamChunk;
import com.altastata.grpc.proto.ReadStreamRequest;
import com.altastata.grpc.proto.RetrieveRequest;
import com.altastata.grpc.proto.RetrieveResponse;
import com.altastata.grpc.proto.StoreRequest;
import com.altastata.grpc.proto.StoreResponse;
import com.altastata.grpc.proto.UploadChunkRequest;
import com.altastata.grpc.proto.UploadChunkResponse;
import com.altastata.grpc.proto.VersionEntry;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * gRPC Service handling high-performance, asynchronous file operations for AltaStata.
 *
 * `FileOpsGrpcService` is the primary binary interface for desktop, mobile, and backend 
 * clients to interact with the file system. It supports:
 * - Chunked, streaming file uploads and downloads.
 * - Directory metadata listing and traversal.
 * - Bulk operations (delete, copy, share).
 * - "Zip-on-the-fly" folder downloads.
 *
 * Security: Uses `GrpcGatewayAuthContext` to identify users and `GrpcServiceUtil` to 
 * mitigate path traversal attacks. Backpressure is managed through GRPC stream observers.
 */
@Singleton
public class FileOpsGrpcService extends FileOpsServiceGrpc.FileOpsServiceImplBase {
    /**
     * Cloud→pipe pump threads. At most two pumps run (current + read-ahead); extra
     * threads absorb small-file churn and metadata setup without blocking.
     */
    static final int ZIP_DOWNLOAD_PARALLEL_READS = 16;
    /**
     * Chunks fetched per AltaStata read batch inside each file ({@code readChunksTogether}).
     * Global download concurrency is capped at 100 in OpsExecutors; two read-ahead pumps
     * × 32 chunks ≈ 64 in-flight — still under that ceiling. Each batch may allocate up to
     * {@code parallelChunks × PLAIN_CHUNK_MAX_SIZE} (~256 MiB at 32); keep below ~48 on
     * memory-constrained JVMs.
     */
    static final int ZIP_READ_PARALLEL_CHUNKS = 32;
    /** Batch small ZipOutputStream writes before gRPC onNext (ZIP local headers stay tiny). */
    static final int ZIP_GRPC_AGGREGATE_SIZE = 256 * 1024;
    /** First read when {@link InputStream#available()} is 0 (blocking stream). */
    static final int ZIP_STREAM_COPY_BUFFER_DEFAULT = 8192;
    /** Backpressure between cloud pump and ZIP writer (2× max single read). */
    static final int ZIP_DOWNLOAD_PIPE_SIZE = Constants.PLAIN_CHUNK_MAX_SIZE() * 2;

    private static final Pattern VERSION_TIMESTAMP_PATTERN = Pattern.compile(".*_(\\d+)$");
    private final Map<String, Long> latestSnapshotByPath = new ConcurrentHashMap<>();
    private final UploadRegistry uploadRegistry;

    /**
     * Constructs a FileOpsGrpcService with the specified upload registry.
     *
     * @param uploadRegistry upload registry instance to track active multipart uploads
     */
    public FileOpsGrpcService(UploadRegistry uploadRegistry) {
        this.uploadRegistry = uploadRegistry;
    }

    /**
     * Creates a new cloud file with the provided payload content.
     *
     * @param request the request containing destination path and file bytes
     * @param responseObserver stream observer to send the CreateFileResponse
     */
    @Override
    public void createFile(CreateFileRequest request, StreamObserver<CreateFileResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getFilePath());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            CloudFileOperationStatus status = fs.createFile(request.getFilePath(), request.getContent().toByteArray());
            cacheLatestSnapshot(request.getFilePath(), status.getCloudFileVersionPath());
            responseObserver.onNext(CreateFileResponse.newBuilder().setStatus(toFileStatus(request.getFilePath(), status)).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Create file failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Initializes a new segmented multipart upload transaction on the gateway.
     *
     * @param request the request detailing destination cloud path
     * @param responseObserver stream observer to send the BeginUploadResponse
     */
    @Override
    public void beginUpload(BeginUploadRequest request, StreamObserver<BeginUploadResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPath());
        AltaStataFileSystem fs;
        Session session;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
            session = GrpcServiceUtil.currentSession();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            UploadRegistry.BeginResult started = uploadRegistry.begin(session.token(), request.getCloudPath(), fs);
            responseObserver.onNext(BeginUploadResponse.newBuilder()
                    .setUploadId(started.uploadId())
                    .setChunkSize(started.chunkSize())
                    .build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Begin upload failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Uploads an individual chunk byte array for an active multipart upload transaction.
     *
     * @param request the request detailing upload ID, offset, and payload
     * @param responseObserver stream observer to send the UploadChunkResponse
     */
    @Override
    public void uploadChunk(UploadChunkRequest request, StreamObserver<UploadChunkResponse> responseObserver) {
        Session session;
        try {
            session = GrpcServiceUtil.currentSession();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            long bytesReceived = uploadRegistry.uploadChunk(
                    session.token(),
                    request.getUploadId(),
                    request.getOffset(),
                    request.getData().toByteArray());
            responseObserver.onNext(UploadChunkResponse.newBuilder()
                    .setBytesReceived(bytesReceived)
                    .build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Upload chunk failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Finalizes and merges all uploaded chunks to complete the multipart upload transaction.
     *
     * @param request the request containing upload ID to finalize
     * @param responseObserver stream observer to send the CompleteUploadResponse
     */
    @Override
    public void completeUpload(CompleteUploadRequest request, StreamObserver<CompleteUploadResponse> responseObserver) {
        Session session;
        try {
            session = GrpcServiceUtil.currentSession();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            UploadRegistry.CompleteResult completed = uploadRegistry.complete(session.token(), request.getUploadId());
            cacheLatestSnapshot(completed.cloudPath(), completed.status().getCloudFileVersionPath());
            responseObserver.onNext(CompleteUploadResponse.newBuilder()
                    .setStatus(toFileStatus(completed.cloudPath(), completed.status()))
                    .build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Complete upload failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Aborts an active multipart upload transaction, deleting any uploaded chunk segments.
     *
     * @param request request containing target upload ID
     * @param responseObserver stream observer to send the AbortUploadResponse
     */
    @Override
    public void abortUpload(AbortUploadRequest request, StreamObserver<AbortUploadResponse> responseObserver) {
        Session session;
        try {
            session = GrpcServiceUtil.currentSession();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            boolean aborted = uploadRegistry.abort(session.token(), request.getUploadId());
            responseObserver.onNext(AbortUploadResponse.newBuilder().setAborted(aborted).build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Abort upload failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Reads a specific block/buffer of bytes from a cloud file version.
     *
     * @param request request containing file path, snapshot, offset, and size parameters
     * @param responseObserver stream observer to send the GetBufferResponse
     */
    @Override
    public void getBuffer(GetBufferRequest request, StreamObserver<GetBufferResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getFilePath());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            byte[] data = fs.getBuffer(
                    request.getFilePath(),
                    snapshot,
                    request.getStartPosition(),
                    request.getParallelChunks() <= 0 ? 4 : request.getParallelChunks(),
                    request.getSize(),
                    request.getTrustCachedSize());
            responseObserver.onNext(GetBufferResponse.newBuilder().setData(ByteString.copyFrom(data)).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Get buffer failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Deletes a cloud file prefix, supporting recursive directory deletions.
     *
     * @param request request detailing prefix path and options
     * @param responseObserver stream observer to send the DeleteResponse
     */
    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            List<CloudFileOperationStatus> statuses = fs.delete(
                    request.getCloudPathPrefix(),
                    request.getIncludingSubdirectories(),
                    request.getTimeIntervalStart().isEmpty() ? null : request.getTimeIntervalStart(),
                    request.getTimeIntervalEnd().isEmpty() ? null : request.getTimeIntervalEnd());
            List<FileStatus> out = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                out.add(toFileStatus(request.getCloudPathPrefix(), status));
            }
            responseObserver.onNext(DeleteResponse.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Delete failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Deletes an explicit list of cloud file paths.
     *
     * @param request request with file paths and optional time filters
     * @param responseObserver stream observer to send the DeleteResponse
     */
    @Override
    public void deleteByPaths(DeleteByPathsRequest request, StreamObserver<DeleteResponse> responseObserver) {
        request.getFilePathsList().forEach(GrpcServiceUtil::validatePath);

        if (request.getFilePathsCount() == 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("file_paths cannot be empty")
                    .asRuntimeException());
            return;
        }

        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            String timeStart = request.getTimeIntervalStart().isEmpty() ? null : request.getTimeIntervalStart();
            String timeEnd = request.getTimeIntervalEnd().isEmpty() ? null : request.getTimeIntervalEnd();
            List<CloudFileOperationStatus> statuses = fs.deletePaths(
                    request.getFilePathsList().toArray(new String[0]),
                    timeStart,
                    timeEnd);
            List<FileStatus> out = new ArrayList<>();
            for (int i = 0; i < statuses.size(); i++) {
                String path = i < request.getFilePathsCount()
                        ? request.getFilePaths(i)
                        : request.getFilePaths(request.getFilePathsCount() - 1);
                out.add(toFileStatus(path, statuses.get(i)));
            }
            responseObserver.onNext(DeleteResponse.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Delete by paths failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Lists version historical entries for a specified cloud file path.
     *
     * @param request request containing target prefix and filters
     * @param responseObserver stream observer to send the list of VersionEntry
     */
    @Override
    public void listVersions(ListVersionsRequest request, StreamObserver<VersionEntry> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Iterator<String[]> iterator = fs.listCloudFilesVersions(
                    request.getCloudPathPrefix(),
                    request.getIncludingSubdirectories(),
                    request.getTimeIntervalStart().isEmpty() ? null : request.getTimeIntervalStart(),
                    request.getTimeIntervalEnd().isEmpty() ? null : request.getTimeIntervalEnd());
            while (iterator.hasNext()) {
                String[] versions = iterator.next();
                responseObserver.onNext(VersionEntry.newBuilder().addAllVersions(java.util.Arrays.asList(versions)).build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("List versions failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Appends a buffer of bytes directly onto an existing cloud file version.
     *
     * @param request request containing target file path, snapshot, and bytes to append
     * @param responseObserver stream observer to send the AppendBufferToFileResponse
     */
    @Override
    public void appendBufferToFile(AppendBufferToFileRequest request, StreamObserver<AppendBufferToFileResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getFilePath());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            Long snapshot;
            if (request.getSnapshotTime() <= 0) {
                snapshot = latestSnapshotByPath.get(request.getFilePath());
                if (snapshot == null) {
                    snapshot = resolveLatestSnapshotTime(fs, request.getFilePath());
                }
            } else {
                snapshot = request.getSnapshotTime();
            }
            if (snapshot == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Append target file has no existing versions: " + request.getFilePath())
                        .asRuntimeException());
                return;
            }
            fs.appendBufferToFile(request.getFilePath(), snapshot, request.getContent().toByteArray());
            latestSnapshotByPath.put(request.getFilePath(), snapshot);
            responseObserver.onNext(AppendBufferToFileResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Append buffer failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Extracts and caches the timestamp suffix from a newly created file version path.
     *
     * @param logicalPath logical cloud file path
     * @param versionPath concrete physical version path
     */
    private void cacheLatestSnapshot(String logicalPath, String versionPath) {
        if (logicalPath == null || logicalPath.isBlank()) return;
        if (versionPath == null || versionPath.isBlank()) return;
        Matcher matcher = VERSION_TIMESTAMP_PATTERN.matcher(versionPath);
        if (!matcher.matches()) return;
        long ts = Long.parseLong(matcher.group(1));
        latestSnapshotByPath.put(logicalPath, ts);
    }

    /**
     * Resolves the latest snapshot creation timestamp suffix for the specified file.
     *
     * @param fs filesystem instance
     * @param filePath cloud path to list versions for
     * @return resolved timestamp Long, or null if none
     */
    private Long resolveLatestSnapshotTime(AltaStataFileSystem fs, String filePath) {
        Iterator<String[]> iterator = fs.listCloudFilesVersions(filePath, false, null, null);
        Long latest = null;
        while (iterator != null && iterator.hasNext()) {
            String[] versions = iterator.next();
            if (versions == null) continue;
            for (String versionPath : versions) {
                if (versionPath == null || versionPath.isBlank()) continue;
                Matcher matcher = VERSION_TIMESTAMP_PATTERN.matcher(versionPath);
                if (!matcher.matches()) continue;
                long ts = Long.parseLong(matcher.group(1));
                if (latest == null || ts > latest) latest = ts;
            }
        }
        return latest;
    }

    /**
     * Uploads local files or folders directly into a target cloud directory prefix.
     *
     * @param request request containing local paths and target cloud destination
     * @param responseObserver stream observer to send the StoreResponse
     */
    @Override
    public void store(StoreRequest request, StreamObserver<StoreResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        request.getLocalFilesOrDirectoriesList().forEach(GrpcServiceUtil::validatePath);
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            List<CloudFileOperationStatus> statuses = fs.store(
                    request.getLocalFilesOrDirectoriesList(),
                    request.getLocalFsPrefix(),
                    request.getCloudPathPrefix(),
                    request.getWaitUntilDone());
            List<FileStatus> out = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                out.add(toFileStatus(request.getCloudPathPrefix(), status));
            }
            responseObserver.onNext(StoreResponse.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Store failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Downloads/retrieves a cloud prefix recursively into a local target directory.
     *
     * @param request request containing output directory, cloud prefix, snapshot, and flags
     * @param responseObserver stream observer to send the RetrieveResponse
     */
    @Override
    public void retrieve(RetrieveRequest request, StreamObserver<RetrieveResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        GrpcServiceUtil.validatePath(request.getOutputDir());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            List<CloudFileOperationStatus> statuses = fs.retrieve(
                    request.getOutputDir(),
                    request.getCloudPathPrefix(),
                    request.getIncludingSubdirectories(),
                    snapshot,
                    request.getIsStreaming(),
                    request.getWaitUntilDone());
            List<FileStatus> out = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                out.add(toFileStatus(request.getCloudPathPrefix(), status));
            }
            responseObserver.onNext(RetrieveResponse.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Retrieve failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Copies a secure cloud file from a source location to a destination.
     *
     * @param request request specifying source and destination paths
     * @param responseObserver stream observer to send the CopyFileResponse
     */
    @Override
    public void copyFile(CopyFileRequest request, StreamObserver<CopyFileResponse> responseObserver) {
        GrpcServiceUtil.validatePath(request.getFromCloudFilePath());
        GrpcServiceUtil.validatePath(request.getToCloudFilePath());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        try {
            CloudFileOperationStatus status = fs.copyFile(
                    request.getFromCloudFilePath(),
                    request.getToCloudFilePath());
            responseObserver.onNext(CopyFileResponse.newBuilder()
                    .setStatus(toFileStatus(request.getToCloudFilePath(), status))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Copy file failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Reads a cloud file securely as a chunked data stream, returning parts through the observer.
     *
     * @param request request defining the file path and streaming settings
     * @param responseObserver stream observer to send the ReadStreamChunk objects
     */
    @Override
    public void readStream(ReadStreamRequest request, StreamObserver<ReadStreamChunk> responseObserver) {
        GrpcServiceUtil.validatePath(request.getFilePath());
        final AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }
        final int chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : 8 * 1024 * 1024;
        final String filePath = request.getFilePath();
        final Long snapshotTime = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
        final long startPosition = request.getStartPosition();
        final int parallelChunks = request.getParallelChunks() <= 0 ? 4 : request.getParallelChunks();
        final boolean trustCachedSize = request.getTrustCachedSize();

        // Blocking cloud decrypt/read must not run on the Armeria worker: the HTTP/gRPC
        // response would not flush until the first in.read() returns, stalling the
        // browser at 0 bytes and risking HTTP/2 backpressure deadlocks.
        Thread worker = new Thread(() -> {
            try (InputStream in = fs.getFileInputStream(
                    filePath,
                    snapshotTime,
                    startPosition,
                    parallelChunks,
                    trustCachedSize)) {
                byte[] buf = new byte[chunkSize];
                int n;
                while ((n = in.read(buf)) != -1) {
                    responseObserver.onNext(ReadStreamChunk.newBuilder()
                            .setData(ByteString.copyFrom(buf, 0, n))
                            .build());
                }
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Read stream failed: " + e.getMessage())
                        .asRuntimeException());
            }
        }, "readStream-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void downloadDirectoryAsZip(DownloadDirectoryAsZipRequest request,
                                       StreamObserver<DownloadDirectoryAsZipChunk> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        final AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        String cloudPrefix = request.getCloudPathPrefix() == null ? "" : request.getCloudPathPrefix().trim();
        while (cloudPrefix.startsWith("/")) {
            cloudPrefix = cloudPrefix.substring(1);
        }
        while (cloudPrefix.endsWith("/")) {
            cloudPrefix = cloudPrefix.substring(0, cloudPrefix.length() - 1);
        }
        final String basePrefix = cloudPrefix;
        final String prefixWithSlash = basePrefix.isEmpty() ? "" : basePrefix + "/";

        Thread worker = new Thread(() -> {
            StreamingZipChunkOutputStream chunkingOut =
                    new StreamingZipChunkOutputStream(responseObserver);
            int filesWritten = 0;
            try {
                Iterator<String[]> iterator = fs.listCloudFilesVersions(basePrefix, true, null, null);
                List<ZipFileWorkItem> workItems = collectZipWorkItems(iterator, basePrefix, prefixWithSlash);
                if (workItems.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("No files found in directory")
                            .asRuntimeException());
                    return;
                }

                int parallelism = Math.min(ZIP_DOWNLOAD_PARALLEL_READS, workItems.size());
                ExecutorService readPool = Executors.newFixedThreadPool(parallelism, r -> {
                    Thread t = new Thread(r, "downloadDirectoryAsZip-read");
                    t.setDaemon(true);
                    return t;
                });
                ZipFilePipePump currentPump = null;
                ZipFilePipePump nextPump = null;
                try {
                    try (ZipOutputStream zos = new ZipOutputStream(chunkingOut)) {
                        // Small text files: skip deflate CPU; bytes still stream out immediately.
                        zos.setLevel(Deflater.NO_COMPRESSION);
                        for (int i = 0; i < workItems.size(); i++) {
                            if (currentPump == null) {
                                currentPump = startZipFilePipePump(fs, readPool, workItems.get(i).versionedPath);
                            }
                            if (i + 1 < workItems.size()) {
                                nextPump = startZipFilePipePump(fs, readPool, workItems.get(i + 1).versionedPath);
                            }
                            ZipFileWorkItem item = workItems.get(i);
                            try (InputStream in = currentPump.input) {
                                zos.putNextEntry(new ZipEntry(item.relativePath));
                                transferStream(in, zos);
                                zos.closeEntry();
                                zos.flush();
                            }
                            currentPump.awaitDone();
                            currentPump = nextPump;
                            nextPump = null;
                            filesWritten++;
                        }
                        zos.finish();
                    }
                    chunkingOut.flush();
                } finally {
                    if (currentPump != null) {
                        currentPump.abort();
                    }
                    if (nextPump != null) {
                        nextPump.abort();
                    }
                    readPool.shutdownNow();
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Download directory ZIP failed: " + cause.getMessage())
                        .asRuntimeException());
                return;
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Download directory ZIP failed: " + e.getMessage())
                        .asRuntimeException());
                return;
            }

            if (filesWritten == 0) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("No files found in directory")
                        .asRuntimeException());
                return;
            }

            responseObserver.onCompleted();
        }, "downloadDirectoryAsZip-worker");
        worker.setDaemon(true);
        worker.start();
    }

    static final class ZipFileWorkItem {
        final String relativePath;
        final String versionedPath;

        ZipFileWorkItem(String relativePath, String versionedPath) {
            this.relativePath = relativePath;
            this.versionedPath = versionedPath;
        }
    }

    static List<ZipFileWorkItem> collectZipWorkItems(Iterator<String[]> iterator,
                                                     String basePrefix,
                                                     String prefixWithSlash) {
        List<ZipFileWorkItem> workItems = new ArrayList<>();
        if (iterator == null) {
            return workItems;
        }
        while (iterator.hasNext()) {
            String[] versions = iterator.next();
            String versionedPath = pickLatestVersionedPath(versions);
            if (versionedPath == null) continue;

            int sepIdx = versionedPath.indexOf('\u2739');
            if (sepIdx < 0) continue;
            String cloudPathBase = versionedPath.substring(0, sepIdx);
            if (!prefixWithSlash.isEmpty() && !cloudPathBase.startsWith(prefixWithSlash)) continue;
            if (cloudPathBase.equals(basePrefix)) continue;

            String relativeRaw = prefixWithSlash.isEmpty()
                    ? cloudPathBase
                    : cloudPathBase.substring(prefixWithSlash.length());
            String relativePath = sanitizeZipRelativePath(relativeRaw);
            if (relativePath == null) continue;

            workItems.add(new ZipFileWorkItem(relativePath, versionedPath));
        }
        return workItems;
    }

    /**
     * Pump cloud file bytes into a bounded pipe on a worker thread so the ZIP writer
     * can consume chunk-by-chunk while the next chunks are still downloading.
     */
    static ZipFilePipePump startZipFilePipePump(AltaStataFileSystem fs,
                                                ExecutorService readPool,
                                                String versionedPath) throws IOException {
        PipedInputStream pin = new PipedInputStream(ZIP_DOWNLOAD_PIPE_SIZE);
        PipedOutputStream pout = new PipedOutputStream(pin);
        Future<Void> pumpFuture = readPool.submit(() -> {
            try (InputStream cloud = fs.getFileInputStream(
                    versionedPath, null, 0L, ZIP_READ_PARALLEL_CHUNKS, true);
                 OutputStream out = pout) {
                transferStream(cloud, out);
            } catch (Exception e) {
                try {
                    pin.close();
                } catch (IOException ignored) {
                    // best-effort
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e);
            }
            return null;
        });
        return new ZipFilePipePump(pin, pumpFuture);
    }

    /**
     * Size a copy buffer from what the stream reports as ready: small files get small
     * arrays, large reads cap at {@link Constants#PLAIN_CHUNK_MAX_SIZE()}.
     */
    static int chooseStreamCopyBufferSize(InputStream in) throws IOException {
        int max = Constants.PLAIN_CHUNK_MAX_SIZE();
        int avail = in.available();
        if (avail <= 0) {
            return Math.min(ZIP_STREAM_COPY_BUFFER_DEFAULT, max);
        }
        return Math.min(avail, max);
    }

    /**
     * Copies all bytes from an InputStream to an OutputStream using an automatically sized transfer buffer.
     *
     * @param in source stream
     * @param out destination stream
     * @throws IOException if read or write operations fail
     */
    static void transferStream(InputStream in, OutputStream out) throws IOException {
        int max = Constants.PLAIN_CHUNK_MAX_SIZE();
        byte[] buf = new byte[chooseStreamCopyBufferSize(in)];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (n == buf.length && buf.length < max) {
                int nextSize = chooseStreamCopyBufferSize(in);
                if (nextSize > buf.length) {
                    buf = new byte[nextSize];
                }
            }
        }
    }

    static final class ZipFilePipePump {
        final PipedInputStream input;
        private final Future<Void> pumpFuture;

        ZipFilePipePump(PipedInputStream input, Future<Void> pumpFuture) {
            this.input = input;
            this.pumpFuture = pumpFuture;
        }

        /**
         * Waits for the pump operation to complete.
         *
         * @throws ExecutionException If the operation fails
         * @throws InterruptedException If the thread is interrupted
         */
        void awaitDone() throws ExecutionException, InterruptedException {
            pumpFuture.get();
        }

        /**
         * Aborts the pump operation and closes the input stream.
         */
        void abort() {
            pumpFuture.cancel(true);
            try {
                input.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /**
     * Sanitizes a relative path inside a ZIP archive to prevent path traversals and normalize separators.
     *
     * @param relative raw entry relative path
     * @return sanitized relative path, or null if invalid
     */
    private static String sanitizeZipRelativePath(String relative) {
        if (relative == null) return null;
        String stripped = relative.trim();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        if (stripped.isEmpty()) return null;
        String[] parts = stripped.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Resolves the latest versioned path from an array of versioned cloud paths based on sequence token comparison.
     *
     * @param versions array of versioned paths
     * @return the resolved latest version path
     */
    private static String pickLatestVersionedPath(String[] versions) {
        if (versions == null) return null;
        String bestPath = null;
        String bestToken = null;
        for (String versionedPath : versions) {
            if (versionedPath == null || versionedPath.isEmpty()) continue;
            int sepIdx = versionedPath.indexOf('\u2739');
            if (sepIdx < 0) continue;
            String versionToken = versionedPath.substring(sepIdx + 1);
            if (versionToken.isEmpty()) continue;
            if (bestToken == null || versionToken.compareTo(bestToken) > 0) {
                bestToken = versionToken;
                bestPath = versionedPath;
            }
        }
        return bestPath;
    }

    /**
     * Stream ZIP bytes to gRPC. Aggregates sub-chunk writes to cut protobuf overhead;
     * {@link #flush()} after each ZIP entry keeps first-byte latency acceptable.
     */
    private static final class StreamingZipChunkOutputStream extends OutputStream {
        private final StreamObserver<DownloadDirectoryAsZipChunk> observer;
        private final byte[] aggregate = new byte[ZIP_GRPC_AGGREGATE_SIZE];
        private int aggregateLen = 0;

        StreamingZipChunkOutputStream(StreamObserver<DownloadDirectoryAsZipChunk> observer) {
            this.observer = observer;
        }

        /**
         * Writes a single byte to the output stream.
         *
         * @param b The byte to write
         * @throws IOException If an I/O error occurs
         */
        @Override
        public void write(int b) throws IOException {
            if (aggregateLen >= aggregate.length) {
                flushAggregate();
            }
            aggregate[aggregateLen++] = (byte) b;
        }

        /**
         * Writes a portion of an array of bytes to the output stream.
         *
         * @param data The data to write
         * @param off The start offset in the data
         * @param len The number of bytes to write
         * @throws IOException If an I/O error occurs
         */
        @Override
        public void write(byte[] data, int off, int len) throws IOException {
            if (len <= 0) return;
            while (len > 0) {
                if (aggregateLen >= aggregate.length) {
                    flushAggregate();
                }
                int n = Math.min(len, aggregate.length - aggregateLen);
                System.arraycopy(data, off, aggregate, aggregateLen, n);
                aggregateLen += n;
                off += n;
                len -= n;
            }
        }

        /**
         * Flushes the output stream, forcing any buffered bytes to be written out.
         *
         * @throws IOException If an I/O error occurs
         */
        @Override
        public void flush() throws IOException {
            flushAggregate();
        }

        /**
         * Flushes the aggregate buffer to the observer.
         */
        private void flushAggregate() {
            if (aggregateLen <= 0) return;
            observer.onNext(DownloadDirectoryAsZipChunk.newBuilder()
                    .setData(ByteString.copyFrom(aggregate, 0, aggregateLen))
                    .build());
            aggregateLen = 0;
        }
    }

    /**
     * Converts a raw CloudFileOperationStatus object into a Protobuf FileStatus structure.
     *
     * @param filePath the logical file path
     * @param status the underlying operation status
     * @return constructed Protobuf FileStatus
     */
    private FileStatus toFileStatus(String filePath, CloudFileOperationStatus status) {
        String resolvedPath = status.getCloudFileVersionPath() == null || status.getCloudFileVersionPath().isEmpty()
                ? filePath : status.getCloudFileVersionPath();
        String error = status.getError() == null ? "" : String.valueOf(status.getError());
        return FileStatus.newBuilder()
                .setFilePath(resolvedPath)
                .setOperationState(String.valueOf(status.getOperationState()))
                .setError(error)
                .build();
    }
}
