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
import com.altastata.grpc.proto.RevokeRequest;
import com.altastata.grpc.proto.RevokeResult;
import com.altastata.grpc.proto.ShareRequest;
import com.altastata.grpc.proto.ShareResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharingGrpcServicePathsBatchTest {

    @Test
    void share_delegatesToSharePathsOnce() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        CloudFileOperationStatus status = mock(CloudFileOperationStatus.class);
        when(status.getCloudFileVersionPath()).thenReturn("Public/inbox/report.pdf");
        when(status.getOperationState()).thenReturn(AltaStataFileSystem.OperationState.DONE);
        when(status.getError()).thenReturn(null);
        when(fs.sharePaths(any(String[].class), isNull(), isNull(), any(String[].class)))
                .thenReturn(List.of(status));

        SharingGrpcService service = new SharingGrpcService();
        ShareRequest request = ShareRequest.newBuilder()
                .addFilePaths("Public/inbox/report.pdf")
                .addFilePaths("Public/inbox/notes.txt")
                .addReaders("alice222")
                .build();

        @SuppressWarnings("unchecked")
        StreamObserver<ShareResult> observer = mock(StreamObserver.class);

        try (MockedStatic<GrpcServiceUtil> util = mockStatic(GrpcServiceUtil.class)) {
            util.when(GrpcServiceUtil::currentFileSystem).thenReturn(fs);
            service.share(request, observer);
        }

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(fs).sharePaths(pathsCaptor.capture(), isNull(), isNull(), eq(new String[] {"alice222"}));
        assertEquals(2, pathsCaptor.getValue().length);
        verify(observer).onNext(any(ShareResult.class));
        verify(observer).onCompleted();
    }

    @Test
    void revoke_delegatesToRevokePathsOnce() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        when(fs.revokePaths(any(String[].class), isNull(), isNull(), any(String[].class)))
                .thenReturn(List.of());

        SharingGrpcService service = new SharingGrpcService();
        RevokeRequest request = RevokeRequest.newBuilder()
                .addFilePaths("Public/inbox/report.pdf")
                .addReaders("alice222")
                .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RevokeResult> observer = mock(StreamObserver.class);

        try (MockedStatic<GrpcServiceUtil> util = mockStatic(GrpcServiceUtil.class)) {
            util.when(GrpcServiceUtil::currentFileSystem).thenReturn(fs);
            service.revoke(request, observer);
        }

        verify(fs).revokePaths(any(String[].class), isNull(), isNull(), eq(new String[] {"alice222"}));
        verify(observer).onCompleted();
    }

    @Test
    void share_rejectsEmptyFilePaths() {
        SharingGrpcService service = new SharingGrpcService();
        ShareRequest request = ShareRequest.newBuilder().addReaders("alice222").build();

        @SuppressWarnings("unchecked")
        StreamObserver<ShareResult> observer = mock(StreamObserver.class);
        service.share(request, observer);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(errorCaptor.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT,
                ((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode());
    }
}
