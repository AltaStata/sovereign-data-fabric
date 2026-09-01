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

import java.io.File;

/**
 * Creates an RSA key pair in HPCS (GREP11) and writes the public key PEM and private key blob
 * to the account directory. Does not require existing account properties; this is a clean HPCS setup.
 * Usage: [accountDir] [keyLabel]
 * <p>
 * Defaults: sandbox {@link HpcsAccountGuard#sandboxAccountDir()} and
 * {@link HpcsAccountGuard#DEFAULT_SANDBOX_USER}. Refuses to overwrite existing
 * key files unless {@code ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true}.
 * <p>
 * Requires GREP11_YAML env (path to grep11client.yaml). Creates account dir if missing.
 * Writes: public.key, hpcs-privkey.blob, hpcs.marker. Set hpcs-priv-key-blob-path to the blob file for later use.
 */
public final class HPCSCreateKey {

    /**
     * Main entry point to create an RSA key pair in IBM Cloud HPCS via GREP11 and save the
     * results in the given account directory.
     *
     * @param args command line arguments; args[0] is the account directory, args[1] is the key label
     */
    public static void main(String[] args) {
        String accountDir = args.length > 0 ? args[0] : HpcsAccountGuard.sandboxAccountDir();
        String username = args.length > 1 ? args[1] : HpcsAccountGuard.DEFAULT_SANDBOX_USER;

        HpcsAccountGuard.assertSafeToWriteKeyFiles(accountDir);

        File dirFile = new File(accountDir);
        if (!dirFile.exists()) {
            if (!dirFile.mkdirs()) {
                System.err.println("Could not create account directory: " + accountDir);
                System.exit(1);
            }
        }
        if (!dirFile.isDirectory()) {
            System.err.println("Account path is not a directory: " + accountDir);
            System.exit(1);
        }

        createKeyViaGrep11(accountDir, username, null);

        System.out.println("Public key written to " + accountDir + "/public.key");
        System.out.println("Private key blob written to " + accountDir + "/hpcs-privkey.blob (set hpcs-priv-key-blob-path to this file).");
        System.out.println("Sign the certificate for this public key, then import it with IBMHPCSKeyManager.importCertificateToHPCS(certificatePEM).");
    }

    /**
     * Generates and saves the RSA key pair using the GREP11 key generator helper.
     *
     * @param accountDir the directory to save key files to
     * @param keyLabel the label for the generated keys
     * @param apiKey the IBM Cloud API key (optional if set in YAML)
     */
    private static void createKeyViaGrep11(String accountDir, String keyLabel, String apiKey) {
        HpcsGrep11KeyGenerator.generateAndSaveKeyPair(accountDir, keyLabel, apiKey);
    }
}
