# Altastata Hadoop JAR Size Optimization Guide

This guide explains how to build optimized JARs for the Altastata Hadoop module with significantly reduced file sizes.

## Problem

The default `shadowJar` build creates a very large JAR file (~145MB) due to including many heavy dependencies that may not be necessary for basic HDFS operations.

## Solutions

### 1. Standard Build (Current - ~145MB)

```bash
gradle clean build shadowJar -PexcludeBouncyCastle=true copyDeps
```

**Includes:** All dependencies including cloud providers, web servers, security libraries, etc.

### 2. Minimal Build (~117MB)

```bash
gradle clean build shadowJar -PexcludeBouncyCastle=true -PminimalBuild=true copyDeps
```

**Excludes:**
- BouncyCastle crypto (8.3MB saved)
- Jetty web server components
- ZooKeeper and Curator
- Jersey REST framework
- Kerberos authentication
- YARN and MapReduce components

**Keeps:**
- gRPC libraries - **REQUIRED for Google Cloud**
- Cloud provider SDKs (AWS, Azure, Google Cloud) - **REQUIRED**

### 3. Minimal Build without Google Cloud (~82MB)

```bash
gradle clean build shadowJar -PexcludeBouncyCastle=true -PminimalBuild=true -PnoGCP=true copyDeps
```

**Excludes everything from minimal build PLUS:**
- Google Cloud SDK (~15MB saved)
- gRPC libraries (~20MB saved)

**Keeps:**
- AWS SDK - **Works for S3 operations**
- Azure SDK - **Works for Blob Storage**

**Use when:** You only need AWS S3 and/or Azure Blob Storage, not Google Cloud Platform

### 4. Dataproc Build (~110MB)

```bash
gradle clean build shadowJar -PdataprocBuild=true -PexcludeBouncyCastle=true copyDeps
```

**Excludes:**
- Netty dependencies (prevents ClassCastException conflicts in Dataproc)
- BouncyCastle crypto (installed separately on Dataproc)
- Conflicting logging implementations (SLF4J, Log4j)

**Keeps:**
- All cloud SDKs (AWS, Azure, Google Cloud)
- gRPC libraries for Google Cloud compatibility
- Core AltaStata functionality

**Use when:** Deploying to Google Cloud Dataproc clusters



## Major Dependencies Removed

| **Component** | **Size** | **Purpose** | **Impact of Removal** |
|---------------|----------|-------------|------------------------|
| `grpc-xds-1.69.0.jar` | 9.4MB | gRPC networking | ❌ No gRPC-based services |
| `grpc-netty-shaded-1.69.0.jar` | 9.3MB | gRPC transport | ❌ No gRPC-based services |
| `bcprov-jdk18on-1.85.jar` | 8.3MB | BouncyCastle crypto | ❌ Reduced crypto capabilities |
| `hadoop-hdfs-client-3.3.5.jar` | 5.3MB | HDFS client | ✅ **KEPT** - Essential for HDFS |
| `scala-library-2.12.20.jar` | 5.2MB | Scala runtime | ✅ **KEPT** - Required for Scala code |
| `cats-core_2.12-2.1.1.jar` | 4.6MB | Functional programming | ✅ **KEPT** - Used by altastata-core |
| `hadoop-common-3.3.5.jar` | 4.3MB | Hadoop core | ✅ **KEPT** - Essential for Hadoop |
| `conscrypt-openjdk-uber-2.5.2.jar` | 4.3MB | Crypto provider | ❌ Alternative crypto available |

## Build Commands Summary

```bash
# Full build (145MB)
gradle clean build shadowJar -PexcludeBouncyCastle=true copyDeps

# Minimal build (117MB) - keeps all cloud SDKs + gRPC
gradle clean build shadowJar -PexcludeBouncyCastle=true -PminimalBuild=true copyDeps

# Minimal build without Google Cloud (82MB) - AWS + Azure only
gradle clean build shadowJar -PexcludeBouncyCastle=true -PminimalBuild=true -PnoGCP=true copyDeps

# Dataproc build (110MB) - optimized for Google Cloud Dataproc
gradle clean build shadowJar -PdataprocBuild=true -PexcludeBouncyCastle=true copyDeps

# Check sizes
ls -lh build/libs/
```

## What's Excluded and Why

### Safe to Exclude for Basic HDFS Operations

1. **Web Server Components (Jetty)**
   - Used for Hadoop web UIs and REST APIs
   - Not needed for programmatic file operations

2. **ZooKeeper & Curator**
   - Used for cluster coordination
   - Not needed for single-client file operations

3. **Jersey REST Framework**
   - Used for Hadoop REST APIs
   - Not needed for native HDFS operations

4. **Kerberos Authentication (Kerby)**
   - Used for enterprise security
   - Can use simpler authentication methods

5. **gRPC Libraries**
   - Used for Google Cloud services
   - Can be excluded with `-PnoGCP=true` if Google Cloud not needed

6. **Cloud Provider SDKs**
   - AWS, Azure, Google Cloud integrations
   - **NOW KEPT** - Required for cloud storage operations

7. **YARN & MapReduce**
   - Used for distributed computing
   - Not needed for file system operations

### Potentially Risky Exclusions

1. **BouncyCastle Crypto**
   - May be needed for some encryption features
   - Use `-PexcludeBouncyCastle=false` if crypto issues occur

2. **Conscrypt**
   - Alternative crypto provider
   - May be needed for TLS connections

## Testing Your Build

After building with exclusions, test basic operations:

```bash
# Test HDFS connectivity
java -cp altastata-hadoop-minimal.jar com.altastata.hadoop.TestHDFS

# Test file operations
java -cp altastata-hadoop-minimal.jar com.altastata.hadoop.FileOperations
```

## Troubleshooting

### ClassNotFoundException

If you get `ClassNotFoundException` for excluded libraries:

1. **For crypto issues:** Remove `-PexcludeBouncyCastle=true`
2. **For authentication issues:** Remove Kerberos exclusions
3. **For network issues:** Remove gRPC exclusions

### Build Customization

Edit `build.gradle` to customize exclusions:

```gradle
// Add specific exclusions
exclude(dependency('specific.group:artifact'))

// Remove exclusions
// Comment out exclude lines you want to keep
```

## File Size Comparison

| **Build Type** | **Actual Size** | **Use Case** |
|----------------|-----------------|--------------|
| Full | ~145MB | Development, full features |
| Minimal | ~117MB | Production, optimized HDFS + all clouds |
| Minimal (no GCP) | ~82MB | Production, AWS + Azure only |

## Recommendations

1. **For AWS + Azure + Google Cloud:** Use minimal build (117MB) - 19% reduction with full cloud support
2. **For AWS + Azure only:** Use minimal build with `-PnoGCP=true` (82MB) - 43% reduction
3. **Test thoroughly** - Ensure your use case works with exclusions
4. **Keep full build** - For development and debugging

## Advanced Optimization

For even smaller JARs, consider:

1. **ProGuard/R8** - Code shrinking and obfuscation
2. **Custom Hadoop build** - Build Hadoop with only needed modules
3. **Native compilation** - GraalVM native-image (experimental)
4. **Modular JARs** - Split into multiple smaller JARs 