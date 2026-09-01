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
import com.altastata.grpc.proto.DownloadDirectoryAsZipChunk;
import com.altastata.utils.Constants;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadDirectoryAsZipStreamTest {

    @Test
    void streamingZipOutputEmitsEachWriteImmediately() throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        StreamObserver<DownloadDirectoryAsZipChunk> observer = new StreamObserver<>() {
            @Override
            public void onNext(DownloadDirectoryAsZipChunk value) {
                chunks.add(value.getData().toByteArray());
            }

            @Override
            public void onError(Throwable t) {
                throw new AssertionError(t);
            }

            @Override
            public void onCompleted() {
            }
        };

        Class<?> clazz = Class.forName("com.altastata.grpc.FileOpsGrpcService$StreamingZipChunkOutputStream");
        Constructor<?> ctor = clazz.getDeclaredConstructor(StreamObserver.class);
        ctor.setAccessible(true);
        OutputStream out = (OutputStream) ctor.newInstance(observer);

        out.write(new byte[]{1, 2, 3});
        out.write(4);
        out.write(new byte[]{5, 6}, 0, 2);
        out.flush();

        assertEquals(1, chunks.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, chunks.get(0));
    }

    @Test
    void pickLatestVersionedPathPrefersNewerToken() throws Exception {
        var method = FileOpsGrpcService.class.getDeclaredMethod(
                "pickLatestVersionedPath", String[].class);
        method.setAccessible(true);
        String[] versions = {
                "dir/file.txt\u2739bob123_1000",
                "dir/file.txt\u2739bob123_2000"
        };
        String picked = (String) method.invoke(null, (Object) versions);
        assertEquals("dir/file.txt\u2739bob123_2000", picked);
    }

    @Test
    void collectZipWorkItemsKeepsCatalogOrderAndLatestVersion() {
        Iterator<String[]> iterator = List.of(
                new String[]{"bulk/a.txt\u2739bob_1", "bulk/a.txt\u2739bob_2"},
                new String[]{"bulk/b.txt\u2739bob_1"}
        ).iterator();

        List<FileOpsGrpcService.ZipFileWorkItem> items =
                FileOpsGrpcService.collectZipWorkItems(iterator, "bulk", "bulk/");

        assertEquals(2, items.size());
        assertEquals("a.txt", items.get(0).relativePath);
        assertEquals("bulk/a.txt\u2739bob_2", items.get(0).versionedPath);
        assertEquals("b.txt", items.get(1).relativePath);
    }

    @Test
    void chooseStreamCopyBufferSizeMatchesAvailableUpToChunkMax() throws Exception {
        byte[] small = new byte[500];
        assertEquals(500, FileOpsGrpcService.chooseStreamCopyBufferSize(new ByteArrayInputStream(small)));

        int chunk = Constants.PLAIN_CHUNK_MAX_SIZE();
        assertEquals(chunk, FileOpsGrpcService.chooseStreamCopyBufferSize(
                new ByteArrayInputStream(new byte[chunk * 3])));

        assertEquals(FileOpsGrpcService.ZIP_STREAM_COPY_BUFFER_DEFAULT,
                FileOpsGrpcService.chooseStreamCopyBufferSize(new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }

                    @Override
                    public int available() {
                        return 0;
                    }
                }));
    }

    @Test
    void startZipFilePipePumpStreamsBytesThroughPipe() throws Exception {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        when(fs.getFileInputStream(any(), any(), eq(0L), eq(FileOpsGrpcService.ZIP_READ_PARALLEL_CHUNKS), eq(true)))
                .thenReturn(new ByteArrayInputStream(payload));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            FileOpsGrpcService.ZipFilePipePump pump =
                    FileOpsGrpcService.startZipFilePipePump(fs, pool, "bulk/small.txt\u2739v1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileOpsGrpcService.transferStream(pump.input, out);
            pump.awaitDone();
            assertArrayEquals(payload, out.toByteArray());
        } finally {
            pool.shutdownNow();
        }
    }
}
