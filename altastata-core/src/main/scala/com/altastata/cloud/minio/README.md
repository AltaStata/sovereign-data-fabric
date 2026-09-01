# MinIO Implementation for AltaStata

This directory contains the MinIO object storage implementation for AltaStata, providing S3-compatible storage with simplified configuration.

## Overview

MinIO is an S3-compatible object storage server that can run on-premises or in the cloud. This implementation provides:

- **Simplified Setup**: No complex IAM policies like cloud providers
- **S3 Compatibility**: Uses AWS SDK v2 with MinIO-specific configuration  
- **Bucket Isolation**: Each user gets their own set of buckets for data isolation
- **Shared Access**: Users share the same MinIO credentials (encrypted per user)
- **Local Development**: Perfect for development and testing environments

## Architecture

### Bucket Structure

Each organization gets buckets with the pattern: `altastata-{org}-{type}-{user}`

**Per-User Buckets:**
- `altastata-{org}-catalog-{username}` - User's file catalog and metadata
- `altastata-{org}-chunks-{username}` - User's encrypted file chunks  
- `altastata-{org}-changes-{username}` - User's change logs and versioning
- `altastata-{org}-dataattributes-{username}` - User's file attributes and properties

**Shared Buckets:**
- `altastata-{org}-users-all` - Shared user metadata (readable by all users)

### Components

1. **MinIOCloudObjectStorageManager.scala** - Low-level S3 operations using AWS SDK v2
2. **MinIOCloudObjectHandler.scala** - High-level bucket operations

Provision MinIO users and buckets with the **Admin Tool** — see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md).

## Prerequisites

### 1. MinIO Server Setup

**Option A: Binary Installation (Recommended)**
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

**Option B: Docker (Alternative)**
```bash
# Start MinIO using Docker
docker run -p 9000:9000 -p 9001:9001 \
  --name minio \
  -e "MINIO_ROOT_USER=minioadmin" \
  -e "MINIO_ROOT_PASSWORD=minioadmin" \
  -v ~/minio-test-data:/data \
  minio/minio server /data --console-address ":9001"
```

**Option C: Production Deployment**
- Follow [MinIO deployment guide](https://docs.min.io/docs/minio-deployment-quickstart-guide.html)
- Configure TLS certificates for HTTPS
- Set up proper access/secret keys

### 2. Admin Tool

Provision MinIO users, policies, and AltaStata buckets with the Admin Tool — see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md). The optional `mc` client is useful for inspecting MinIO after Admin has run:

```bash
brew install minio/stable/mc  # macOS
mc alias set myminio http://localhost:9000 minioadmin minioadmin
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

### Admin Setup

When setting up MinIO in AltaStata Admin, provide:

```properties
minio-endpoint=http://localhost:9000
minio-access-key=minioadmin  
minio-secret-key=minioadmin
```

**Production Configuration:**
```properties
minio-endpoint=https://your-minio-server.com
minio-access-key=your-access-key
minio-secret-key=your-secret-key
```

### User Properties

Each user gets a properties file with encrypted credentials:

```properties
accounttype=minio-secure
myuser=john-doe
metadata-encryption=RSA
acccontainer-prefix=altastata-myorg-
minio-endpoint=http://localhost:9000
minio-access-key=<encrypted-access-key>
minio-secret-key=<encrypted-secret-key>
```

## Security Model

### Access Control

MinIO implementation supports two approaches:

**Option 1: Admin Tool (recommended)**
- **Individual Users**: Each user gets their own MinIO credentials
- **Role-Based Policies**: Custodian, regular user, and cross-user access permissions
- **Provisioning**: Run the Admin Tool after MinIO is up — see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md)

**Option 2: Shared Credentials Approach (Fallback)**
- **Shared Credentials**: All users use the same MinIO access/secret keys
- **Bucket Naming**: Users can only access their own buckets by convention
- **Encryption**: All credentials are encrypted with each user's public key
- **Cross-User Access**: Limited to specific use cases (reading others' chunks for sharing)

### Data Protection

1. **Encryption at Rest**: MinIO supports server-side encryption
2. **Encryption in Transit**: Use HTTPS endpoints in production
3. **Client-Side Encryption**: AltaStata encrypts data before sending to MinIO
4. **Key Management**: Private keys stored locally, encrypted with user passwords

## Development

### Integration with AltaStata

The MinIO implementation integrates with AltaStata's `Account.scala`:

```scala
case "minio-secure" => new MinIOCloudObjectHandler()(this)
```

### API Usage

```scala
// Store object
val handler = new MinIOCloudObjectHandler()
handler.storeCatalogObject("metadata.json", data)

