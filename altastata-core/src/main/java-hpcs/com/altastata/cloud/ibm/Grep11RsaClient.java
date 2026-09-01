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
 * GREP11 (gRPC) RSA client for IBM HPCS: sign and decrypt using HSM without loading PKCS#11 .so.
 * Uses IAM bearer token and bluemix-instance header for authentication.
 */

package com.altastata.cloud.ibm;

import com.google.protobuf.ByteString;
import com.ibm.crypto.grep11.grpc.AttributeValue;
import com.ibm.crypto.grep11.grpc.CryptoGrpc;
import com.ibm.crypto.grep11.grpc.DecryptSingleRequest;
import com.ibm.crypto.grep11.grpc.DecryptSingleResponse;
import com.ibm.crypto.grep11.grpc.EncryptSingleRequest;
import com.ibm.crypto.grep11.grpc.EncryptSingleResponse;
import com.ibm.crypto.grep11.grpc.GenerateKeyPairRequest;
import com.ibm.crypto.grep11.grpc.GenerateKeyPairResponse;
import com.ibm.crypto.grep11.grpc.GetAttributeValueRequest;
import com.ibm.crypto.grep11.grpc.GetAttributeValueResponse;
import com.ibm.crypto.grep11.grpc.KeyBlob;
import com.ibm.crypto.grep11.grpc.Mechanism;
import com.ibm.crypto.grep11.grpc.RSAOAEPParm;
import com.ibm.crypto.grep11.grpc.SignSingleRequest;
import com.ibm.crypto.grep11.grpc.SignSingleResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ForwardingClientCall;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GREP11 gRPC client for RSA sign and decrypt (unwrap) against IBM HPCS.
 * No PKCS#11 library (.so) required; all crypto runs in the HSM via gRPC.
 * <p>
 * Requires: endpoint, port, instance ID, API key, and the private key as a serialized
 * KeyBlob (from GREP11 GenerateKeyPair or from a previously stored blob).
 */
public class Grep11RsaClient implements AutoCloseable {

    /** PKCS#11 CKM_RSA_PKCS (PKCS#1 v1.5 decrypt). */
    public static final long CKM_RSA_PKCS = 0x00000001L;
    /** PKCS#11 CKM_RSA_PKCS_OAEP. */
    public static final long CKM_RSA_PKCS_OAEP = 0x00000009L;
    /** PKCS#11 CKM_SHA256. */
    public static final long CKM_SHA256 = 0x00000250L;
    /** PKCS#11 CKM_SHA256_RSA_PKCS (SHA-256 with RSA PKCS#1 v1.5 sign). */
    public static final long CKM_SHA256_RSA_PKCS = 0x00000040L;
    /** PKCS#11 CKM_RSA_PKCS_KEY_PAIR_GEN. */
    public static final long CKM_RSA_PKCS_KEY_PAIR_GEN = 0x00000000L;
    // PKCS#11 CKA_* (object/attribute types) - values from IBM hpcs-grep11 pkcs11t.h
    private static final long CKA_CLASS = 0x00000000L;
    private static final long CKA_KEY_TYPE = 0x00000100L;
    private static final long CKA_MODULUS_BITS = 0x00000121L;
    private static final long CKA_SIGN = 0x00000108L;
    private static final long CKA_DECRYPT = 0x00000105L;
    private static final long CKA_VERIFY = 0x0000010AL;
    private static final long CKA_ENCRYPT = 0x00000104L;
    private static final long CKA_SENSITIVE = 0x00000103L;
    private static final long CKA_EXTRACTABLE = 0x00000162L;
    private static final long CKO_PUBLIC_KEY = 0x00000002L;
    private static final long CKO_PRIVATE_KEY = 0x00000003L;
    private static final long CKK_RSA = 0x00000000L;

    private static final String IAM_TOKEN_URL = "https://iam.cloud.ibm.com/identity/token";

    private final String endpoint;
    private final int port;
    private final String instanceId;
    private final String apiKey;
    private final ManagedChannel channel;
    private final CryptoGrpc.CryptoBlockingStub stub;

