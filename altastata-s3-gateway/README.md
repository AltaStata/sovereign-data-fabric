# AltaStata S3 Gateway

S3-compatible REST handlers that run **inside** the `altastata-services` JVM
(port `9876`). They are not a standalone application and AltaStata does not
publish a container image for them. See [UBER_JARS.md](../docs/guides/UBER_JARS.md).

## Authentication

S3 requests use **temporary AWS-style credentials** issued by the co-hosted
gRPC gateway (`LoginV2` → `IssueCredentials`). The S3 library does **not**
expose admin PUT routes (`/setUserProperties`, `/setPrivateKey`, `/setPassword`).

gRPC and S3 must share the same process so `S3CredentialsRegistry` can resolve
issued keys.

```bash
./gradlew :altastata-services:run \
  -Daltastata.services.s3gateway.enabled=true \
  -Daltastata.services.grpc.enabled=true
```

- S3: `http://127.0.0.1:9876`
- gRPC / Web Console: `http://127.0.0.1:9877`

**Test mode** (`-Daltastata.test.mode=true`) accepts hardcoded `testkey` /
`testsecret` against an in-memory mock store. Use it only for local smoke tests.

## Obtain credentials

Log in through the Web Console at `http://127.0.0.1:9877` (or gRPC `LoginV2` →
`IssueCredentials`). Then point any S3 client at `:9876` with those keys:

```bash
aws s3 ls s3://altastata-bucket/Public/ --endpoint-url http://127.0.0.1:9876
```

See [HOWTO.md](../docs/guides/HOWTO.md). The Python `boto3` helper lives in
[altastata-python-package](https://github.com/AltaStata/altastata-python-package).

## Unit tests

```bash
./gradlew :altastata-s3-gateway:test
```

Snowflake S3-compat notes: [SNOWFLAKE_S3COMPAT_TESTS.md](SNOWFLAKE_S3COMPAT_TESTS.md).

## Multipart upload

The gateway implements the AWS S3 multipart API (`CreateMultipartUpload`,
`UploadPart`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListParts`).
Clients should use boto3 / the AWS CLI against `http://127.0.0.1:9876` after
`install_aws_env()`.

## Troubleshooting

**403 / SignatureDoesNotMatch.** Confirm gRPC and S3 run in the same
`altastata-services` JVM, the session has not expired, and the client uses
SigV4 against port `9876`.

**Connection refused.** S3 is `9876`, gRPC/Console is `9877`. Start
`./gradlew :altastata-services:run` with both gates enabled.