// Retrieve object  
val data = handler.retrieveCatalogObject("metadata.json")

// List objects
val objects = handler.listCatalogObjects("prefix/")
```

## Operations

### Bucket Management

**Automatic Bucket Creation:**
- Organization setup creates shared buckets (via Java S3 API)
- User creation creates individual user buckets (via Java S3 API)
- Cleanup operations delete all organization buckets (via Java S3 API)

**User Management:**
- Individual users and policies: Admin Tool — see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md)

### Monitoring

Use MinIO Console (http://localhost:9001) to:
- Monitor bucket usage
- View access logs
- Manage policies and users (if needed)

### Backup

```bash
# Backup specific buckets
mc mirror local/altastata-myorg-users-all /backup/users-all/

# Backup all organization buckets
mc mirror local/altastata-myorg-* /backup/organization/
```

## Troubleshooting

### Common Issues

1. **Connection Refused**
   - Verify MinIO server is running
   - Check endpoint URL and port
   - Confirm firewall settings

2. **Access Denied**
   - Verify access/secret keys are correct
   - Check MinIO server logs
   - Confirm bucket exists

3. **Bucket Creation Failed**
   - Check MinIO server has write permissions
   - Verify disk space is available
   - Review MinIO server configuration

4. **Authentication Failed**
   - Verify access key and secret key
   - Check MinIO server credentials
   - Ensure environment variables are set correctly

5. **Storage Issues**
   - Ensure data directory exists and has proper permissions
   - Use single-drive mode for local development
   - Avoid distributed mode unless specifically needed

### Debug Logging

Enable detailed logging in AltaStata:
```properties
logging.level.com.altastata.cloud.minio=DEBUG
logging.level.software.amazon.awssdk=INFO
```

### Debug Mode

Enable debug logging by setting the log level:

```bash
export GRADLE_OPTS="-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
./gradlew runMinIODemo
```

### MinIO Server Logs

```bash
# View MinIO server logs
docker logs minio

# Or for binary installation
journalctl -u minio
```

## Production Considerations

### High Availability

- Deploy MinIO in [distributed mode](https://docs.min.io/docs/distributed-minio-quickstart-guide.html)
- Use load balancers for client access
- Configure multiple MinIO servers

### Performance

- Use SSD storage for better I/O performance
- Configure appropriate disk setup (RAID, JBOD)
- Tune MinIO server settings for your workload

### Security

- Use HTTPS endpoints with valid TLS certificates
- Rotate access/secret keys regularly
- Enable MinIO server-side encryption
- Configure network security (VPN, firewalls)
- Use strong passwords for MinIO root credentials

### Monitoring

- Set up monitoring with Prometheus/Grafana
- Configure MinIO metrics collection
- Monitor disk usage and performance
- Set up alerting for errors and capacity

## Performance

MinIO is designed for high performance:
- Supports concurrent operations
- Optimized for modern hardware
- Compatible with S3 performance patterns
- Suitable for both development and production use

## Comparison with Cloud Providers

| Feature | MinIO | AWS S3 | IBM COS | Azure Blob |
|---------|-------|--------|---------|------------|
| Setup Complexity | Simple | Complex (IAM) | Complex (IAM) | Complex (AAD) |
| Local Development | Excellent | Limited | Limited | Limited |
| Cost | Free (self-hosted) | Pay-per-use | Pay-per-use | Pay-per-use |
| S3 Compatibility | Full | Native | Full | Partial |
| Enterprise Features | Good | Excellent | Excellent | Excellent |

MinIO is ideal for:
- Development and testing environments
- On-premises deployments
- Cost-sensitive applications
- Organizations wanting full control over their data

## Resources

- [MinIO Documentation](https://docs.min.io/)
- [MinIO SDKs](https://docs.min.io/docs/minio-client-complete-guide.html)
- [S3 API Compatibility](https://docs.min.io/docs/minio-server-limitations.html)
- [Production Deployment](https://docs.min.io/docs/minio-deployment-quickstart-guide.html) 