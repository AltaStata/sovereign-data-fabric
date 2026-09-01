# Snowflake S3 Compatibility Test Suite

This document describes how to run the [Snowflake S3Compat API Test Suite](https://github.com/snowflakedb/snowflake-s3compat-api-test-suite) against an **already running** AltaStata S3 gateway.

The upstream suite lives in a sibling directory (gitignored in this repo):

```text
workspace/
├── sovereign-data-fabric/               ← this repository
└── snowflake-s3compat-api-test-suite/    ← clone here
```

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| **Running gateway** | S3 on `:9876` and gRPC on `:9877` in the **same JVM** (`./gradlew :altastata-services:run` with both enabled) |
| **Java 8+** | Required by the Snowflake Maven project |
| **Maven 3.x** | To run `mvn test` |
| **Python `altastata` wheel** | Issues S3 credentials (`LoginV2` → `IssueCredentials`) |
| **Test suite checkout** | See [Install the test suite](#install-the-test-suite) |

### Gateway must expose gRPC + S3 together

Issued S3 credentials live in an in-memory registry inside the same process as gRPC.

```bash
./gradlew :altastata-services:run \
  -Daltastata.services.s3gateway.enabled=true \
  -Daltastata.services.grpc.enabled=true \
  -Daltastata.test.mode=true
```

Verify the gateway is listening (404 on `/` is normal — any non-zero HTTP code means it is up):

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9876/
# expected: 404 (not 000)
```

## Install the test suite

Clone once next to `altastata-s3-gateway`:

```bash
cd /path/to/workspace
git clone git@github.com:snowflakedb/snowflake-s3compat-api-test-suite.git
cd snowflake-s3compat-api-test-suite
mvn clean install -DskipTests
```

The `spf4j-ui` dependency may require a GitHub Packages token in `~/.m2/settings.xml` — see the upstream [Readme.md](https://github.com/snowflakedb/snowflake-s3compat-api-test-suite).

## One-time test data setup

The Snowflake suite expects:

- Buckets: `test-bucket`, `not-accessible-bucket`
- **1100 objects** under prefix `test-suite/page-listing-test/` in `test-bucket` (for `listObjectsV2` pagination)

Issue credentials with the Python wheel, then create the buckets and seed objects with boto3:

```python
from altastata import AltaStataFunctions

f = AltaStataFunctions.from_account_dir(
    "~/.altastata/accounts/amazon.rsa.bob123",
    password="your_password",
)
f.install_aws_env()
s3 = f.boto3_s3()
for name in ("test-bucket", "not-accessible-bucket"):
    try:
        s3.create_bucket(Bucket=name)
    except Exception:
        pass
for i in range(1100):
    s3.put_object(
        Bucket="test-bucket",
        Key=f"test-suite/page-listing-test/obj-{i:04d}",
        Body=b"x",
    )
```

`-Daltastata.test.mode=true` is required for the `test-bucket` / `not-accessible-bucket` names.

## Maven suite

After credentials are issued and buckets exist:

```bash
export END_POINT=http://127.0.0.1:9876
export S3COMPAT_ACCESS_KEY="$AWS_ACCESS_KEY_ID"
export S3COMPAT_SECRET_KEY="$AWS_SECRET_ACCESS_KEY"
export BUCKET_NAME_1=test-bucket
export REGION_1=us-east-1
export REGION_2=us-west-2
export NOT_ACCESSIBLE_BUCKET=not-accessible-bucket
export PREFIX_FOR_PAGE_LISTING=test-suite/page-listing-test
export PAGE_LISTING_TOTAL_SIZE=1100

cd ../snowflake-s3compat-api-test-suite/s3compatapi
mvn test -Dtest=S3CompatApiTest
```

Run a single API:

```bash
mvn test -Dtest='S3CompatApiTest#getObject'
```

**Note:** The full `S3CompatApiTest` run can take several minutes; `putObject` includes a large (up to 5 GB) upload case.

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `GATEWAY_URL` | `http://127.0.0.1:9876` | S3 REST endpoint (prefer `127.0.0.1` over `localhost`) |
| `ALTASTATA_GRPC_HOST` | `127.0.0.1` | gRPC host |
| `ALTASTATA_GRPC_PORT` | `9877` | gRPC port |
| `PASSWORD` / `ALTASTATA_PASSWORD` | `123` | Account password for LoginV2 |
| `TEST_SUITE_DIR` | `../snowflake-s3compat-api-test-suite` | Path to cloned suite (relative to `altastata-s3-gateway` CWD) |
| `ALTASTATA_ACCOUNT_DIR` | — | Use co-located account dir instead of embedded test properties |

Snowflake test variables (set automatically by wrapper scripts):

| Variable | Value |
|----------|-------|
| `END_POINT` | Same as `GATEWAY_URL` |
| `S3COMPAT_ACCESS_KEY` | From `IssueCredentials` |
| `S3COMPAT_SECRET_KEY` | From `IssueCredentials` |
| `BUCKET_NAME_1` | `test-bucket` |
| `NOT_ACCESSIBLE_BUCKET` | `not-accessible-bucket` |
| `PREFIX_FOR_PAGE_LISTING` | `test-suite/page-listing-test` |
| `PAGE_LISTING_TOTAL_SIZE` | `1100` |

## APIs covered

```
getBucketLocation    getObject           getObjectMetadata
putObject            listObjectsV2       deleteObject
deleteObjects        copyObject          generatePresignedUrl
```

## Troubleshooting

### `gRPC S3 credential bootstrap failed`

- Confirm gRPC port is open: `(echo >/dev/tcp/127.0.0.1/9877) 2>/dev/null && echo ok`
- gRPC and S3 must be the same JVM; credentials are not shared across processes.
- Check the Services JVM log if LoginV2 fails (HPCS/PKCS#11 misconfiguration often surfaces there).

### `S3 gateway not reachable`

The health check treats **any HTTP response** (including 404) as “up”. If you see `000`, nothing is listening on `:9876`.

### Test-mode buckets (`test-bucket`, `not-accessible-bucket`)

These bucket names are only allowed when the gateway runs with **`-Daltastata.test.mode=true`**. Production/HPCS accounts (e.g. jupyter on `:9876`) may return **500** on bucket create. For Snowflake tests, use a test-mode Gradle gateway:

Use a test-mode Gradle gateway and the Python SDK (`install_aws_env()`), then run Maven with `END_POINT=http://127.0.0.1:9876`.

### `localhost` vs `127.0.0.1`

Prefer **`http://127.0.0.1:9876`** so IPv6 `localhost` does not hit the wrong process.

### `listObjectsV2` fails or pagination errors

Ensure 1100 files exist under `test-suite/page-listing-test/` (see the boto3 seed snippet above).

### Do not use hardcoded `testkey` / `testsecret`

The file `snowflake-s3compat-api-test-suite/altastata_s3_gateway_all_tests.sh` uses legacy static keys. Current gateways require gRPC-issued credentials from the Python wheel.

### Test reports

After Maven runs:

```
snowflake-s3compat-api-test-suite/s3compatapi/target/surefire-reports/
```