    /**
     * Constructs a new Grep11RsaClient with IAM authorization.
     *
     * @param endpoint the gRPC server hostname or IP address
     * @param port the gRPC server port
     * @param instanceId the IBM Cloud HPCS instance ID
     * @param apiKey the IBM Cloud API key
     */
    public Grep11RsaClient(String endpoint, int port, String instanceId, String apiKey) {
        this.endpoint = endpoint;
        this.port = port;
        this.instanceId = instanceId;
        this.apiKey = apiKey;
        this.channel = ManagedChannelBuilder
                .forAddress(endpoint, port)
                .useTransportSecurity()
                .build();
        ClientInterceptor authInterceptor = new IamAuthInterceptor(instanceId, apiKey, null);
        this.stub = CryptoGrpc.newBlockingStub(channel).withInterceptors(authInterceptor);
    }

    /**
     * Create client with explicit token supplier (for testing or custom IAM).
     * If tokenSupplier is null, token is obtained from apiKey via IAM.
     */
    Grep11RsaClient(String endpoint, int port, String instanceId, String apiKey,
                    TokenSupplier tokenSupplier) {
        this.endpoint = endpoint;
        this.port = port;
        this.instanceId = instanceId;
        this.apiKey = apiKey;
        this.channel = ManagedChannelBuilder
                .forAddress(endpoint, port)
                .useTransportSecurity()
                .build();
        ClientInterceptor authInterceptor = new IamAuthInterceptor(instanceId, apiKey, tokenSupplier);
        this.stub = CryptoGrpc.newBlockingStub(channel).withInterceptors(authInterceptor);
    }

    /**
     * Sign data with RSA SHA-256 PKCS#1 v1.5 using the private key (KeyBlob) in the HSM.
     *
     * @param privateKeyBlob serialized KeyBlob of the RSA private key (from GREP11)
     * @param data           data to sign
     * @return signature bytes
     */
    public byte[] sign(byte[] privateKeyBlob, byte[] data) {
        KeyBlob privKey = parseKeyBlob(privateKeyBlob);
        Mechanism mech = Mechanism.newBuilder().setMechanism(CKM_SHA256_RSA_PKCS).build();
        SignSingleRequest req = SignSingleRequest.newBuilder()
                .setMech(mech)
                .setData(ByteString.copyFrom(data))
                .setPrivKey(privKey)
                .build();
        SignSingleResponse resp = stub.signSingle(req);
        return resp.getSignature().toByteArray();
    }

    /**
     * Generate RSA key pair in the HSM. Returns serialized KeyBlobs for use with sign/decrypt.
     * Caller should store the private KeyBlob securely; there is no GREP11 API to list or delete keys.
     *
     * @param modulusBits RSA modulus size in bits (e.g. 2048 or 4096)
     * @return [privateKeyBlob, publicKeyBlob] as serialized KeyBlob bytes
     */
    public static final class KeyPairBlobs {
        public final byte[] privateKeyBlob;
        public final byte[] publicKeyBlob;
        /** PEM of the public key; set when KeyBlob lacks Attributes and GetAttributeValue was used. */
        public final String publicKeyPem;

        /**
         * Constructs KeyPairBlobs with private and public key blobs.
         *
         * @param privateKeyBlob IBM HPCS private key blob
         * @param publicKeyBlob IBM HPCS public key blob
         */
        public KeyPairBlobs(byte[] privateKeyBlob, byte[] publicKeyBlob) {
            this(privateKeyBlob, publicKeyBlob, null);
        }

        /**
         * Constructs KeyPairBlobs with private key blob, public key blob, and its public PEM representation.
         *
         * @param privateKeyBlob IBM HPCS private key blob
         * @param publicKeyBlob IBM HPCS public key blob
         * @param publicKeyPem public key in PEM format
         */
        public KeyPairBlobs(byte[] privateKeyBlob, byte[] publicKeyBlob, String publicKeyPem) {
            this.privateKeyBlob = privateKeyBlob;
            this.publicKeyBlob = publicKeyBlob;
            this.publicKeyPem = publicKeyPem;
        }
    }

