# MinIO Integration for AltaStata

This directory contains the MinIO integration for AltaStata, providing S3-compatible object storage capabilities using MinIO.

## Overview

MinIO is a high-performance, S3-compatible object storage server that can be run locally or in production environments. This implementation provides:

- **MinIOClient**: Utility class for creating and configuring S3 clients for MinIO
- **MinIODemo**: Comprehensive demo application showing MinIO operations
- **S3-compatible API**: Uses AWS SDK v2 for seamless integration

## Files

- `MinIOClient.scala` - Client utility for MinIO configuration and connection
- `MinIODemo.scala` - Demo application showcasing MinIO operations
- `README.md` - This documentation file (basic setup)

## Quick Start

### 1. Start MinIO Server

#### Local Installation (Recommended)
```bash
# Create a data directory
mkdir -p ~/minio-test-data

# Start MinIO server with explicit configuration
export MINIO_ROOT_USER=minioadmin
export MINIO_ROOT_PASSWORD=minioadmin
minio server ~/minio-test-data --console-address ":9001" --address ":9000"
```

The server will start with:
- API endpoint: http://localhost:9000
- Console (WebUI): http://localhost:9001
- Default credentials: minioadmin/minioadmin

#### Docker (Alternative)
```bash
# Start MinIO using Docker
docker run -p 9000:9000 -p 9001:9001 \
  -e "MINIO_ROOT_USER=minioadmin" \
  -e "MINIO_ROOT_PASSWORD=minioadmin" \
  minio/minio server /data --console-address ":9001"
```

### 2. Run the Demo

```bash
# Using default credentials (minioadmin/minioadmin)
cd altastata-core
./gradlew runMinIODemo

# Or with custom credentials
export MINIO_ACCESS_KEY=your-access-key
export MINIO_SECRET_KEY=your-secret-key
export MINIO_ENDPOINT=http://localhost:9000
./gradlew runMinIODemo
```

### 3. Clean Up (Optional)

```bash
# Stop MinIO server
pkill minio

# Remove test data
rm -rf ~/minio-test-data
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MINIO_ROOT_USER` | `minioadmin` | Root user for MinIO server |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | Root password for MinIO server |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO server endpoint |
| `MINIO_ACCESS_KEY` | `minioadmin` | Access key for client authentication |
| `MINIO_SECRET_KEY` | `minioadmin` | Secret key for client authentication |

### Common Endpoints

- **API Endpoint**: `http://localhost:9000`
- **Console/WebUI**: `http://localhost:9001`
- **Secure MinIO**: `https://localhost:9000` (if configured)
- **Remote MinIO**: `https://your-minio-server.com`

## Usage Examples

### Basic Client Creation

```scala
import com.altastata.cloud.minio.MinIOClient

// Create configuration
val config = MinIOClient.MinIOConfig(
  endpoint = "http://localhost:9000",
  accessKey = "minioadmin",
  secretKey = "minioadmin"
)

// Create S3 client
val client = MinIOClient.createS3Client(config)
```

### Using Environment Variables

```scala
import com.altastata.cloud.minio.MinIOClient

// Create client from environment variables
MinIOClient.createS3ClientFromEnv() match {
  case Success(client) => 
    // Use client
    client.close()
  case Failure(ex) => 
    println(s"Failed to create client: ${ex.getMessage}")
}
```

### Presigned URLs

```scala
import com.altastata.cloud.minio.MinIOClient
import java.time.Duration

val config = MinIOClient.MinIOConfig(
  endpoint = "http://localhost:9000",
  accessKey = "minioadmin",
  secretKey = "minioadmin"
)

val presigner = MinIOClient.createS3Presigner(config)

// Generate presigned upload URL
val putRequest = PutObjectPresignRequest.builder()
  .signatureDuration(Duration.ofMinutes(15))
  .putObjectRequest(PutObjectRequest.builder()
    .bucket("my-bucket")
    .key("my-file.txt")
    .build())
  .build()

val presignedUrl = presigner.presignPutObject(putRequest)
println(s"Upload URL: ${presignedUrl.url()}")
```

## Features Demonstrated

The demo application showcases:

1. **Client Configuration** - Setting up S3 client for MinIO
2. **Bucket Operations** - Creating, listing, and managing buckets
3. **Object Operations** - Uploading, downloading, and listing objects
4. **Advanced Features** - Presigned URLs, bucket policies
5. **Configuration Options** - Environment variables and setup options

## MinIO Console

Access the MinIO web console at http://localhost:9001

Default credentials:
- Username: `minioadmin`
- Password: `minioadmin`

The console provides:
- Bucket management
- File browser
- Access policy configuration
- Server monitoring
- User management

## Integration with AltaStata

This MinIO implementation follows the same patterns as the IBM Cloud Object Storage integration:

- Uses AWS SDK v2 for S3 compatibility
- Provides utility classes for client creation
- Includes comprehensive demo applications
- Supports environment variable configuration
- Follows AltaStata coding conventions

### Advanced Features

This test implementation uses simple shared credentials for development and testing purposes. For production deployments with individual user accounts and role-based access control, see the main MinIO integration documentation.

## Troubleshooting

### Common Issues

1. **Connection Refused**
   - Ensure MinIO server is running
   - Check endpoint URL and port
   - Verify network connectivity

2. **Authentication Failed**
   - Verify access key and secret key
   - Check MinIO server credentials
   - Ensure environment variables are set correctly

3. **Storage Issues**
   - Ensure data directory exists and has proper permissions
   - Use single-drive mode for local development
   - Avoid distributed mode unless specifically needed

### Debug Mode

Enable debug logging by setting the log level:

```bash
export GRADLE_OPTS="-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
./gradlew runMinIODemo
```

## Security Considerations

- Use HTTPS for production deployments
- Change default credentials (`minioadmin`/`minioadmin`)
- Implement proper access controls and bucket policies
- Consider using IAM roles for production environments
- Enable server-side encryption for sensitive data

## Performance

MinIO is designed for high performance:
- Supports concurrent operations
- Optimized for modern hardware
- Compatible with S3 performance patterns
- Suitable for both development and production use

## References

- [MinIO Documentation](https://docs.min.io/)
- [AWS SDK v2 for Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/)
- [S3 API Reference](https://docs.aws.amazon.com/AmazonS3/latest/API/)
- [AltaStata MinIO Documentation](../../../../../../main/scala/com/altastata/cloud/minio/README.md) 