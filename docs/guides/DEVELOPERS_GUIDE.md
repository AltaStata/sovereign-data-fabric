# AltaStata Developers Guide

This guide covers development setup, builds, testing, and deployment workflows for the AltaStata core modules, libraries, and services.

---

## 1. Directory Structure Overview

This repository contains these source-available modules:

- `altastata-core` — Scala/Java library: cloud storage, RSA/PQC, file APIs.
- `altastata-services` — One JVM: S3, gRPC, Py4J, Web Console, MCP (see [README.md](../../README.md) §4). S3, gRPC, and Py4J share connection pools and client-side encryption caches.
- `altastata-grpc` — gRPC protobuf definitions and handlers (runs inside Services).
- `altastata-s3-gateway` — S3-compatible REST handlers (runs inside Services).
- `altastata-mcp` — MCP stdio for Claude Desktop / Cursor (Services uber JAR).
- `altastata-hadoop` — Spark/Hadoop `FileSystem` (`altastata://`).
- `altastata-examples` — Java, Scala, Spark, and streaming samples.
- `altastata-ui` — JavaFX desktop app ([USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md)).

The Admin Tool is a separate installer ([ADMIN_TOOL_GUIDE.md](ADMIN_TOOL_GUIDE.md));
its sources are not in the public BSL tree. The pip wheel is
`altastata-python-package` (`1.0.YYYYMMDD.N`). See that repo’s
[VERSIONING.md](https://github.com/AltaStata/altastata-python-package/blob/main/docs/guides/VERSIONING.md).

---

## 2. Java and IDE Setup

### Java SDK Requirements
- **Java 17** is required across all Java and Scala components.

### IntelliJ IDEA JVM Configurations

#### For UI/JavaFX Desktop Application (`altastata-ui`):
```bash
-Xmx4g
-Xms2g
--module-path=/path/to/javafx-sdk/lib 
--add-modules=javafx.controls,javafx.web,javafx.media,javafx.graphics
```

#### For CLI, Services, and Spark Modules:
```bash
-Xmx4g 
-Xms2g
--add-opens=java.base/java.lang=ALL-UNNAMED 
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED 
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED 
--add-opens=java.base/java.io=ALL-UNNAMED 
--add-opens=java.base/java.net=ALL-UNNAMED 
--add-opens=java.base/java.nio=ALL-UNNAMED 
--add-opens=java.base/java.util=ALL-UNNAMED 
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED 
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED 
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED 
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED 
--add-opens=java.base/sun.security.action=ALL-UNNAMED 
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED 
--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED
```

---

## 3. Main Build and Development Commands

### Building the Entire Project
```bash
./gradlew clean build -x test
```

### Running the Unified `altastata-services` JVM App
This launches the single JVM (S3, gRPC, Py4J; Web Console when `ALTASTATA_WEB_UI_DIR` is set):
```bash
./gradlew :altastata-services:run
```

### Building individual Uber JARs
Published JARs (no source build): [how to use the Hadoop and Services uber JARs](UBER_JARS.md).

```bash
# Hadoop Shaded Uber JAR (Databricks)
./gradlew :altastata-hadoop:clean :altastata-hadoop:shadowJar \
  -PexcludeBouncyCastle=true

# Unified Services Uber JAR
./gradlew :altastata-services:clean :altastata-services:shadowJar
```

### Building the JavaFX Desktop Client app
```bash
# Run the UI locally
./gradlew :altastata-ui:run

# Build the native desktop application (Mac, Windows, Linux)
# Published Releases ship macOS arm64 .dmg and Windows x64 .exe only.
./gradlew :altastata-ui:jpackage

# Or run from runtime image (from repository root)
./gradlew :altastata-ui:runtime
./altastata-ui/build/image/bin/AltaStataUI
```

---

## 4. Run and Test SecureCloud

### Local Fast-Feedback Tests
```bash
./gradlew :altastata-core:test --tests "com.altastata.filesystem.securecloud.*" --no-daemon
```

### What `./gradlew build` does and does not run

`altastata-core` disables its `test` and `compileTestJava` tasks whenever the
task graph contains `build`, so a full build never runs core tests — run them
explicitly with `./gradlew :altastata-core:test`.

A plain `./gradlew test` also reports a number of **skipped** tests. Those are
cloud integration suites that opt in through environment variables and are
skipped by design when the variable is absent — nothing is failing:

| Variable | Suites it enables |
|----------|-------------------|
| `RUN_AZURE_IT` | Azure stream and transfer-scheduling ITs |
| `RUN_AZURE_BULK_IT` | Azure 1100-file bulk soak (with `RUN_AZURE_IT`) |
| `RUN_MANY_SMALL_FILES_IT` | Azure + GCS many-small-files sharing ITs |
| `RUN_FUSION_IT` | IBM Fusion ITs (also needs `FUSION_ENV_SH` / `FUSION_CREDENTIALS_FILE` / `ALTASTATA_ACCOUNT_DIR`) |
| `RUN_STREAM_BENCHMARK` | Stream throughput benchmark |

HPCS suites additionally need `ALTASTATA_HPCS_YAML`.

### Cloud Integration Testing (opt-in)

These write real objects to your configured Azure / GCS cloud storage. They are skipped unless you set the corresponding environment flag.

```bash
# Azure (streams + transfer scheduling)
RUN_AZURE_IT=1 ./gradlew :altastata-core:test --tests TransferSchedulingAzureITSpec --tests SecureCloudStreamITSpec --no-daemon

# Azure bulk soak (1100 files)
RUN_AZURE_IT=1 RUN_AZURE_BULK_IT=1 ./gradlew :altastata-core:cleanTest :altastata-core:test --tests TransferSchedulingAzureITSpec --no-daemon

# GCS many-small-files share
RUN_MANY_SMALL_FILES_IT=1 ./gradlew :altastata-core:test --tests ManySmallFilesShareGoogleITSpec --no-daemon
```

---

## 5. Examples (`altastata-examples`)

Java and Scala samples for [HOWTO.md](HOWTO.md) and
[Low-level-Scala-API.md](Low-level-Scala-API.md). Classes, accounts, and Gradle
tasks: [altastata-examples/README.md](../../altastata-examples/README.md).

```bash
./gradlew :altastata-examples:runSimpleTest
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.api.StoreAndRetrieve -PappArgs='your-password'
```

### Running Spark Examples
```bash
# Run the Spark/Hadoop file system integration test
./gradlew :altastata-examples:runSpark
```

### Running Audio Streams Examples
```bash
# Run the streaming publisher application
./gradlew :altastata-examples:runStreamsApp
```

---

## 6. Security and Licences

- For high-level architecture and component overviews, see [README.md](README.md).
- For vulnerability reporting and credential handling, see
  [SECURITY.md](../../SECURITY.md).
- For SDK API documentation (Java), see the [AltaStata API Docs on GitHub Pages](https://altastata.github.io/sovereign-data-fabric/api/javadoc/).
- Licensing: [LICENSE.md](../../LICENSE.md).
