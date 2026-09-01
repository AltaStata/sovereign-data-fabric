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

package com.altastata.s3gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.altastata.api.AltaStataFileSystem;

/**
 * Per-session S3 auth context: SigV4 validators and {@link S3Service} for one
 * access key. Built from gRPC {@code IssueCredentials} or test-mode defaults.
 */
public class UserData {

    private static final Logger logger = LoggerFactory.getLogger(UserData.class);

    private String accessKey;
    private String secretKey;
    private final String region;

    private AltaStataFileSystem altaStataFileSystem;
    private S3Service s3Service;
    private AwsGeneralSigV4Validator generalValidator;
    private AwsPresignedValidator presignedValidator;

    /**
     * Test-mode UserData with fixed {@code testkey:testsecret} credentials.
     */
    public UserData(String region) {
        this.region = region;
        this.accessKey = "testkey";
        this.secretKey = "testsecret";
        this.altaStataFileSystem = null;
        this.s3Service = new MockS3ServiceSimple(this.accessKey, this.secretKey, region);
        initializeValidators();
        logger.info("UserData initialized for test mode (region={})", region);
    }

    /**
     * Initialize signature validators with current credentials.
     */
    public void initializeValidators() {
        if (this.accessKey != null && this.secretKey != null) {
            this.generalValidator = new AwsGeneralSigV4Validator(
                    accessKey,
                    secretKey,
                    region
            );

            this.presignedValidator = new AwsPresignedValidator(
                    accessKey,
                    secretKey,
                    region
            );

            logger.info("UserData: Initialized validators for accessKey: {}", this.accessKey);
        } else {
            logger.warn("UserData: Cannot initialize validators - missing credentials");
        }
    }

    /**
     * Build {@link UserData} for S3 requests authenticated via issued gRPC
     * credentials ({@code S3CredentialsRegistry}).
     */
    public static UserData forIssuedCredentials(String accessKey,
                                                String secretKey,
                                                AltaStataFileSystem fileSystem,
                                                String region) {
        UserData data = new UserData(region);
        data.accessKey = accessKey;
        data.secretKey = secretKey;
        data.altaStataFileSystem = fileSystem;
        data.s3Service = new AltaStataS3Service(fileSystem);
        data.initializeValidators();
        return data;
    }

    /**
     * Gets the user access key.
     *
     * @return S3 access key ID
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * Gets the user secret key.
     *
     * @return S3 secret access key
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Gets the associated S3 service provider instance.
     *
     * @return S3 service provider
     */
    public S3Service getS3Service() {
        return s3Service;
    }

    /**
     * Gets the general S3 signature v4 request validator.
     *
     * @return AWS SigV4 request validator
     */
    public AwsGeneralSigV4Validator getGeneralValidator() {
        return generalValidator;
    }

    /**
     * Gets the S3 presigned URL signature validator.
     *
     * @return AWS presigned signature validator
     */
    public AwsPresignedValidator getPresignedValidator() {
        return presignedValidator;
    }
}
