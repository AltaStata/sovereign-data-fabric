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
import com.altastata.grpc.proto.CredentialSummary;
import com.altastata.grpc.proto.IssueCredentialsRequest;
import com.altastata.grpc.proto.IssueCredentialsResponse;
import com.altastata.grpc.proto.ListMyCredentialsRequest;
import com.altastata.grpc.proto.ListMyCredentialsResponse;
import com.altastata.grpc.proto.RevokeCredentialsRequest;
import com.altastata.grpc.proto.RevokeCredentialsResponse;
import com.altastata.grpc.proto.S3CredentialsServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC implementation of {@code S3CredentialsService} (see {@code s3_credentials.proto}).
 */
@Singleton
public class S3CredentialsGrpcService extends S3CredentialsServiceGrpc.S3CredentialsServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(S3CredentialsGrpcService.class);

    private final S3CredentialsRegistry credentialsRegistry;

    /**
     * Constructs S3CredentialsGrpcService with the specified registry.
     *
     * @param credentialsRegistry registry of active S3 session credentials
     */
    public S3CredentialsGrpcService(S3CredentialsRegistry credentialsRegistry) {
        this.credentialsRegistry = credentialsRegistry;
    }

    @Override
    public void issueCredentials(
            IssueCredentialsRequest request,
            StreamObserver<IssueCredentialsResponse> responseObserver) {
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        GrpcUserData userData = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        if (session == null || userData == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Bearer session required")
                    .asRuntimeException());
            return;
        }
        AltaStataFileSystem fs = userData.getAltaStataFileSystem();
        if (fs == null) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Account is not logged in on this gateway")
                    .asRuntimeException());
            return;
        }
        try {
            S3CredentialsRegistry.IssuedCredential issued = credentialsRegistry.issue(
                    session.token(),
                    session.accountKey(),
                    fs,
                    request.getLabel());
            logger.warn("IssueCredentials: issued access key for account={} session={}",
                    session.accountKey(), session.token());
            responseObserver.onNext(IssueCredentialsResponse.newBuilder()
                    .setAccessKeyId(issued.accessKeyId())
                    .setSecretAccessKey(issued.secretAccessKey())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listMyCredentials(
            ListMyCredentialsRequest request,
            StreamObserver<ListMyCredentialsResponse> responseObserver) {
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        if (session == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Bearer session required")
                    .asRuntimeException());
            return;
        }
        ListMyCredentialsResponse.Builder builder = ListMyCredentialsResponse.newBuilder();
        for (S3CredentialsRegistry.IssuedCredential cred
                : credentialsRegistry.listForSessionToken(session.token())) {
            builder.addCredentials(CredentialSummary.newBuilder()
                    .setAccessKeyId(cred.accessKeyId())
                    .setLabel(cred.label() == null ? "" : cred.label())
                    .setCreatedAt(toProto(cred.createdAt()))
                    .build());
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void revokeCredentials(
            RevokeCredentialsRequest request,
            StreamObserver<RevokeCredentialsResponse> responseObserver) {
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        if (session == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Bearer session required")
                    .asRuntimeException());
            return;
        }
        if (request.getAccessKeyId() == null || request.getAccessKeyId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("access_key_id is required")
                    .asRuntimeException());
            return;
        }
        boolean removed = credentialsRegistry.revokeForSession(
                session.token(), request.getAccessKeyId());
        if (!removed) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Credential not found for this session")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(RevokeCredentialsResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Converts a Java Instant timestamp to a Protobuf Timestamp model.
     *
     * @param instant Java instant timestamp
     * @return Protobuf timestamp equivalent
     */
    private static Timestamp toProto(java.time.Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
