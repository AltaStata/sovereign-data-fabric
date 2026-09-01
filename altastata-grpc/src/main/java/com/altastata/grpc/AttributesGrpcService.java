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
import com.altastata.grpc.proto.Attribute;
import com.altastata.grpc.proto.AttributeMap;
import com.altastata.grpc.proto.AttributesServiceGrpc;
import com.altastata.grpc.proto.DeleteAttributeRequest;
import com.altastata.grpc.proto.Empty;
import com.altastata.grpc.proto.GetAttributeRequest;
import com.altastata.grpc.proto.GetAttributesRequest;
import com.altastata.grpc.proto.SetAttributeRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class AttributesGrpcService extends AttributesServiceGrpc.AttributesServiceImplBase {
    /**
     * Retrieves a single custom file attribute for a specified cloud file path.
     *
     * @param request the request containing target path and attribute name
     * @param responseObserver stream observer to send the custom Attribute
     */
    @Override
    public void getAttribute(GetAttributeRequest request, StreamObserver<Attribute> responseObserver) {
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            String value = fs.getFileAttribute(request.getFilePath(), snapshot, request.getName());
            if (value == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Attribute not found: " + request.getName())
                        .asRuntimeException());
                return;
            }
            responseObserver.onNext(Attribute.newBuilder()
                    .setName(request.getName())
                    .setValue(value)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Get attribute failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Retrieves multiple custom file attributes in bulk for a specified cloud file path.
     *
     * @param request the request containing target path and list of attribute names
     * @param responseObserver stream observer to send the collected AttributeMap
     */
    @Override
    public void getAttributes(GetAttributesRequest request, StreamObserver<AttributeMap> responseObserver) {
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            Map<String, String> values = fs.getFileAttributes(request.getFilePath(), snapshot, request.getNamesList());
            AttributeMap.Builder builder = AttributeMap.newBuilder();
            if (values != null) {
                builder.putAllAttributes(values);
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Get attributes failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Sets or updates a custom file attribute for a specified cloud file path.
     *
     * @param request request containing target path, attribute name, and value
     * @param responseObserver stream observer to send completion signal
     */
    @Override
    public void setAttribute(SetAttributeRequest request, StreamObserver<Empty> responseObserver) {
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            fs.setFileAttribute(request.getFilePath(), snapshot, request.getName(), request.getValue());
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Set attribute failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Deletes a custom file attribute for a specified cloud file path.
     *
     * @param request request containing target path and attribute name to delete
     * @param responseObserver stream observer to send completion signal
     */
    @Override
    public void deleteAttribute(DeleteAttributeRequest request, StreamObserver<Empty> responseObserver) {
        AltaStataFileSystem fs;
        try {
            fs = GrpcServiceUtil.currentFileSystem();
        } catch (Exception e) {
            responseObserver.onError(e);
            return;
        }

        try {
            Long snapshot = request.getSnapshotTime() <= 0 ? null : request.getSnapshotTime();
            fs.deleteFileAttribute(request.getFilePath(), snapshot, request.getName());
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Delete attribute failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
