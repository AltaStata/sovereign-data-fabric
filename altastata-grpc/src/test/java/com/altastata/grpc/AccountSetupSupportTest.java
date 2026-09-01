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

import com.altastata.grpc.proto.AccountType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountSetupSupportTest {

    private static final String PKCS11_PROP = "altastata.hpcs.pkcs11-library";

    @TempDir
    Path tempDir;

    @Test
    void supportedAccountTypesAlwaysIncludesRsaAndPqc() {
        List<AccountType> types = AccountSetupSupport.supportedAccountTypes();

        assertTrue(types.contains(AccountType.RSA));
        assertTrue(types.contains(AccountType.PQC));
    }

    @Test
    void supportedAccountTypesIncludesHpcsWhenPkcs11LibraryPropertyExists() throws IOException {
        Path library = tempDir.resolve("pkcs11-grep11.so");
        Files.write(library, new byte[]{0x7f});

        String prior = System.getProperty(PKCS11_PROP);
        try {
            System.setProperty(PKCS11_PROP, library.toString());
            List<AccountType> types = AccountSetupSupport.supportedAccountTypes();
            assertTrue(types.contains(AccountType.HPCS));
        } finally {
            restoreProperty(PKCS11_PROP, prior);
        }
    }

    @Test
    void accountTypeFromUserPropertiesDetectsRsaPqcAndHpcs() {
        assertEquals(AccountType.RSA, AccountSetupSupport.accountTypeFromUserProperties(
                "acccontainer-prefix=altastata-x-\nmyuser=bob\naccounttype=amazon-s3-secure\n"
                        + "metadata-encryption=RSA\n"));
        assertEquals(AccountType.PQC, AccountSetupSupport.accountTypeFromUserProperties(
                "acccontainer-prefix=altastata-x-\nmyuser=bob\naccounttype=amazon-s3-secure\n"
                        + "metadata-encryption=PQC\n"));
        assertEquals(AccountType.HPCS, AccountSetupSupport.accountTypeFromUserProperties(
                "acccontainer-prefix=altastata-x-\nmyuser=bob\naccounttype=amazon-s3-secure\n"
                        + "key-protection=HPCS\n"));
    }

    @Test
    void supportedAccountTypesAlwaysIncludesHpcs() {
        String prior = System.getProperty(PKCS11_PROP);
        try {
            System.clearProperty(PKCS11_PROP);
            List<AccountType> types = AccountSetupSupport.supportedAccountTypes();
            assertTrue(types.contains(AccountType.HPCS));
        } finally {
            restoreProperty(PKCS11_PROP, prior);
        }
    }

    private static void restoreProperty(String key, String prior) {
        if (prior == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prior);
        }
    }
}
