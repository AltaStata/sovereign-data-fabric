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

import com.altastata.api.AccountId;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.grpc.proto.IssueCredentialsRequest;
import com.altastata.grpc.proto.IssueCredentialsResponse;
import com.altastata.grpc.proto.ListMyCredentialsRequest;
import com.altastata.grpc.proto.ListMyCredentialsResponse;
import com.altastata.grpc.proto.RevokeCredentialsRequest;
import com.altastata.grpc.proto.RevokeCredentialsResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3CredentialsGrpcServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private SessionRegistry sessions;
    private S3CredentialsRegistry credentialsRegistry;
    private S3CredentialsGrpcService service;
    private AltaStataFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        sessions = new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(T0, ZoneOffset.UTC), false);
        credentialsRegistry = new S3CredentialsRegistry(sessions, Clock.fixed(T0, ZoneOffset.UTC));
        service = new S3CredentialsGrpcService(credentialsRegistry);
        fileSystem = mock(AltaStataFileSystem.class);
        when(fileSystem.getAccountId()).thenReturn(
                new AccountId("altastata-test-bob-", "bob", "amazon-s3-secure"));
    }

    @Test
    void issueCredentialsRequiresAuthenticatedContext() {
        Capture<IssueCredentialsResponse> obs = new Capture<>();
        service.issueCredentials(IssueCredentialsRequest.getDefaultInstance(), obs);
        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, obs.statusCode().getCode());
    }

    @Test
    void issueListAndRevokeCredentialsForSession() {
        Session session = sessions.create("bob", "tab");
        GrpcUserData userData = new GrpcUserData("bob");
        userData.setAltaStataFileSystem(fileSystem);

        Context context = Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, session)
                .withValue(GrpcGatewayAuthContext.USER_DATA, userData);

        context.run(() -> {
            Capture<IssueCredentialsResponse> issueObs = new Capture<>();
            service.issueCredentials(IssueCredentialsRequest.newBuilder()
                    .setLabel("snowflake")
                    .build(), issueObs);
            Assertions.assertNull(issueObs.error);
            Assertions.assertFalse(issueObs.value.getAccessKeyId().isEmpty());
            Assertions.assertFalse(issueObs.value.getSecretAccessKey().isEmpty());

            Capture<ListMyCredentialsResponse> listObs = new Capture<>();
            service.listMyCredentials(ListMyCredentialsRequest.getDefaultInstance(), listObs);
            Assertions.assertNull(listObs.error);
            Assertions.assertEquals(1, listObs.value.getCredentialsCount());
            Assertions.assertEquals("snowflake", listObs.value.getCredentials(0).getLabel());

            Capture<RevokeCredentialsResponse> revokeObs = new Capture<>();
            service.revokeCredentials(RevokeCredentialsRequest.newBuilder()
                    .setAccessKeyId(issueObs.value.getAccessKeyId())
                    .build(), revokeObs);
            Assertions.assertNull(revokeObs.error);
            Assertions.assertTrue(revokeObs.completed);

            Capture<ListMyCredentialsResponse> afterRevoke = new Capture<>();
            service.listMyCredentials(ListMyCredentialsRequest.getDefaultInstance(), afterRevoke);
            Assertions.assertEquals(0, afterRevoke.value.getCredentialsCount());
        });
    }

    private static final class Capture<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T v) {
            value = v;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        Status statusCode() {
            return ((StatusRuntimeException) error).getStatus();
        }
    }
}
