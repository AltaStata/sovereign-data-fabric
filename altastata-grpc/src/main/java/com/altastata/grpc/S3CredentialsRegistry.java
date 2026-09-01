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
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory registry mapping issued S3 access keys to gateway sessions.
 *
 * <p>Design: {@code altastata-grpc/CONSOLE_ACCOUNT_SETUP_DESIGN.md §7}.
 */
@Singleton
public class S3CredentialsRegistry {

    private final ConcurrentMap<String, IssuedCredential> byAccessKeyId = new ConcurrentHashMap<>();
    private final SessionRegistry sessionRegistry;
    private final Clock clock;

    /**
     * Constructs S3CredentialsRegistry using system UTC time and session registry.
     *
     * @param sessionRegistry gateway sessions manager
     */
    public S3CredentialsRegistry(SessionRegistry sessionRegistry) {
        this(sessionRegistry, Clock.systemUTC());
    }

    S3CredentialsRegistry(SessionRegistry sessionRegistry, Clock clock) {
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
    }

    public IssuedCredential issue(String sessionToken,
                                 String userName,
                                 AltaStataFileSystem fileSystem,
                                 String label) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new IllegalArgumentException("sessionToken is required");
        }
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("userName is required");
        }
        if (fileSystem == null) {
            throw new IllegalArgumentException("fileSystem is required");
        }
        String accessKeyId = uniqueAccessKeyId();
        String secretAccessKey = S3CredentialKeyGenerator.generateSecretAccessKey();
        IssuedCredential credential = new IssuedCredential(
                accessKeyId,
                secretAccessKey,
                sessionToken,
                userName,
                fileSystem,
                label == null ? "" : label,
                clock.instant());
        byAccessKeyId.put(accessKeyId, credential);
        return credential;
    }

    /**
     * Resolves the session validation result and credentials mapped to the given S3 Access Key ID.
     *
     * @param accessKeyId query access key ID
     * @return optional containing the S3 resolution result details
     */
    public Optional<S3ResolveResult> resolveForS3(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return Optional.empty();
        }
        IssuedCredential issued = byAccessKeyId.get(accessKeyId);
        if (issued == null) {
            return Optional.empty();
        }
        if (sessionRegistry.resolve(issued.sessionToken()).isEmpty()) {
            byAccessKeyId.remove(accessKeyId, issued);
            return Optional.empty();
        }
        return Optional.of(new S3ResolveResult(
                issued.fileSystem(),
                issued.userName(),
                issued.secretAccessKey()));
    }

    /**
     * Lists all active S3 credentials assigned to the target session token.
     *
     * @param sessionToken owner session token
     * @return list of issued credentials for this session
     */
    public List<IssuedCredential> listForSessionToken(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return List.of();
        }
        return byAccessKeyId.values().stream()
                .filter(c -> c.sessionToken().equals(sessionToken))
                .toList();
    }

    /**
     * Revokes a specific S3 Access Key ID under the owner session token.
     *
     * @param sessionToken owner session token
     * @param accessKeyId S3 access key ID to revoke
     * @return true if successfully revoked; false otherwise
     */
    public boolean revokeForSession(String sessionToken, String accessKeyId) {
        if (sessionToken == null || accessKeyId == null) {
            return false;
        }
        IssuedCredential cred = byAccessKeyId.get(accessKeyId);
        if (cred == null || !cred.sessionToken().equals(sessionToken)) {
            return false;
        }
        return byAccessKeyId.remove(accessKeyId, cred);
    }

    /**
     * Revokes all S3 credentials assigned to the target session token.
     *
     * @param sessionToken owner session token
     */
    public void revokeAllForSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return;
        }
        byAccessKeyId.entrySet().removeIf(e -> e.getValue().sessionToken().equals(sessionToken));
    }

    /**
     * Revokes all S3 credentials assigned to the target user profile name.
     *
     * @param userName target user profile name
     */
    public void revokeAllForUser(String userName) {
        if (userName == null || userName.isBlank()) {
            return;
        }
        byAccessKeyId.entrySet().removeIf(e -> userName.equals(e.getValue().userName()));
    }

    /**
     * Gets total count of active S3 credentials in registry.
     *
     * @return count of issued S3 credentials
     */
    int size() {
        return byAccessKeyId.size();
    }

    /**
     * Loops to find a unique, non-colliding S3 Access Key ID.
     *
     * @return non-colliding S3 Access Key ID
     */
    private String uniqueAccessKeyId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = S3CredentialKeyGenerator.generateAccessKeyId();
            if (!byAccessKeyId.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate unique S3 access key id");
    }

    public record IssuedCredential(
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            String userName,
            AltaStataFileSystem fileSystem,
            String label,
            Instant createdAt) {}

    public record S3ResolveResult(
            AltaStataFileSystem fileSystem,
            String userName,
            String secretAccessKey) {}
}
