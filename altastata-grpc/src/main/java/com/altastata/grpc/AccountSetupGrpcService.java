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

import com.altastata.grpc.proto.AccountSetupServiceGrpc;
import com.altastata.grpc.proto.AccountType;
import com.altastata.grpc.proto.ChangePasswordRequest;
import com.altastata.grpc.proto.ChangePasswordResponse;
import com.altastata.grpc.proto.DeleteAccountRequest;
import com.altastata.grpc.proto.DeleteAccountResponse;
import com.altastata.grpc.proto.ExportAccountRequest;
import com.altastata.grpc.proto.ExportAccountResponse;
import com.altastata.grpc.proto.GenerateKeysRequest;
import com.altastata.grpc.proto.GenerateKeysResponse;
import com.altastata.grpc.proto.GetSupportedAccountTypesRequest;
import com.altastata.grpc.proto.GetSupportedAccountTypesResponse;
import com.altastata.grpc.proto.UpdateUserPropertiesRequest;
import com.altastata.grpc.proto.UpdateUserPropertiesResponse;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * gRPC implementation of {@code AccountSetupService} (see {@code account_setup.proto}).
 *
 * <p>Design: {@code altastata-grpc/CONSOLE_ACCOUNT_SETUP_DESIGN.md}.
 */
@Singleton
public class AccountSetupGrpcService extends AccountSetupServiceGrpc.AccountSetupServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(AccountSetupGrpcService.class);
    private static final String NOT_IMPLEMENTED = "AccountSetupService not implemented yet";

    private final GenerateKeysService generateKeysService;
    private final AccountMaintenanceService maintenanceService;

    public AccountSetupGrpcService(GenerateKeysService generateKeysService,
                                   AccountMaintenanceService maintenanceService) {
        this.generateKeysService = generateKeysService;
        this.maintenanceService = maintenanceService;
    }

    AccountSetupGrpcService(GenerateKeysService generateKeysService) {
        this(generateKeysService, null);
    }

    /**
     * Default constructor for AccountSetupGrpcService, initializing GenerateKeysService and default maintenance service.
     */
    public AccountSetupGrpcService() {
        this(new GenerateKeysService());
    }

    @Override
    public void getSupportedAccountTypes(
            GetSupportedAccountTypesRequest request,
            StreamObserver<GetSupportedAccountTypesResponse> responseObserver) {
        GetSupportedAccountTypesResponse.Builder builder =
                GetSupportedAccountTypesResponse.newBuilder();
        for (AccountType type : AccountSetupSupport.supportedAccountTypes()) {
            builder.addAccountTypes(type);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void generateKeys(
            GenerateKeysRequest request,
            StreamObserver<GenerateKeysResponse> responseObserver) {
        try {
            GenerateKeysService.Result result = generateKeysService.generate(request);
            GenerateKeysResponse.Builder builder = GenerateKeysResponse.newBuilder()
                    .setSuggestedDisplayName(result.suggestedDisplayName());
            for (Map.Entry<String, byte[]> entry : result.accountFiles().entrySet()) {
                builder.putAccountFiles(entry.getKey(), ByteString.copyFrom(entry.getValue()));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SecurityException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            logger.warn("GenerateKeys failed (cause: {})", e.getClass().getSimpleName());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage() == null ? "Key generation failed" : e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void changePassword(
            ChangePasswordRequest request,
            StreamObserver<ChangePasswordResponse> responseObserver) {
        if (maintenanceService == null) {
            unimplemented(responseObserver);
            return;
        }
        try {
            // Bootstrap like GenerateKeys: re-encrypt key files in a local directory.
            // No LoginV2 / *user.properties. (Legacy session-based callers should
            // pass user_account_directory of the co-located account folder.)
            Map<String, byte[]> accountFiles;
            if (request.getUserAccountDirectory() != null
                    && !request.getUserAccountDirectory().isBlank()) {
                accountFiles = maintenanceService.changePasswordInDirectory(
                        request.getUserAccountDirectory(),
                        request.getCurrentPassword(),
                        request.getNewPassword());
            } else {
                Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
                GrpcUserData userData = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
                if (session == null || userData == null) {
                    throw new IllegalArgumentException("user_account_directory is required when not logged in");
                }
                accountFiles = maintenanceService.changePassword(session, userData, request);
            }
            ChangePasswordResponse.Builder builder = ChangePasswordResponse.newBuilder();
            for (Map.Entry<String, byte[]> entry : accountFiles.entrySet()) {
                builder.putAccountFiles(entry.getKey(), ByteString.copyFrom(entry.getValue()));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SecurityException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            logger.warn("ChangePassword failed (cause: {})", e.getClass().getSimpleName());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage() == null ? "Password change failed" : e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void updateUserProperties(
            UpdateUserPropertiesRequest request,
            StreamObserver<UpdateUserPropertiesResponse> responseObserver) {
        unimplemented(responseObserver);
    }

    @Override
    public void exportAccount(
            ExportAccountRequest request,
            StreamObserver<ExportAccountResponse> responseObserver) {
        if (maintenanceService == null) {
            unimplemented(responseObserver);
            return;
        }
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        GrpcUserData userData = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        try {
            Map<String, byte[]> accountFiles = maintenanceService.exportAccount(session, userData);
            ExportAccountResponse.Builder builder = ExportAccountResponse.newBuilder();
            for (Map.Entry<String, byte[]> entry : accountFiles.entrySet()) {
                builder.putAccountFiles(entry.getKey(), ByteString.copyFrom(entry.getValue()));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (SecurityException e) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            logger.warn("ExportAccount failed (cause: {})", e.getClass().getSimpleName());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage() == null ? "Account export failed" : e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void deleteAccount(
            DeleteAccountRequest request,
            StreamObserver<DeleteAccountResponse> responseObserver) {
        if (maintenanceService == null) {
            unimplemented(responseObserver);
            return;
        }
        Session session = GrpcGatewayAuthContext.SESSION.get(io.grpc.Context.current());
        GrpcUserData userData = GrpcGatewayAuthContext.USER_DATA.get(io.grpc.Context.current());
        try {
            maintenanceService.deleteAccount(session, userData, request);
            responseObserver.onNext(DeleteAccountResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SecurityException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            logger.warn("DeleteAccount failed (cause: {})", e.getClass().getSimpleName());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage() == null ? "Account deletion failed" : e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Helper to return a standard UNIMPLEMENTED gRPC status response.
     *
     * @param responseObserver the stream observer to receive the error signal
     */
    private static void unimplemented(StreamObserver<?> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription(NOT_IMPLEMENTED).asRuntimeException());
    }
}
