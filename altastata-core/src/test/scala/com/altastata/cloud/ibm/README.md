# IBM Cloud Object Storage Scala Application

This directory contains a Scala application that demonstrates IBM Cloud Object Storage (Ceph-based) operations using AWS SDK v2.

## Files

- `IBMCloudObjectStorageDemo.scala` - Main demo application showcasing all operations
- `IBMCloudObjectStorageClient.scala` - Utility class for creating IBM COS clients
- `README.md` - This documentation

## Application Overview

The demo application demonstrates:

1. **📋 Client Configuration** - How to set up S3 client for IBM COS
2. **🪣 Bucket Operations** - List, create, and delete buckets
3. **📄 Object Operations** - Upload, list, download, and delete objects
4. **🚀 Advanced Features** - Presigned URLs and STS token generation
5. **⚙️ Configuration Validation** - Endpoint and credential validation

## Based on Ceph Documentation

This implementation follows the [Ceph S3 Java examples](https://docs.ceph.com/en/latest/radosgw/s3/java/) and adapts them for IBM Cloud Object Storage with Scala.

## Prerequisites

1. **Gradle Dependencies** (already included in `build.gradle`):
   - `software.amazon.awssdk:s3:2.29.39`
   - `software.amazon.awssdk:sts:2.29.39`
   - `org.scala-lang:scala-library:2.12.20`

2. **IBM COS Credentials**: 
   - HMAC access/secret keys (recommended)
   - Or IBM Cloud API key (requires additional implementation)

## 🔑 Getting HMAC Credentials (IBM_COS_ACCESS_KEY and IBM_COS_SECRET_KEY)

### **Step 1: Access IBM Cloud Console**
1. Go to [https://cloud.ibm.com](https://cloud.ibm.com)
2. Log into your IBM Cloud account

### **Step 2: Navigate to Your Cloud Object Storage Instance**
1. Go to **Resource List** (from the hamburger menu ☰)
2. Under **Storage**, find your **Cloud Object Storage** instance
3. Click on the instance name

### **Step 3: Create Service Credentials with HMAC**
1. In the left sidebar, click **Service credentials**
2. Click **New credential** button
3. **Important**: In the configuration, add this JSON:
   ```json
   {"HMAC": true}
   ```
4. Give it a name (e.g., "HMAC-credentials")
5. Click **Add**

### **Step 4: View and Copy Credentials**
1. Click **View credentials** on the newly created credential
2. Look for the **`cos_hmac_keys`** section:
   ```json
   {
     "cos_hmac_keys": {
       "access_key_id": "a1b2c3d4e5f6g7h8i9j0",      // ← This is IBM_COS_ACCESS_KEY
       "secret_access_key": "A1B2C3D4E5F6G7H8I9J0..."  // ← This is IBM_COS_SECRET_KEY
     }
   }
   ```

### **Step 5: Set Environment Variables**
```bash
export IBM_COS_ACCESS_KEY="your-access-key-here"
export IBM_COS_SECRET_KEY="your-secret-key-here"
```

### **📋 Example Service Credentials Structure**

Your full service credentials will look like this:
```json
{
  "apikey": "your-api-key",
  "cos_hmac_keys": {
    "access_key_id": "a1b2c3d4e5f6g7h8i9j0",      // ← IBM_COS_ACCESS_KEY
    "secret_access_key": "A1B2C3D4E5F6G7H8I9J0..."  // ← IBM_COS_SECRET_KEY
  },
  "endpoints": "https://cos-service.bluemix.net/endpoints",
  "iam_apikey_description": "Auto-generated for key...",
  "iam_apikey_name": "Service credentials",
  "iam_role_crn": "crn:v1:bluemix:public:iam::::serviceRole:Writer",
  "iam_serviceid_crn": "crn:v1:bluemix:public:iam-identity::a/...",
  "resource_instance_id": "crn:v1:bluemix:public:cloud-object-storage:global:a/..."
}
```

### **⚠️ Important Notes**

1. **HMAC Must Be Enabled**: The `{"HMAC": true}` configuration is crucial
2. **Permissions**: Ensure your service credential has appropriate IAM roles (Writer/Manager)
3. **Security**: Keep these credentials secure - don't commit them to code

## Running the Application

### Option 1: With Real Credentials (Recommended)

Set environment variables with your HMAC credentials:

```bash
export IBM_COS_ACCESS_KEY="your_access_key_here"
export IBM_COS_SECRET_KEY="your_secret_key_here"
export IBM_COS_ENDPOINT="https://s3.us.cloud-object-storage.appdomain.cloud"  # optional
```

Then compile and run:

```bash
./gradlew :altastata-core:runIBMCloudObjectStorageDemo
```

### Option 2: With Placeholder Credentials (Demo Mode)

Run without setting environment variables to see the application structure:

```bash
./gradlew :altastata-core:runIBMCloudObjectStorageDemo
```

The application will run with placeholder credentials and demonstrate the API calls (which will fail gracefully for authentication, but show the correct structure).

### Option 3: Using IDE

1. Import the project into IntelliJ IDEA or Eclipse
2. Set environment variables in run configuration
3. Run `com.altastata.cloud.ibm.IBMCloudObjectStorageDemo`

### Option 4: Alternative Test Runner (Legacy)

You can also run it using the test framework:

```bash
./gradlew :altastata-core:test --tests "*IBMCloudObjectStorageDemo*"
```

## Expected Output

**With real credentials:**
```
=== IBM Cloud Object Storage Demo ===
Endpoint: https://s3.us.cloud-object-storage.appdomain.cloud
Demo Bucket: altastata-demo-bucket-1234
Service Instance: crn:v1:bluemix:public:cloud-object-storage:global...

📋 Client Configuration Demo
----------------------------------------
✅ S3 Client created successfully
   Endpoint: https://s3.us.cloud-object-storage.appdomain.cloud
   Region: US_EAST_1
   Path Style Access: enabled

🪣 Bucket Operations Demo
----------------------------------------
📝 Listing buckets...
Found 2 buckets:
   📁 my-bucket-1 (created: 2024-01-15T10:30:00Z)
   📁 my-bucket-2 (created: 2024-01-20T14:22:00Z)
🆕 Creating bucket: altastata-demo-bucket-1234
✅ Bucket created at: /altastata-demo-bucket-1234
🗑️  Deleting bucket: altastata-demo-bucket-1234
✅ Bucket deleted successfully

... (continues with object operations and advanced features)
```

**With placeholder credentials:**
```
=== IBM Cloud Object Storage Demo ===
⚠️  Using placeholder credentials. Set IBM_COS_ACCESS_KEY and IBM_COS_SECRET_KEY environment variables for real operations.

📋 Client Configuration Demo
----------------------------------------
✅ S3 Client created successfully

🪣 Bucket Operations Demo
----------------------------------------
⚠️  Bucket operations failed (expected with placeholder credentials): The AWS Access Key Id you provided does not exist in our records.

... (continues showing structure)
```

## Key Features Demonstrated

### 1. S3 Client Configuration
```scala
S3Client.builder()
  .endpointOverride(URI.create(endpoint))
  .credentialsProvider(StaticCredentialsProvider.create(credentials))
  .serviceConfiguration(_.pathStyleAccessEnabled(true)) // Important for IBM COS
  .region(region)
  .build()
```

### 2. Bucket Operations
- List all buckets
- Create new bucket
- Delete bucket

### 3. Object Operations
- Upload objects with metadata
- List objects in bucket
- Set object ACLs
- Delete objects

### 4. Advanced Features
- Generate presigned URLs for temporary access
- Create STS session tokens
- Validate endpoints and configurations

## Configuration Options

### Supported IBM COS Regions
- `us` or `us-south`: US South (Dallas)
- `eu` or `eu-gb`: EU Great Britain (London)
- `ap` or `ap-south`: Asia Pacific (Tokyo)
- `jp` or `jp-tok`: Japan (Tokyo)
- `au` or `au-syd`: Australia (Sydney)

### Custom Configuration
```scala
import com.altastata.cloud.ibm.IBMCloudObjectStorageClient._

val config = IBMCOSConfig(
  endpoint = "https://s3.eu.cloud-object-storage.appdomain.cloud",
  region = Region.EU_WEST_2,
  accessKey = "your_access_key",
  secretKey = "your_secret_key",
  pathStyleAccess = true
)

val client = createS3Client(config)
```

## Differences from AWS S3

1. **Custom Endpoint**: Uses IBM COS endpoints instead of AWS
2. **Path Style Access**: Must be enabled (`pathStyleAccessEnabled(true)`)
3. **Region Handling**: IBM COS doesn't use AWS regions, but SDK requires one
4. **Authentication**: Primary method is HMAC credentials (not IAM roles)

## Troubleshooting

### Common Issues

1. **Authentication Error**: 
   - Ensure HMAC credentials are correct
   - Check if credentials have proper permissions

2. **SSL/Connection Issues**:
   - Verify endpoint URL is correct
   - Ensure `pathStyleAccessEnabled(true)` is set

3. **Bucket Naming**:
   - Use DNS-compliant names (lowercase, no underscores)
   - Max 63 characters

4. **Region Errors**:
   - Any valid AWS region works (IBM COS ignores it)
   - Recommended: `Region.US_EAST_1`

### Debug Mode

Add to your `logback.xml`:
```xml
<logger name="software.amazon.awssdk" level="DEBUG"/>
<logger name="com.altastata.cloud.ibm" level="DEBUG"/>
```

## Integration with Existing Code

You can use the utility classes in your own applications:

```scala
import com.altastata.cloud.ibm.IBMCloudObjectStorageClient._

// Create client from environment variables
val clientTry = createS3ClientFromEnv()
clientTry match {
  case Success(client) =>
    // Use the client
    val buckets = client.listBuckets()
    println(s"Found ${buckets.buckets().size()} buckets")
    client.close() // Clean up resources
  case Failure(ex) =>
    println(s"Failed to create client: ${ex.getMessage}")
}
```

## References

- [Ceph S3 Java Examples](https://docs.ceph.com/en/latest/radosgw/s3/java/)
- [IBM Cloud Object Storage Documentation](https://cloud.ibm.com/docs/cloud-object-storage)
- [AWS SDK for Java v2 Developer Guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/)
- [AWS SDK v2 S3 Client Documentation](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html) 