    /**
     * Generates an RSA key pair in the HPCS crypto unit via GREP11.
     *
     * @param modulusBits the RSA modulus size in bits (e.g. 2048, 3072, 4096)
     * @return key pair blobs including private key blob, public key blob, and public key PEM
     */
    public KeyPairBlobs generateKeyPair(int modulusBits) {
        Mechanism mech = Mechanism.newBuilder().setMechanism(CKM_RSA_PKCS_KEY_PAIR_GEN).build();
        java.util.Map<Long, AttributeValue> privTemplate = new java.util.HashMap<>();
        privTemplate.put(CKA_CLASS, AttributeValue.newBuilder().setAttributeI(CKO_PRIVATE_KEY).build());
        privTemplate.put(CKA_KEY_TYPE, AttributeValue.newBuilder().setAttributeI(CKK_RSA).build());
        privTemplate.put(CKA_SIGN, AttributeValue.newBuilder().setAttributeTF(true).build());
        privTemplate.put(CKA_DECRYPT, AttributeValue.newBuilder().setAttributeTF(true).build());
        privTemplate.put(CKA_SENSITIVE, AttributeValue.newBuilder().setAttributeTF(true).build());
        privTemplate.put(CKA_EXTRACTABLE, AttributeValue.newBuilder().setAttributeTF(false).build());
        java.util.Map<Long, AttributeValue> pubTemplate = new java.util.HashMap<>();
        pubTemplate.put(CKA_CLASS, AttributeValue.newBuilder().setAttributeI(CKO_PUBLIC_KEY).build());
        pubTemplate.put(CKA_KEY_TYPE, AttributeValue.newBuilder().setAttributeI(CKK_RSA).build());
        pubTemplate.put(CKA_MODULUS_BITS, AttributeValue.newBuilder().setAttributeI(modulusBits).build());
        pubTemplate.put(CKA_VERIFY, AttributeValue.newBuilder().setAttributeTF(true).build());
        pubTemplate.put(CKA_ENCRYPT, AttributeValue.newBuilder().setAttributeTF(true).build());
        GenerateKeyPairRequest req = GenerateKeyPairRequest.newBuilder()
                .setMech(mech)
                .putAllPrivKeyTemplate(privTemplate)
                .putAllPubKeyTemplate(pubTemplate)
                .build();
        GenerateKeyPairResponse resp = stub.generateKeyPair(req);
        byte[] privBlob = resp.getPrivKey().toByteArray();
        byte[] pubBlob = resp.getPubKey().toByteArray();
        String publicPem = derivePublicKeyPem(resp, pubBlob);
        return new KeyPairBlobs(privBlob, pubBlob, publicPem);
    }

    /**
     * Derive PEM from GenerateKeyPairResponse. Tries in order: PubKeyBytes (DER), KeyBlob Attributes, GetAttributeValue.
     * Normalizes server bytes to SubjectPublicKeyInfo only, so we never write cert or extra data (fixes "Extra data detected in stream").
     */
    private String derivePublicKeyPem(GenerateKeyPairResponse resp, byte[] pubBlob) {
        if (resp.getPubKeyBytes() != null && resp.getPubKeyBytes().size() > 0) {
            byte[] der = resp.getPubKeyBytes().toByteArray();
            byte[] spki = ensureSubjectPublicKeyInfoOnly(der);
            String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(spki);
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
        }
        try {
            return publicKeyBlobToPem(pubBlob);
        } catch (Exception e) {
            try {
                return getPublicKeyPemViaGetAttributeValue(pubBlob);
            } catch (RuntimeException re) {
                if (re.getCause() instanceof io.grpc.StatusRuntimeException
                        && re.getCause().getMessage() != null
                        && re.getCause().getMessage().contains("CKR_KEY_TYPE_INCONSISTENT")) {
                    throw new RuntimeException(
                            "HPCS returned a public key KeyBlob without Attributes and GetAttributeValue is not supported. "
                                    + "Create the key on a LinuxONE host with pkcs11-tool, export the public key, and place it in the account dir.",
                            re);
                }
                throw re;
            }
        }
    }

