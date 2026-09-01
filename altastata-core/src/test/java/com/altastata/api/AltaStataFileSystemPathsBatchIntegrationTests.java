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

import com.altastata.api.AltaStataFileSystem.OperationState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Live-account integration test for batch delete/share/revoke by explicit paths.
 *
 * <p>Run manually:
 *
 * <pre>
 * ALTASTATA_ACCOUNT_DIR=$HOME/.altastata/accounts/amazon.rsa.bob123 \
 *   ./gradlew :altastata-core:test --tests com.altastata.api.AltaStataFileSystemPathsBatchIntegrationTests
 * </pre>
 */
public class AltaStataFileSystemPathsBatchIntegrationTests {

    private AltaStataFileSystem fs;
    private String prefix;
    private String pathA;
    private String pathB;

    @Before
    public void setUp() throws Exception {
        String accountDir = System.getenv("ALTASTATA_ACCOUNT_DIR");
        assumeTrue(accountDir != null && !accountDir.trim().isEmpty());
        String password = System.getenv().getOrDefault("ALTASTATA_PASSWORD", "123");

        fs = AccountRegistry.getOrCreateFromDir(accountDir);
        fs.setPassword(password);

        prefix = "BatchPathsJavaTest/" + UUID.randomUUID() + "/";
        pathA = prefix + "a.txt";
        pathB = prefix + "b.txt";
        fs.createFile(pathA, "alpha".getBytes());
        fs.createFile(pathB, "beta".getBytes());
    }

    @After
    public void tearDown() throws Exception {
        if (fs == null) {
            return;
        }
        try {
            fs.deletePaths(new String[] {pathA, pathB}, null, null);
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    public void shareRevokeDeletePaths_batchOnRealAccount() throws Exception {
        List<CloudFileOperationStatus> shareStatuses =
                fs.sharePaths(new String[] {pathA, pathB}, null, null, new String[] {"alice222"});
        assertEquals(2, shareStatuses.size());
        for (CloudFileOperationStatus status : shareStatuses) {
            assertEquals(OperationState.DONE, status.getOperationState());
        }

        List<CloudFileOperationStatus> revokeStatuses =
                fs.revokePaths(new String[] {pathA, pathB}, null, null, new String[] {"alice222"});
        assertEquals(2, revokeStatuses.size());

        List<CloudFileOperationStatus> deleteStatuses =
                fs.deletePaths(new String[] {pathA, pathB}, null, null);
        assertFalse(deleteStatuses.isEmpty());
        for (CloudFileOperationStatus status : deleteStatuses) {
            assertEquals(OperationState.DONE, status.getOperationState());
        }

        Iterator<String[]> remaining = fs.listCloudFilesVersions(prefix, true, null, null);
        assertFalse(remaining.hasNext());
    }
}
