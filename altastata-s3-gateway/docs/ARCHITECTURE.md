# AltaStata S3 Gateway — Architecture

`altastata-s3-gateway` is a **library** inside the `altastata-services` JVM
(port `9876`). Session credentials are issued by the co-hosted gRPC service
on port `9877`. See [UBER_JARS.md](../../docs/guides/UBER_JARS.md).

There is no standalone S3 container and no admin HTTP PUT bootstrap
(`/setUserProperties`, `/setPrivateKey`, `/setPassword`).

## Technology stack

- **Framework**: Micronaut (Netty)
- **Java**: 17+
- **Build**: Gradle; the deployable artifact is the Services uber JAR

## Service layer

```
S3Service (abstract)
├── MockS3ServiceSimple (in-memory test mode)
└── AltaStataS3Service (AltaStata integration)
```

- **S3Controller** — S3-compatible REST
- **S3Service** — service interface
- Credentials come from `S3CredentialsRegistry` in the same JVM (gRPC
  `LoginV2` → `IssueCredentials`), or from hardcoded `testkey`/`testsecret`
  when `-Daltastata.test.mode=true`

## S3 API

- `GET /` — list buckets
- `PUT /{bucket}/{key}` — upload
- `GET /{bucket}/{key}` — download
- `HEAD /{bucket}/{key}` — metadata
- `DELETE /{bucket}/{key}` — delete
- `GET /{bucket}?list-type=2` — list objects v2
- Multipart: `CreateMultipartUpload`, `UploadPart`, `Complete`, `Abort`, `ListParts`

SigV4 validation, S3-shaped XML errors, `x-amz-meta-*`, ETags, HTTP Range.

## Clients

Use the Python wheel (`AltaStataFunctions.from_account_dir` → `boto3_s3()` /
`install_aws_env()`), the Web Console at `:9877`, or any SigV4 S3 client
against `http://127.0.0.1:9876`.

## Data flow

1. HTTP S3 request on `:9876`
2. SigV4 check against issued (or test-mode) credentials
3. `AltaStataS3Service` translates to AltaStata file operations
4. S3-compatible response
