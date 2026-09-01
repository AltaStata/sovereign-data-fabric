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

import com.altastata.utils.Account;
import com.altastata.utils.FileSecurity;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry that maps an {@link AccountId} to its single,
 * shared {@link AltaStataFileSystem} instance.
 *
 * <p><b>Not instantiable.</b> All operations are exposed as {@code static}
 * methods backed by a single JVM-wide map. There can only ever be one
 * registry per JVM — two registries would partition the per-account
 * caches and connection pools and defeat the whole purpose of the
 * class. Without a registry, services running in the same JVM (the gRPC
 * server and the S3 gateway hosting the same Bob account) would each
 * construct their own {@link AltaStataFileSystem}, doubling RAM and
 * re-fetching hot files from cloud storage on each side.
 *
 * <p><b>Keying.</b> The registry key is {@link AccountId}, the tuple
 * {@code (acccontainer-prefix, myuser, accounttype)} derived from
 * user_properties. Callers never construct {@link AccountId} themselves
 * — the {@code getOrCreate*} convenience methods derive it from whatever
 * input the caller already has (user-properties text, account dir, or a
 * preloaded {@link Account}).
 *
 * <p><b>Concurrency.</b> Backed by {@link ConcurrentHashMap}; for a given
 * {@link AccountId} the underlying {@link AltaStataFileSystem}
 * constructor is invoked at most once for the lifetime of the JVM
 * (or until {@link #invalidate(AltaStataFileSystem)} is called).
 * Construction runs while holding the map's bin lock; if real-world
 * construction proves too long under contention, switch to an outer
 * per-key lock pattern.
 *
 * <p><b>Eviction.</b> {@link ConcurrentHashMap} never evicts. Use
 * {@link #invalidate(AltaStataFileSystem)} from explicit logout /
 * revocation hooks. Re-visit if RAM accumulation in long-running
 * services becomes a problem.
 *
 * @since 1.0
 */
public final class AccountRegistry {

    private static final Map<AccountId, AltaStataFileSystem> BY_ACCOUNT_ID = new ConcurrentHashMap<>();

    /**
     * Temp directories materialized by {@link #getOrCreateFromUpload} for every
     * upload login (RSA, PQC, HPCS, …). Kept for the lifetime of the cached
     * {@link AltaStataFileSystem} and deleted on {@link #invalidate}.
     * Holds {@code private.key}, {@code license.jwt}, {@code org-ca.pem}, etc.
     */
    private static final Map<AccountId, Path> UPLOAD_MATERIAL_DIRS = new ConcurrentHashMap<>();

    /**
     * Private constructor to enforce static-only utility usage and prevent instantiation.
     */
    private AccountRegistry() {
        throw new AssertionError("AccountRegistry is a static utility; do not instantiate");
    }

    /**
     * Returns the existing {@link AltaStataFileSystem} for the account
     * identified by {@code userProperties}, or atomically constructs one
     * and stores it. The {@link AltaStataFileSystem} constructor is
     * package-private; this is one of three public paths to it (see
     * also {@link #getOrCreateFromDir} and {@link #getOrCreateForAccount}).
     *
     * <p>Callers must invoke {@code fs.setPassword(...)} on the
     * returned filesystem afterwards. If {@code setPassword} throws,
     * call {@link #invalidate(AltaStataFileSystem)} so a retry with the
     * correct password gets a fresh instance instead of reusing the
     * half-initialized one.
     *
     * @param userProperties      raw user_properties text (mandatory)
     * @param privateKeyEncrypted encrypted private-key PEM, or
     *                            {@code null}/empty for HSM-backed accounts
     * @throws IllegalArgumentException if {@code userProperties} is null
     *         or doesn't contain all three identity fields
     */
    public static AltaStataFileSystem getOrCreate(String userProperties,
                                                  String privateKeyEncrypted) {
        AccountId id = AccountId.fromUserProperties(userProperties);
        return BY_ACCOUNT_ID.computeIfAbsent(id, k -> new AltaStataFileSystem(userProperties, privateKeyEncrypted));
    }

    /**
     * Returns the existing {@link AltaStataFileSystem} for the account
     * stored in {@code accountDirPath}, or atomically constructs one
     * from disk and stores it.
     *
     * @param accountDirPath absolute path to the account directory
     *                       containing exactly one {@code *user.properties}
     *                       file and the encrypted private-key PEM
     * @throws IllegalArgumentException if {@code accountDirPath} is
     *         null, not a directory, has no/multiple {@code
     *         *user.properties} files, or the file is missing an
     *         identity field
     */
    public static AltaStataFileSystem getOrCreateFromDir(String accountDirPath) {
        AccountId id = AccountId.fromAccountDir(accountDirPath);
        return BY_ACCOUNT_ID.computeIfAbsent(id, k -> new AltaStataFileSystem(accountDirPath));
    }

    /**
     * Returns the existing {@link AltaStataFileSystem} for the account
     * identified by {@code userProperties}, or atomically constructs one
     * from an in-memory upload ({@code LoginV2.upload} / Python map).
     *
     * <p>Always materializes {@code accountFiles} into a process-local temp
     * directory (basenames only) and loads via
     * {@link AltaStataFileSystem#AltaStataFileSystem(String)}. That directory
     * is removed when the registry entry is {@link #invalidate invalidated}.
     *
     * <p>Typical keys (pass only what the account needs; future files fit the
     * same map without API changes):
     * <ul>
     *   <li>{@code private.key} — RSA (required unless HPCS/HSM)</li>
     *   <li>{@code license.jwt} + {@code org-ca.pem} — Enterprise / eval</li>
     *   <li>{@code org-ca-private.key} — custodian local signing only</li>
     *   <li>HPCS / PQC key material as needed</li>
     * </ul>
     *
     * @param userProperties raw {@code *user.properties} text (mandatory)
     * @param accountFiles   account files keyed by basename → content
     * @throws IllegalArgumentException if {@code userProperties} is null,
     *         identity fields are missing, or required key files are absent
     */
    public static AltaStataFileSystem getOrCreateFromUpload(String userProperties,
                                                            Map<String, byte[]> accountFiles) {
        if (userProperties == null) {
            throw new IllegalArgumentException("userProperties is null");
        }
        AccountId id = AccountId.fromUserProperties(userProperties);
        return BY_ACCOUNT_ID.computeIfAbsent(id,
                k -> buildFromUpload(userProperties,
                        accountFiles == null ? Collections.<String, byte[]>emptyMap() : accountFiles));
    }

    /**
     * Returns the existing {@link AltaStataFileSystem} for the account
     * already loaded into {@code account}, or atomically wraps it in a
     * fresh {@link AltaStataFileSystem} and stores it.
     *
     * <p>If a different {@link Account} object with the same
     * {@link AccountId} was registered earlier, that prior instance
     * wins and the {@code account} argument is discarded.
     *
     * @param account already-built {@link Account} (must not be null,
     *                must have all three identity properties set)
     * @throws IllegalArgumentException if {@code account} is null or
     *         missing an identity field
     */
    public static AltaStataFileSystem getOrCreateForAccount(Account account) {
        AccountId id = AccountId.fromAccount(account);
        return BY_ACCOUNT_ID.computeIfAbsent(id, k -> new AltaStataFileSystem(account));
    }

    /**
     * Returns the currently-cached {@link AltaStataFileSystem} for
     * {@code id}, or {@code null} if no entry exists. Read-only; does
     * not construct anything.
     */
    public static AltaStataFileSystem get(AccountId id) {
        if (id == null) {
            return null;
        }
        return BY_ACCOUNT_ID.get(id);
    }

    /**
     * Removes {@code fs} from the registry if it is the currently-cached
     * filesystem for its own {@link AltaStataFileSystem#getAccountId()
     * accountId}. This is the logout / revocation / bad-password hook.
     *
     * <p>Conditional removal (vs. blind {@link Map#remove(Object)}) is
     * important: a concurrent {@link #getOrCreate} call may already
     * have replaced the entry with a fresh, correctly-initialized
     * {@link AltaStataFileSystem}, and we must not evict <em>that</em>
     * one when cleaning up after our own failed init.
     *
     * @param fs the filesystem to invalidate; ignored if {@code null}
     * @return {@code true} if the entry was actually removed, {@code false}
     *         if {@code fs} was null or no longer the cached instance
     */
    public static boolean invalidate(AltaStataFileSystem fs) {
        if (fs == null) {
            return false;
        }
        boolean removed = BY_ACCOUNT_ID.remove(fs.getAccountId(), fs);
        if (removed) {
            deleteUploadMaterialDir(fs.getAccountId());
        }
        return removed;
    }

    /**
     * Removes whichever filesystem is currently registered for
     * {@code accountId}, regardless of its state. Prefer
     * {@link #invalidate(AltaStataFileSystem)} when the caller already
     * holds the {@link AltaStataFileSystem} reference; this overload
     * exists for admin paths that only know the identity.
     *
     * @param accountId account identity to evict; ignored if {@code null}
     * @return the previously-cached filesystem, or {@code null} if absent
     */
    public static AltaStataFileSystem invalidate(AccountId accountId) {
        if (accountId == null) {
            return null;
        }
        AltaStataFileSystem removed = BY_ACCOUNT_ID.remove(accountId);
        if (removed != null) {
            deleteUploadMaterialDir(accountId);
        }
        return removed;
    }

    /** Number of currently-registered filesystems (for diagnostics / tests). */
    public static int size() {
        return BY_ACCOUNT_ID.size();
    }

    /**
     * Returns the gateway-local temp directory materialized for a
     * {@link #getOrCreateFromUpload} login, if one exists.
     */
    public static java.util.Optional<Path> materialDirFor(AccountId accountId) {
        if (accountId == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(UPLOAD_MATERIAL_DIRS.get(accountId));
    }

    /**
     * {@code true} when {@code path} is a gateway-owned upload material dir
     * (safe to mutate in place for password-change flows).
     */
    public static boolean isGatewayMaterialDir(Path path) {
        if (path == null) {
            return false;
        }
        return UPLOAD_MATERIAL_DIRS.containsValue(path);
    }

    // ─── Test seams ────────────────────────────────────────────────────────

    /**
     * <b>FOR TEST CODE ONLY.</b> {@link Map#putIfAbsent} on the underlying
     * map; lets tests pre-populate the registry with a mock so
     * {@link #getOrCreate} resolves to it without triggering real
     * {@link AltaStataFileSystem} construction.
     *
     * @param id account identity (must not be null)
     * @param fs filesystem (typically a Mockito mock; must not be null)
     * @return the previously-cached filesystem for {@code id}, or
     *         {@code null} if {@code fs} was actually inserted
     */
    public static AltaStataFileSystem putForTesting(AccountId id, AltaStataFileSystem fs) {
        if (id == null) {
            throw new NullPointerException("id");
        }
        if (fs == null) {
            throw new NullPointerException("fs");
        }
        return BY_ACCOUNT_ID.putIfAbsent(id, fs);
    }

    /**
     * <b>FOR TEST CODE ONLY.</b> Empties the registry. Required in
     * {@code @Before} / {@code @BeforeEach}: Gradle shares one JVM across
     * test classes by default, so without this, mappings from one test
     * pollute the next.
     */
    public static void clearForTesting() {
        for (AccountId id : UPLOAD_MATERIAL_DIRS.keySet()) {
            deleteUploadMaterialDir(id);
        }
        BY_ACCOUNT_ID.clear();
    }

    private static AltaStataFileSystem buildFromUpload(String userProperties,
                                                       Map<String, byte[]> accountFiles) {
        requireUploadKeyMaterial(userProperties, accountFiles);
        AccountId id = AccountId.fromUserProperties(userProperties);
        Path dir = materializeUploadDirectory(userProperties, accountFiles);
        UPLOAD_MATERIAL_DIRS.put(id, dir);
        return new AltaStataFileSystem(dir.toString());
    }

    /**
     * RSA (non-HPCS) uploads must include {@code private.key}. Other account
     * types validate their own material when the account directory is loaded.
     */
    static void requireUploadKeyMaterial(String userProperties, Map<String, byte[]> accountFiles) {
        Properties props = new Properties();
        try (StringReader reader = new StringReader(userProperties)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Can't parse user properties", e);
        }

        String metadataEncryption = props.getProperty("metadata-encryption", "RSA");
        String keyProtection = props.getProperty("key-protection", "");
        boolean hpcs = "HPCS".equals(keyProtection);

        if ("RSA".equals(metadataEncryption) && !hpcs) {
            byte[] privateKey = accountFiles.get("private.key");
            if (privateKey == null || privateKey.length == 0) {
                throw new IllegalArgumentException(
                        "account_files must contain private.key for RSA accounts");
            }
        }
    }

    /** Package-visible for unit tests. */
    static Path materializeUploadDirectory(String userProperties,
                                           Map<String, byte[]> accountFiles) {
        try {
            Path dir = Files.createTempDirectory("altastata-upload-");
            Path propsPath = dir.resolve("account.user.properties");
            Files.write(propsPath, userProperties.getBytes(StandardCharsets.UTF_8));
            FileSecurity.restrictToOwner(propsPath);
            for (Map.Entry<String, byte[]> entry : accountFiles.entrySet()) {
                String basename = sanitizeUploadBasename(entry.getKey());
                byte[] content = entry.getValue() == null ? new byte[0] : entry.getValue();
                Path filePath = dir.resolve(basename);
                Files.write(filePath, content);
                FileSecurity.restrictToOwner(filePath);
            }
            return dir;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to materialize account upload", e);
        }
    }

    /**
     * Sanitizes a key representing an uploaded account file to prevent path traversal.
     *
     * @param key the relative path or key to sanitize
     * @return the sanitized, safe plain file basename
     * @throws IllegalArgumentException if the key is blank, or contains path-traversal segments like ".." or path separators
     */
    private static String sanitizeUploadBasename(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("account_files key is blank");
        }
        if (key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("account_files key must be a plain basename: " + key);
        }
        return key;
    }

    /**
     * Recursively deletes the temporary materialized directory associated with the given account identity.
     *
     * @param accountId the identifier of the account whose temporary files should be cleaned up
     */
    private static void deleteUploadMaterialDir(AccountId accountId) {
        Path dir = UPLOAD_MATERIAL_DIRS.remove(accountId);
        if (dir == null) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup of temp material
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup of temp material
        }
    }
}
