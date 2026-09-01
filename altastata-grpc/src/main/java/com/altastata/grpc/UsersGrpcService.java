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

import com.altastata.grpc.proto.Empty;
import com.altastata.grpc.proto.GetMyAccountRequest;
import com.altastata.grpc.proto.GetUserRequest;
import com.altastata.grpc.proto.SetPasswordRequest;
import com.altastata.grpc.proto.SetPasswordResponse;
import com.altastata.grpc.proto.User;
import com.altastata.grpc.proto.UserSummary;
import com.altastata.grpc.proto.UsersServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

@Singleton
public class UsersGrpcService extends UsersServiceGrpc.UsersServiceImplBase {
    private final GrpcUserRegistry registry;

    /**
     * Constructs UsersGrpcService with the specified user profile registry.
     *
     * @param registry user profiles registry
     */
    public UsersGrpcService(GrpcUserRegistry registry) {
        this.registry = registry;
    }

    /**
     * Lists all existing user accounts and their initialization status.
     *
     * @param request empty request message
     * @param responseObserver stream observer returning UserSummary elements
     */
    @Override
    public void listUsers(Empty request, StreamObserver<UserSummary> responseObserver) {
        GrpcUserData current = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        if (current == null || current.getAltaStataFileSystem() == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated user")
                    .asRuntimeException());
            return;
        }

        var lakeId = current.getAltaStataFileSystem().getAccountId();
        for (String userName : current.getAltaStataFileSystem().listUsers()) {
            GrpcUserData data = registry.findLiveInSameLake(lakeId, userName);
            responseObserver.onNext(UserSummary.newBuilder()
                    .setUserName(userName)
                    .setInitialized(data != null && data.getAltaStataFileSystem() != null)
                    .build());
        }
        responseObserver.onCompleted();
    }

    /**
     * Resolves and gets user account details for the specified user name.
     *
     * @param request target user request message
     * @param responseObserver stream observer returning User metadata
     */
    @Override
    public void getUser(GetUserRequest request, StreamObserver<User> responseObserver) {
        GrpcUserData current = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        if (current == null || current.getAltaStataFileSystem() == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated user")
                    .asRuntimeException());
            return;
        }

        String requestedUser = request.getUserName();
        if (requestedUser == null || requestedUser.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("user_name cannot be empty")
                    .asRuntimeException());
            return;
        }

        if (!current.getAltaStataFileSystem().listUsers().contains(requestedUser)) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("User not found: " + requestedUser)
                    .asRuntimeException());
            return;
        }

        GrpcUserData data = registry.findLiveInSameLake(
                current.getAltaStataFileSystem().getAccountId(), requestedUser);
        responseObserver.onNext(User.newBuilder()
                .setUserName(requestedUser)
                .setInitialized(data != null && data.getAltaStataFileSystem() != null)
                .setAccessKey(data == null || data.getAccessKey() == null ? "" : data.getAccessKey())
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Retrieves account information for the currently authenticated session user.
     *
     * @param request empty request message
     * @param responseObserver stream observer returning current User metadata
     */
    @Override
    public void getMyAccount(GetMyAccountRequest request, StreamObserver<User> responseObserver) {
        GrpcUserData current = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        if (current == null) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription("No authenticated user").asRuntimeException());
            return;
        }

        // Session / registry key is AccountId; API still exposes myuser.
        String userName = "";
        if (current.getAltaStataFileSystem() != null
                && current.getAltaStataFileSystem().getAccountId() != null) {
            userName = current.getAltaStataFileSystem().getAccountId().getMyUser();
        }

        responseObserver.onNext(User.newBuilder()
                .setUserName(userName)
                .setInitialized(current.getAltaStataFileSystem() != null)
                .setAccessKey(current.getAccessKey() == null ? "" : current.getAccessKey())
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Unlocks the user's secure account context by setting/validating the master key password.
     *
     * @param request password setting request message
     * @param responseObserver stream observer returning success status
     */
    @Override
    public void setPassword(SetPasswordRequest request, StreamObserver<SetPasswordResponse> responseObserver) {
        GrpcUserData current = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        if (current == null || current.getAltaStataFileSystem() == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated user")
                    .asRuntimeException());
            return;
        }
        if (request.getAccountPassword().isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("account_password cannot be empty")
                    .asRuntimeException());
            return;
        }
        try {
            current.getAltaStataFileSystem().setPassword(request.getAccountPassword());
            responseObserver.onNext(SetPasswordResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Set password failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
