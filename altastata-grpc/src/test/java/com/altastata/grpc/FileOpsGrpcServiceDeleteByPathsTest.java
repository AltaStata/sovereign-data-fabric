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
import com.altastata.grpc.proto.DeleteByPathsRequest;
import com.altastata.grpc.proto.DeleteResponse;
import com.altastata.grpc.proto.FileStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
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
class FileOpsGrpcServiceDeleteByPathsTest {

    @Test
    void deleteByPaths_delegatesToFileSystemDeletePaths() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        CloudFileOperationStatus status = mock(CloudFileOperationStatus.class);
        when(status.getCloudFileVersionPath()).thenReturn("Public/inbox/report.pdf");
        when(status.getOperationState()).thenReturn(AltaStataFileSystem.OperationState.DONE);
        when(status.getError()).thenReturn(null);
        when(fs.deletePaths(any(String[].class), isNull(), isNull()))
                .thenReturn(List.of(status));

        FileOpsGrpcService service = new FileOpsGrpcService(mock(UploadRegistry.class));
        DeleteByPathsRequest request = DeleteByPathsRequest.newBuilder()
                .addFilePaths("Public/inbox/report.pdf")
                .addFilePaths("Public/inbox/notes.txt")
                .build();

        @SuppressWarnings("unchecked")
        StreamObserver<DeleteResponse> observer = mock(StreamObserver.class);

        try (MockedStatic<GrpcServiceUtil> util = mockStatic(GrpcServiceUtil.class)) {
            util.when(GrpcServiceUtil::currentFileSystem).thenReturn(fs);
            service.deleteByPaths(request, observer);
        }

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(fs).deletePaths(pathsCaptor.capture(), isNull(), isNull());
        assertEquals(2, pathsCaptor.getValue().length);
        assertEquals("Public/inbox/report.pdf", pathsCaptor.getValue()[0]);
        assertEquals("Public/inbox/notes.txt", pathsCaptor.getValue()[1]);

        ArgumentCaptor<DeleteResponse> responseCaptor = ArgumentCaptor.forClass(DeleteResponse.class);
        verify(observer).onNext(responseCaptor.capture());
        verify(observer).onCompleted();
        List<FileStatus> statuses = responseCaptor.getValue().getStatusesList();
        assertEquals(1, statuses.size());
        assertEquals("Public/inbox/report.pdf", statuses.get(0).getFilePath());
    }

    @Test
    void deleteByPaths_rejectsEmptyFilePaths() {
        FileOpsGrpcService service = new FileOpsGrpcService(mock(UploadRegistry.class));
        DeleteByPathsRequest request = DeleteByPathsRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<DeleteResponse> observer = mock(StreamObserver.class);

        service.deleteByPaths(request, observer);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(errorCaptor.capture());
        StatusRuntimeException error = (StatusRuntimeException) errorCaptor.getValue();
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
    }
}
