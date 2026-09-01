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

/*
 * Runnable: create RSA key in HPCS via GREP11 and verify sign + decrypt.
 * Uses settings from grep11client.yaml. Works from Mac (no .so required).
 * Key deletion is not exposed in the GREP11 API.
 */

package com.altastata.cloud.ibm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Create a key in HPCS via GREP11 and run sign + encrypt/decrypt round-trip.
 * <p>
 * Usage:
 * <pre>
 *   # Use grep11client.yaml in current directory
 *   java -cp ... com.altastata.cloud.ibm.Grep11CreateKeyMain
 *
 *   # Or specify YAML path
 *   java -cp ... com.altastata.cloud.ibm.Grep11CreateKeyMain /path/to/grep11client.yaml
 *
 *   # Save private KeyBlob to a file (for HPCS_PRIV_KEY_BLOB_PATH)
 *   java -cp ... com.altastata.cloud.ibm.Grep11CreateKeyMain /path/to/grep11client.yaml /path/to/hpcs-privkey.blob
 *
 *   # Or set GREP11_YAML env
 *   export GREP11_YAML=/etc/ep11client/grep11client.yaml
 *   java -cp ... com.altastata.cloud.ibm.Grep11CreateKeyMain
 * </pre>
 * <p>
 * The YAML must have: iamcredentialtemplate.instance, tokens.0.grep11connection.address and port,
 * and one of tokens.0.users.&lt;n&gt;.iamauth.apikey set to your IBM Cloud API key.
 */
public final class Grep11CreateKeyMain {

    /**
     * Main entry point to generate an RSA-2048 key pair in IBM Cloud HPCS via GREP11
     * and run a quick cryptographic verification.
     *
     * @param args command line arguments; optional args[0] is the path to grep11client.yaml,
     *             and optional args[1] is the output file path to save the generated private key blob
     */
    public static void main(String[] args) {
        Path yamlPath = null;
        if (args.length > 0) {
            yamlPath = Paths.get(args[0]);
        } else {
            String env = System.getenv("GREP11_YAML");
            if (env != null && !env.isEmpty()) {
                yamlPath = Paths.get(env);
            } else {
                yamlPath = Paths.get("grep11client.yaml");
            }
        }

        System.out.println("GREP11 create-key test (settings from YAML, no .so required)");
        System.out.println("YAML path: " + yamlPath.toAbsolutePath());

        try {
            Grep11ConfigFromYaml config = Grep11ConfigFromYaml.load(yamlPath);
            System.out.println("Endpoint: " + config.endpoint + ":" + config.port);
            System.out.println("Instance: " + config.instanceId);

            try (Grep11RsaClient client = new Grep11RsaClient(
                    config.endpoint, config.port, config.instanceId, config.apiKey)) {

                System.out.println("Generating RSA-2048 key pair in HPCS...");
                Grep11RsaClient.KeyPairBlobs keyPair = client.generateKeyPair(2048);
                System.out.println("Key pair created. Private KeyBlob length: " + keyPair.privateKeyBlob.length);
                System.out.println("Public KeyBlob length: " + keyPair.publicKeyBlob.length);
                if (keyPair.publicKeyPem != null) {
                    System.out.println("Public key PEM obtained (via GetAttributeValue fallback)");
                }

                byte[] data = "Hello GREP11 from Mac".getBytes(StandardCharsets.UTF_8);
                byte[] sig = client.sign(keyPair.privateKeyBlob, data);
                System.out.println("Sign OK (signature length " + sig.length + ")");

                byte[] plain = "secret".getBytes(StandardCharsets.UTF_8);
                byte[] cipher = client.encrypt(keyPair.publicKeyBlob, plain);
                byte[] decrypted = client.decrypt(keyPair.privateKeyBlob, cipher);
                if (!java.util.Arrays.equals(plain, decrypted)) {
                    throw new RuntimeException("Decrypt round-trip failed");
                }
                System.out.println("Encrypt/decrypt round-trip OK");

                if (args.length >= 2) {
                    Path savePath = Paths.get(args[1]);
                    // TODO(security): Verify whether this CLI is used on shared POSIX hosts;
                    // if so, create/save the private KeyBlob with owner-only (0600) permissions.
                    Files.write(savePath, keyPair.privateKeyBlob);
                    System.out.println("Private KeyBlob saved to: " + savePath.toAbsolutePath());
                    System.out.println("Use: HPCS_PRIV_KEY_BLOB_PATH=" + savePath.toAbsolutePath());
                    Path publicKeyPath = savePath.getParent().resolve("public.key");
                    String publicPem = keyPair.publicKeyPem != null ? keyPair.publicKeyPem : Grep11RsaClient.publicKeyBlobToPem(keyPair.publicKeyBlob);
                    Files.write(publicKeyPath, publicPem.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Public key saved to: " + publicKeyPath.toAbsolutePath());
                    System.out.println("Set hpcs-public-key-pem to this path for verify/encrypt and re-encrypt credentials with this key.");
                }
            }

            System.out.println("Done. Key was created in HPCS and sign/decrypt verified.");
            System.out.println("(Key deletion is not exposed in the GREP11 API.)");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