    /**
     * When the server returns a public KeyBlob without CKA_MODULUS/CKA_PUBLIC_EXPONENT in Attributes,
     * retrieve them via GetAttributeValue. (May fail with CKR_KEY_TYPE_INCONSISTENT on some EP11 versions.)
     */
    private String getPublicKeyPemViaGetAttributeValue(byte[] publicKeyBlob) {
        try {
            GetAttributeValueRequest req = GetAttributeValueRequest.newBuilder()
                    .setObject(ByteString.copyFrom(publicKeyBlob))
                    .putAttributesBytes(CKA_MODULUS, ByteString.EMPTY)
                    .putAttributesBytes(CKA_PUBLIC_EXPONENT, ByteString.EMPTY)
                    .build();
            GetAttributeValueResponse resp = stub.getAttributeValue(req);
            java.util.Map<Long, ByteString> attrs = resp.getAttributesBytesMap();
            if (!attrs.containsKey(CKA_MODULUS) || !attrs.containsKey(CKA_PUBLIC_EXPONENT)) {
                throw new IllegalArgumentException("GetAttributeValue did not return CKA_MODULUS or CKA_PUBLIC_EXPONENT");
            }
            byte[] modulusBytes = attrs.get(CKA_MODULUS).toByteArray();
            byte[] exponentBytes = attrs.get(CKA_PUBLIC_EXPONENT).toByteArray();
            java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
            java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);
            java.security.spec.RSAPublicKeySpec spec = new java.security.spec.RSAPublicKeySpec(modulus, exponent);
            java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
            byte[] encoded = pub.getEncoded();
            String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded);
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new RuntimeException("Failed to get public key PEM via GetAttributeValue: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypt plaintext with RSA PKCS#1 v1.5 using the public key in the HSM.
     *
     * @param publicKeyBlob serialized KeyBlob of the RSA public key (from GREP11 GenerateKeyPair)
     * @param plaintext     data to encrypt (length must be &lt; modulus bytes - 11 for PKCS#1 v1.5)
     * @return ciphertext
     */
    public byte[] encrypt(byte[] publicKeyBlob, byte[] plaintext) {
        KeyBlob key = parseKeyBlob(publicKeyBlob);
        Mechanism mech = Mechanism.newBuilder().setMechanism(CKM_RSA_PKCS).build();
        EncryptSingleRequest req = EncryptSingleRequest.newBuilder()
                .setMech(mech)
                .setPlain(ByteString.copyFrom(plaintext))
                .setKey(key)
                .build();
        EncryptSingleResponse resp = stub.encryptSingle(req);
        return resp.getCiphered().toByteArray();
    }

    /**
     * Decrypt (unwrap) ciphertext with RSA PKCS#1 v1.5 using the private key in the HSM.
     *
     * @param privateKeyBlob serialized KeyBlob of the RSA private key (from GREP11)
     * @param ciphertext     RSA-encrypted data (e.g. from Cipher "RSA/ECB/PKCS1Padding")
     * @return decrypted plaintext
     */
    public byte[] decrypt(byte[] privateKeyBlob, byte[] ciphertext) {
        return decryptWithMechanism(privateKeyBlob, ciphertext, Mechanism.newBuilder().setMechanism(CKM_RSA_PKCS).build());
    }

    /**
     * Decrypt with RSA-OAEP SHA-256 / MGF1-SHA-256 (matches JCA {@code RSA/ECB/OAEPWithSHA-256AndMGF1Padding}).
     */
    public byte[] decryptOaepSha256(byte[] privateKeyBlob, byte[] ciphertext) {
        RSAOAEPParm oaep = RSAOAEPParm.newBuilder()
                .setHashMech(CKM_SHA256)
                .setMgf(RSAOAEPParm.Mask.CkgMgf1Sha256)
                .setEncodingParmType(RSAOAEPParm.ParmType.CkzNoDataSpecified)
                .build();
        Mechanism mech = Mechanism.newBuilder()
                .setMechanism(CKM_RSA_PKCS_OAEP)
                .setRSAOAEPParameter(oaep)
                .build();
        return decryptWithMechanism(privateKeyBlob, ciphertext, mech);
    }

    private byte[] decryptWithMechanism(byte[] privateKeyBlob, byte[] ciphertext, Mechanism mech) {
        KeyBlob key = parseKeyBlob(privateKeyBlob);
        DecryptSingleRequest req = DecryptSingleRequest.newBuilder()
                .setMech(mech)
                .setCiphered(ByteString.copyFrom(ciphertext))
                .setKey(key)
                .build();
        DecryptSingleResponse resp = stub.decryptSingle(req);
        return resp.getPlain().toByteArray();
    }

    private static final long CKA_MODULUS = 0x00000120L;
    private static final long CKA_PUBLIC_EXPONENT = 0x00000122L;

    /**
     * Parses a byte array into a GREP11 KeyBlob object.
     *
     * @param bytes the encoded key blob bytes
     * @return the parsed KeyBlob object
     * @throws IllegalArgumentException if the key blob is invalid
     */
    private static KeyBlob parseKeyBlob(byte[] bytes) {
        try {
            return KeyBlob.parseFrom(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid private key KeyBlob", e);
        }
    }

    /**
     * Return bytes that are exactly one SubjectPublicKeyInfo (DER). The server may return key + trailing bytes
     * (e.g. extra ASN.1); we read only the first ASN.1 object so PEM parses without "Extra data detected in stream".
     */
    private static byte[] ensureSubjectPublicKeyInfoOnly(byte[] der) {
        try (org.bouncycastle.asn1.ASN1InputStream asn1 = new org.bouncycastle.asn1.ASN1InputStream(new java.io.ByteArrayInputStream(der))) {
            org.bouncycastle.asn1.ASN1Primitive first = asn1.readObject();
            if (first == null) throw new IllegalArgumentException("No ASN.1 object in server response");
            byte[] firstBytes = first.getEncoded();
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(firstBytes);
            return spki.getEncoded();
        } catch (Exception e) {
            throw new IllegalArgumentException("Server public key bytes are not a valid SubjectPublicKeyInfo", e);
        }
    }

    /**
     * Extract RSA public key PEM from a GREP11 public KeyBlob (e.g. from GenerateKeyPairResponse).
     * KeyBlob must contain CKA_MODULUS and CKA_PUBLIC_EXPONENT in Attributes.
     */
    public static String publicKeyBlobToPem(byte[] publicKeyBlob) throws Exception {
        KeyBlob blob = KeyBlob.parseFrom(publicKeyBlob);
        java.util.Map<Long, AttributeValue> attrs = blob.getAttributesMap();
        if (!attrs.containsKey(CKA_MODULUS) || !attrs.containsKey(CKA_PUBLIC_EXPONENT)) {
            throw new IllegalArgumentException("Public KeyBlob missing CKA_MODULUS or CKA_PUBLIC_EXPONENT");
        }
        byte[] modulusBytes = attrs.get(CKA_MODULUS).getAttributeB().toByteArray();
        byte[] exponentBytes = attrs.get(CKA_PUBLIC_EXPONENT).getAttributeB().toByteArray();
        java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
        java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);
        java.security.spec.RSAPublicKeySpec spec = new java.security.spec.RSAPublicKeySpec(modulus, exponent);
        java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        byte[] encoded = pub.getEncoded();
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
    }

    /**
     * Shuts down the managed gRPC channel and releases network resources.
     */
    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Channel shutdown interrupted", e);
        }
    }

