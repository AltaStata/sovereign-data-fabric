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

class GrpcUserData {
    private final String accountKey;
    private String userProperties;
    private String privateKeyEncrypted;
    private AltaStataFileSystem altaStataFileSystem;
    private String accessKey;
    private String secretKey;

    GrpcUserData(String accountKey) {
        this.accountKey = accountKey;
    }

    /**
     * Gets the {@link com.altastata.api.AccountId#key()} this gateway profile is
     * keyed by — not a bare {@code myuser}.
     *
     * @return account key
     */
    String getAccountKey() {
        return accountKey;
    }

    /**
     * Gets the raw user properties content.
     *
     * @return properties configuration string
     */
    String getUserProperties() {
        return userProperties;
    }

    /**
     * Sets the user properties content.
     *
     * @param userProperties configuration string to set
     */
    void setUserProperties(String userProperties) {
        this.userProperties = userProperties;
    }

    /**
     * Gets the password-encrypted PEM private key string.
     *
     * @return encrypted private key PEM
     */
    String getPrivateKeyEncrypted() {
        return privateKeyEncrypted;
    }

    /**
     * Sets the password-encrypted PEM private key string.
     *
     * @param privateKeyEncrypted encrypted private key PEM to set
     */
    void setPrivateKeyEncrypted(String privateKeyEncrypted) {
        this.privateKeyEncrypted = privateKeyEncrypted;
    }

    /**
     * Gets the associated AltaStataFileSystem instance.
     *
     * @return active filesystem context
     */
    AltaStataFileSystem getAltaStataFileSystem() {
        return altaStataFileSystem;
    }

    /**
     * Sets the associated AltaStataFileSystem instance.
     *
     * @param altaStataFileSystem active filesystem context to bind
     */
    void setAltaStataFileSystem(AltaStataFileSystem altaStataFileSystem) {
        this.altaStataFileSystem = altaStataFileSystem;
    }

    /**
     * Gets the generated AWS Access Key ID assigned to this session context.
     *
     * @return S3 access key ID
     */
    String getAccessKey() {
        return accessKey;
    }

    /**
     * Sets the generated AWS Access Key ID for this session context.
     *
     * @param accessKey S3 access key ID to set
     */
    void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * Gets the generated AWS Secret Access Key assigned to this session context.
     *
     * @return S3 secret key
     */
    String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the generated AWS Secret Access Key for this session context.
     *
     * @param secretKey S3 secret key to set
     */
    void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
