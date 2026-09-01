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
import com.altastata.grpc.proto.FileStatus;
import com.altastata.grpc.proto.RevokeByQueryRequest;
import com.altastata.grpc.proto.RevokeRequest;
import com.altastata.grpc.proto.RevokeResult;
import com.altastata.grpc.proto.ShareByQueryRequest;
import com.altastata.grpc.proto.ShareRequest;
import com.altastata.grpc.proto.ShareResult;
import com.altastata.grpc.proto.SharingServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class SharingGrpcService extends SharingServiceGrpc.SharingServiceImplBase {
    /**
      * Shares files with specified readers.
      * @param request the share request
      * @param responseObserver stream observer for result
      */
    @Override
    public void share(ShareRequest request, StreamObserver<ShareResult> responseObserver) {
        request.getFilePathsList().forEach(GrpcServiceUtil::validatePath);

        if (request.getReadersCount() == 0 || request.getFilePathsCount() == 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("file_paths and readers cannot be empty")
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
            String[] readers = request.getReadersList().toArray(new String[0]);
            List<CloudFileOperationStatus> statuses = fs.sharePaths(
                    request.getFilePathsList().toArray(new String[0]),
                    null,
                    null,
                    readers);
            List<FileStatus> allStatuses = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                allStatuses.add(toFileStatus("", status));
            }
            responseObserver.onNext(ShareResult.newBuilder().addAllStatuses(allStatuses).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Share failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
      * Revokes sharing for specified readers.
      * @param request the revoke request
      * @param responseObserver stream observer for result
      */
    @Override
    public void revoke(RevokeRequest request, StreamObserver<RevokeResult> responseObserver) {
        request.getFilePathsList().forEach(GrpcServiceUtil::validatePath);

        if (request.getReadersCount() == 0 || request.getFilePathsCount() == 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("file_paths and readers cannot be empty")
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
            String[] readers = request.getReadersList().toArray(new String[0]);
            List<CloudFileOperationStatus> statuses = fs.revokePaths(
                    request.getFilePathsList().toArray(new String[0]),
                    null,
                    null,
                    readers);
            List<FileStatus> allStatuses = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                allStatuses.add(toFileStatus("", status));
            }
            responseObserver.onNext(RevokeResult.newBuilder().addAllStatuses(allStatuses).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Revoke failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
      * Shares files matching a query with specified readers.
      * @param request the share by query request
      * @param responseObserver stream observer for result
      */
    @Override
    public void shareByQuery(ShareByQueryRequest request, StreamObserver<ShareResult> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        if (request.getReadersCount() == 0 || request.getCloudPathPrefix().isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("cloud_path_prefix and readers cannot be empty")
                    .asRuntimeException());
            return;
        }

        try {
            String[] readers = request.getReadersList().toArray(new String[0]);
            List<CloudFileOperationStatus> statuses = fs.share(
                    request.getCloudPathPrefix(),
                    request.getIncludingSubdirectories(),
                    request.getTimeIntervalStart().isEmpty() ? null : request.getTimeIntervalStart(),
                    request.getTimeIntervalEnd().isEmpty() ? null : request.getTimeIntervalEnd(),
                    readers);
            List<FileStatus> out = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                out.add(toFileStatus(request.getCloudPathPrefix(), status));
            }
            responseObserver.onNext(ShareResult.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Share by query failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
      * Revokes sharing for files matching a query.
      * @param request the revoke by query request
      * @param responseObserver stream observer for result
      */
    @Override
    public void revokeByQuery(RevokeByQueryRequest request, StreamObserver<RevokeResult> responseObserver) {
        GrpcServiceUtil.validatePath(request.getCloudPathPrefix());
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        if (request.getReadersCount() == 0 || request.getCloudPathPrefix().isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("cloud_path_prefix and readers cannot be empty")
                    .asRuntimeException());
            return;
        }

        try {
            String[] readers = request.getReadersList().toArray(new String[0]);
            List<CloudFileOperationStatus> statuses = fs.revokeReaderAccess(
                    request.getCloudPathPrefix(),
                    request.getIncludingSubdirectories(),
                    request.getTimeIntervalStart().isEmpty() ? null : request.getTimeIntervalStart(),
                    request.getTimeIntervalEnd().isEmpty() ? null : request.getTimeIntervalEnd(),
                    readers);
            List<FileStatus> out = new ArrayList<>();
            for (CloudFileOperationStatus status : statuses) {
                out.add(toFileStatus(request.getCloudPathPrefix(), status));
            }
            responseObserver.onNext(RevokeResult.newBuilder().addAllStatuses(out).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Revoke by query failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Converts a file operation status to a Protobuf FileStatus message.
     *
     * @param filePath fallback file path
     * @param status file operation status
     * @return constructed FileStatus proto
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
