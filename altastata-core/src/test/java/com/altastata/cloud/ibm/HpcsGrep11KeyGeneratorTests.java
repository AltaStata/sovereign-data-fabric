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

package com.altastata.cloud.ibm;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

public class HpcsGrep11KeyGeneratorTests {

    @Test
    public void keyLabelFromAccountDirUsesLastSegment() {
        assertEquals("hpcsdev",
                HpcsGrep11KeyGenerator.keyLabelFromAccountDir("/home/x/.altastata/accounts/amazon.rsa.hpcs.hpcsdev"));
        assertEquals("bob", HpcsGrep11KeyGenerator.keyLabelFromAccountDir("/tmp/bob"));
    }

    @Test
    public void resolveYamlPathReturnsNullWhenUnset() {
        assumeTrue(System.getenv("GREP11_YAML") == null);
        assumeTrue(System.getenv("ALTASTATA_HPCS_YAML") == null);
        assertNull(HpcsGrep11KeyGenerator.resolveYamlPath(null));
        assertNull(HpcsGrep11KeyGenerator.resolveYamlPath(""));
        assertNull(HpcsGrep11KeyGenerator.resolveYamlPath("/nonexistent/grep11client.yaml"));
    }

    @Test
    public void resolveYamlPathUsesExplicitExistingFile() throws Exception {
        Path yaml = Files.createTempFile("grep11client-", ".yaml");
        try {
            assertEquals(yaml.toString(), HpcsGrep11KeyGenerator.resolveYamlPath(yaml.toString()));
        } finally {
            Files.deleteIfExists(yaml);
        }
    }
}