    /**
     * Supplies IAM Bearer token; if null, token is fetched using API key.
     */
    public interface TokenSupplier {
        String getToken();
    }

    /**
     * gRPC client interceptor that adds Authorization: Bearer &lt;token&gt; and
     * bluemix-instance: &lt;instanceId&gt; to every call.
     */
    private static final class IamAuthInterceptor implements ClientInterceptor {
        private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> BLUEMIX_INSTANCE = Metadata.Key.of("bluemix-instance", Metadata.ASCII_STRING_MARSHALLER);

        private final String instanceId;
        private final String apiKey;
        private final TokenSupplier tokenSupplier;

        /**
         * Constructs an IamAuthInterceptor with the specified IBM Cloud credentials.
         *
         * @param instanceId IBM HPCS crypto instance identifier
         * @param apiKey IBM Cloud API key for IAM token retrieval
         * @param tokenSupplier optional custom IAM token supplier
         */
        IamAuthInterceptor(String instanceId, String apiKey, TokenSupplier tokenSupplier) {
            this.instanceId = instanceId;
            this.apiKey = apiKey;
            this.tokenSupplier = tokenSupplier;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                /**
                 * Intercepts the start of the client call to add required headers.
                 *
                 * @param responseListener the gRPC response listener
                 * @param headers the metadata headers to augment
                 */
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    String token = tokenSupplier != null ? tokenSupplier.getToken() : fetchIamToken(apiKey);
                    headers.put(AUTH, "Bearer " + token);
                    headers.put(BLUEMIX_INSTANCE, instanceId);
                    super.start(responseListener, headers);
                }
            };
        }
    }

    /**
     * Fetch IAM access token using API key.
     */
    static String fetchIamToken(String apiKey) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(IAM_TOKEN_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            String body = "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" + java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name());
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream errStream = conn.getErrorStream();
                String err = errStream != null ? new String(readAllBytes(errStream), StandardCharsets.UTF_8) : "";
                throw new RuntimeException("IAM token request failed: " + code + " " + err);
            }
            String json = new String(readAllBytes(conn.getInputStream()), StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(json);
            if (!obj.has("access_token")) {
                throw new RuntimeException("IAM response missing access_token");
            }
            return obj.getString("access_token");
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Failed to get IAM token", e);
        }
    }

    /** Java 8 compatible: read stream to byte array. */
    private static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }
}
