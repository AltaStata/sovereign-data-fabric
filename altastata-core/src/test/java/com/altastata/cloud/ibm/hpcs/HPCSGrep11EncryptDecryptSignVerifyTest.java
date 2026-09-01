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
 * Standalone test: encrypt/decrypt and sign/verify using the GREP11 key created by HPCSCreateKey.
 * Uses public.key and hpcs-privkey.blob from the account dir; GREP11 config from GREP11_YAML.
 *
 * Usage: [accountDir]
 *   Default accountDir: ~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev (sandbox)
 *
 * Requires: GREP11_YAML env pointing to grep11client.yaml
 */

package com.altastata.cloud.ibm.hpcs;

import com.altastata.cloud.ibm.Grep11ConfigFromYaml;
import com.altastata.cloud.ibm.Grep11RsaClient;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class HPCSGrep11EncryptDecryptSignVerifyTest {

    private static final String DEFAULT_ACCOUNT_DIR =
            System.getProperty("user.home") + "/.altastata/accounts/amazon.rsa.hpcs.hpcsdev";
    private static final String SEP = "======================================================================\n";

    public static void main(String[] args) throws Exception {
        String accountDir = args.length > 0 ? args[0] : DEFAULT_ACCOUNT_DIR;
        if (!Files.exists(Paths.get(accountDir, "public.key")) || accountDir.contains("path/to")) {
            accountDir = DEFAULT_ACCOUNT_DIR;
        }
        String yamlPath = System.getenv("GREP11_YAML");
        if (yamlPath == null || yamlPath.isEmpty()) {
            yamlPath = null;
        }
        if (yamlPath != null && (!Files.exists(Paths.get(yamlPath)) || yamlPath.contains("path/to"))) {
            yamlPath = null;
        }
        if (yamlPath == null) {
            java.nio.file.Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            outer:
            for (java.nio.file.Path dir = cwd; dir != null; dir = dir.getParent()) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> children = java.nio.file.Files.newDirectoryStream(dir)) {
                    for (java.nio.file.Path child : children) {
                        java.nio.file.Path candidate = child.resolve("grep11client.yaml");
                        if (Files.exists(candidate) && !child.getFileName().toString().equals("altastata-core")) {
                            yamlPath = candidate.toAbsolutePath().normalize().toString();
                            break outer;
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // keep searching parents
                }
            }
        }
        if (yamlPath == null || !Files.exists(Paths.get(yamlPath))) {
            System.err.println("Set GREP11_YAML to the full path to a populated grep11client.yaml.");
            System.exit(1);
        }
        yamlPath = Paths.get(yamlPath).toAbsolutePath().normalize().toString();

        byte[] publicKeyPem = Files.readAllBytes(Paths.get(accountDir, "public.key"));
        byte[] privateKeyBlob = Files.readAllBytes(Paths.get(accountDir, "hpcs-privkey.blob"));

        String pemString = new String(publicKeyPem, StandardCharsets.UTF_8);
        inspectPemDer("public.key", pemString);

        PublicKey publicKey = parsePublicKeyFromPem(pemString);

        Grep11ConfigFromYaml config = Grep11ConfigFromYaml.load(Paths.get(yamlPath));
        System.out.println(SEP + "HPCS GREP11 Encrypt/Decrypt and Sign/Verify Test\n" + SEP);
        System.out.println("Account dir: " + accountDir);
        System.out.println("Public key: loaded from public.key");
        System.out.println("Private key: loaded from hpcs-privkey.blob (used in HSM only)\n");

        try (Grep11RsaClient client = new Grep11RsaClient(
                config.endpoint, config.port, config.instanceId, config.apiKey)) {

            // --- Encrypt (Java) / Decrypt (GREP11) ---
            System.out.println("--- Encrypt / Decrypt ---");
            String plaintext = "Hello from GREP11 encrypt/decrypt test.";
            byte[] plain = plaintext.getBytes(StandardCharsets.UTF_8);
            Cipher enc = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            enc.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] ciphertext = enc.doFinal(plain);
            System.out.println("  Encrypted (Java, public key): " + ciphertext.length + " bytes");

            byte[] decrypted = client.decryptOaepSha256(privateKeyBlob, ciphertext);
            String decryptedStr = new String(decrypted, StandardCharsets.UTF_8);
            if (!plaintext.equals(decryptedStr)) {
                throw new AssertionError("Decrypt mismatch: '" + decryptedStr + "'");
            }
            System.out.println("  Decrypted (GREP11, private key in HSM): OK");
            System.out.println("  Plaintext: " + plaintext + "\n");

            // --- Sign (GREP11) / Verify (Java) ---
            System.out.println("--- Sign / Verify ---");
            byte[] data = "Data to sign with HPCS key.".getBytes(StandardCharsets.UTF_8);
            byte[] signature = client.sign(privateKeyBlob, data);
            System.out.println("  Signed (GREP11, private key in HSM): " + signature.length + " bytes");

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(data);
            if (!sig.verify(signature)) {
                throw new AssertionError("Signature verification failed.");
            }
            System.out.println("  Verified (Java, public key): OK\n");

            System.out.println(SEP + "SUCCESS: Encrypt/decrypt and sign/verify passed.\n" + SEP);
        }
    }

    /**
     * Inspect PEM content: decode base64, read first ASN.1 object, report its length and any extra bytes.
     * Use this to find the root cause of "Extra data detected in stream" (see firstObjectLen vs der.length).
     */
    private static void inspectPemDer(String label, String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        System.out.println("--- PEM DER inspection (" + label + ") ---");
        System.out.println("  Total decoded bytes: " + der.length);
        try (ASN1InputStream asn1 = new ASN1InputStream(new ByteArrayInputStream(der))) {
            ASN1Primitive first = asn1.readObject();
            if (first != null) {
                byte[] firstEncoded = first.getEncoded();
                System.out.println("  First ASN.1 object length: " + firstEncoded.length);
                int extra = der.length - firstEncoded.length;
                if (extra > 0) {
                    System.out.println("  EXTRA bytes after first object: " + extra + " (this causes BouncyCastle 'Extra data detected in stream')");
                    int show = Math.min(extra, 64);
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < show; i++) {
                        hex.append(String.format("%02x ", der[firstEncoded.length + i]));
                    }
                    if (extra > show) hex.append("...");
                    System.out.println("  Extra bytes (hex, first " + show + "): " + hex);
                } else {
                    System.out.println("  No extra bytes (single ASN.1 object).");
                }
            } else {
                System.out.println("  No ASN.1 object read.");
            }
        } catch (Exception e) {
            System.out.println("  Error inspecting: " + e.getMessage());
        }
        System.out.println();
    }

    /** Parse public key from PEM; use only first ASN.1 object so test passes even when PEM has extra bytes. */
    private static PublicKey parsePublicKeyFromPem(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try (ASN1InputStream asn1 = new ASN1InputStream(new ByteArrayInputStream(der))) {
            ASN1Primitive first = asn1.readObject();
            if (first != null) {
                byte[] firstBytes = first.getEncoded();
                // Use X509EncodedKeySpec (SubjectPublicKeyInfo DER) with default provider - no BC required
                return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(firstBytes));
            }
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
