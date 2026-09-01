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

package com.altastata.api;

import com.altastata.filesystem.FileSystemModel;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.utils.Account;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AltaStataFileSystemPathsBatchTests {

    @Test
    public void deletePaths_invokesDeleteCloudFilesOnce() {
        FileSystemModel model = mock(FileSystemModel.class);
        Account account = mock(Account.class);
        when(account.fileSystemModel()).thenReturn(model);

        CloudFile cf1 = cloudFileWithPath("Public/a.pdf");
        CloudFile cf2 = cloudFileWithPath("Public/b.pdf");
        when(model.listCloudFiles(eq("Public/a.pdf"), eq(true), isNull(), isNull(), eq(false)))
                .thenReturn(iteratorOf(cf1));
        when(model.listCloudFiles(eq("Public/b.pdf"), eq(true), isNull(), isNull(), eq(false)))
                .thenReturn(iteratorOf(cf2));
        when(model.deleteCloudFiles(any(CloudFile[].class), any()))
                .thenReturn(new CloudFileOperationStatus[0]);

        AltaStataFileSystem fs = new AltaStataFileSystem(account);
        fs.deletePaths(new String[] {"Public/a.pdf", "Public/b.pdf"}, null, null);

        ArgumentCaptor<CloudFile[]> captor = ArgumentCaptor.forClass(CloudFile[].class);
        verify(model, times(1)).deleteCloudFiles(captor.capture(), any());
        assertEquals(2, captor.getValue().length);
    }

    @Test
    public void sharePaths_invokesShareCloudFilesOnce() {
        FileSystemModel model = mock(FileSystemModel.class);
        Account account = mock(Account.class);
        when(account.fileSystemModel()).thenReturn(model);

        CloudFile cf1 = cloudFileWithPath("Public/a.pdf");
        CloudFile cf2 = cloudFileWithPath("Public/b.pdf");
        when(model.listCloudFiles(eq("Public/a.pdf"), eq(true), isNull(), isNull(), eq(false)))
                .thenReturn(iteratorOf(cf1));
        when(model.listCloudFiles(eq("Public/b.pdf"), eq(true), isNull(), isNull(), eq(false)))
                .thenReturn(iteratorOf(cf2));
        when(model.shareCloudFiles(any(CloudFile[].class), any(String[].class), any()))
                .thenReturn(new CloudFileOperationStatus[0]);

        AltaStataFileSystem fs = new AltaStataFileSystem(account);
        fs.sharePaths(new String[] {"Public/a.pdf", "Public/b.pdf"}, null, null, new String[] {"alice222"});

        verify(model, times(1)).shareCloudFiles(any(CloudFile[].class), any(String[].class), any());
    }

    @Test
    public void revokePaths_invokesRevokeReaderAccessOnce() {
        FileSystemModel model = mock(FileSystemModel.class);
        Account account = mock(Account.class);
        when(account.fileSystemModel()).thenReturn(model);

        CloudFile cf1 = cloudFileWithPath("Public/a.pdf");
        when(model.listCloudFiles(eq("Public/a.pdf"), eq(true), isNull(), isNull(), eq(false)))
                .thenReturn(iteratorOf(cf1));
        when(model.revokeReaderAccess(any(CloudFile[].class), any(String[].class), any()))
                .thenReturn(new CloudFileOperationStatus[0]);

        AltaStataFileSystem fs = new AltaStataFileSystem(account);
        fs.revokePaths(new String[] {"Public/a.pdf"}, null, null, new String[] {"alice222"});

        verify(model, times(1)).revokeReaderAccess(any(CloudFile[].class), any(String[].class), any());
    }

    @Test
    public void deletePaths_emptyOrNullReturnsEmpty() {
        AltaStataFileSystem fs = new AltaStataFileSystem(new Account());
        assertTrue(fs.deletePaths(new String[0], null, null).isEmpty());
        assertTrue(fs.deletePaths(null, null, null).isEmpty());
    }

    private static CloudFile cloudFileWithPath(String path) {
        CloudFile cf = mock(CloudFile.class);
        when(cf.getPath()).thenReturn(path);
        when(cf.getVersions()).thenReturn(new ConcurrentSkipListSet<>());
        return cf;
    }

    private static Iterator<CloudFile> iteratorOf(CloudFile cf) {
        return Collections.singletonList(cf).iterator();
    }
}
