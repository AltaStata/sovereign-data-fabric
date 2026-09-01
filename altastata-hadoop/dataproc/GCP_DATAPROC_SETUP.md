# Google Cloud Dataproc Setup Guide for AltaStata

This guide explains how to set up Google Cloud Dataproc with AltaStata integration, including Jupyter and Zeppelin notebooks, JAR loading, and example workflows.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Project Setup](#project-setup)
- [Dataproc Cluster Management](#dataproc-cluster-management)
- [Notebook Access](#notebook-access)
- [AltaStata JAR Integration](#altastata-jar-integration)
- [Example Notebooks](#example-notebooks)
- [Cost Management](#cost-management)
- [Troubleshooting](#troubleshooting)

## Overview

Google Cloud Dataproc provides managed Apache Spark and Hadoop clusters with integrated Jupyter and Zeppelin notebooks. This setup enables:

- ✅ **Scala Support**: Use `%scala` magic commands in Zeppelin
- ✅ **Python Support**: Use `%python` in both Jupyter and Zeppelin
- ✅ **SQL Support**: Use `%sql` in both environments
- ✅ **AltaStata Integration**: Load AltaStata JARs for secure cloud operations
- ✅ **GCS Integration**: Direct access to Google Cloud Storage
- ✅ **Cost Control**: Stop/start clusters to save money

## Prerequisites

### Required Projects
- **Storage Project**: `altastatastoragecheck` (AltaStata Storage)
- **Compute Project**: `altastata-dataproc` (AltaStata Dataproc)

### Required Services
- Google Cloud Dataproc API enabled
- Google Cloud Storage API enabled
- Compute Engine API enabled

### Required Credentials
- Service account: `altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com`
- Cross-project access to storage buckets

## Project Setup

### 1. Create Dataproc Project

```bash
# Create new project
gcloud projects create altastata-dataproc --name="AltaStata Dataproc"

# Link to billing account (your credits)
gcloud billing projects link altastata-dataproc --billing-account=012A33-E3A0F9-7F4612

# Set as active project
gcloud config set project altastata-dataproc
```

### 2. Enable Required APIs

```bash
# Enable APIs
gcloud services enable dataproc.googleapis.com compute.googleapis.com storage.googleapis.com
```

### 3. Create Service Account

```bash
# Create service account
gcloud iam service-accounts create altastata-dataproc-admin \
    --display-name="AltaStata Dataproc Admin" \
    --description="Service account for AltaStata Dataproc operations"

# Grant permissions
gcloud projects add-iam-policy-binding altastata-dataproc \
    --member="serviceAccount:altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com" \
    --role="roles/dataproc.admin"

gcloud projects add-iam-policy-binding altastata-dataproc \
    --member="serviceAccount:altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com" \
    --role="roles/dataproc.worker"

gcloud projects add-iam-policy-binding altastata-dataproc \
    --member="serviceAccount:altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com" \
    --role="roles/storage.admin"
```

### 4. Cross-Project Access

```bash
# Grant access to storage project
gcloud projects add-iam-policy-binding altastatastoragecheck \
    --member="serviceAccount:altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com" \
    --role="roles/storage.objectViewer"
```

## Dataproc Cluster Management

### Automated Cluster Rebuild (Recommended)

For the most efficient workflow, use the automated rebuild script that handles the complete process:

```bash
cd altastata-hadoop/dataproc
./rebuild-dataproc-cluster.sh
```

This script automatically:
1. **Builds the JAR** - Creates AltaStata Hadoop JAR with Dataproc-specific exclusions
2. **Deletes existing cluster** - Safely removes the current `altastata-full-cluster` if it exists
3. **Uploads JAR** - Uploads the latest JAR to `gs://altastata-spark-files/altastata-jars/`
4. **Creates new cluster** - Builds a fresh cluster with the updated JAR
5. **Provides information** - Shows cluster details, access URLs, and cost information

#### Rebuild Script Features

- **Automatic JAR building** with proper exclusions
- **Safe cluster deletion** with existence checking
- **Complete cluster recreation** with latest configuration
- **Cost information** and management tips
- **Error handling** and status reporting

### Manual Cluster Creation

If you prefer manual control, you can create the cluster step by step:

```bash
gcloud dataproc clusters create altastata-full-cluster \
    --region=us-central1 \
    --zone=us-central1-a \
    --master-machine-type=n1-standard-4 \
    --master-boot-disk-size=100GB \
    --num-workers=2 \
    --worker-machine-type=n1-standard-4 \
    --worker-boot-disk-size=100GB \
    --image-version=2.1-debian11 \
    --enable-component-gateway \
    --optional-components=JUPYTER,ZEPPELIN \
    --service-account=altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com \
    --initialization-actions=gs://altastata-spark-files/init-scripts/altastata-dataproc-init.sh
```

### Cluster Configuration

| Component | Specification |
|-----------|---------------|
| **Master Node** | n1-standard-4 (4 vCPUs, 15 GB RAM) |
| **Worker Nodes** | 2x n1-standard-4 (4 vCPUs, 15 GB RAM each) |
| **Storage** | 100GB per node |
| **Location** | us-central1-a |
| **Components** | Spark, Hadoop, Hive, Jupyter, Zeppelin |
| **Cost** | ~$0.57/hour (~$410/month if running 24/7) |

## Notebook Access

### Web Console Access

1. **Go to**: https://console.cloud.google.com/dataproc/clusters?project=altastata-dataproc
2. **Click**: `altastata-full-cluster`
3. **Click**: "Web interfaces" tab

### Jupyter Notebooks

- **URL**: Available in "Web interfaces" tab
- **Features**: Python, R, SQL support
- **Limitation**: No Scala support

### Zeppelin Notebooks

- **URL**: Available in "Web interfaces" tab
- **Features**: Scala, Python, SQL, R support
- **Advantage**: Full Scala support with `%scala` magic

### Cluster Status Checking

Check the current status of your Dataproc cluster:

```bash
cd altastata-hadoop/dataproc
./check-cluster-status.sh
```

This script provides:
- **Cluster existence** verification
- **Current status** (RUNNING, STOPPED, etc.)
- **Configuration details** (nodes, machine types, etc.)
- **Web interface URLs** for direct access
- **Console access** link

### Direct URLs

```bash
# Get web interface URLs
gcloud dataproc clusters describe altastata-full-cluster \
    --region=us-central1 \
    --format="value(config.gceClusterConfig.metadata.items[0].value)"
```

## AltaStata JAR Integration

### Building Dataproc-Specific JAR

Build the AltaStata Hadoop JAR with Dataproc-specific exclusions to prevent dependency conflicts:

```bash
cd altastata-hadoop/dataproc
./build-dataproc-jar.sh
```

This script builds with `-PdataprocBuild=true -PexcludeBouncyCastle=true` to exclude:
- Netty dependencies (prevents ClassCastException conflicts)
- BouncyCastle dependencies (installed separately on Dataproc)

### Initialization Action Setup

The AltaStata JARs are automatically installed on all cluster nodes using an initialization action:

1. **Script Location**: `gs://altastata-spark-files/init-scripts/altastata-dataproc-init.sh`
2. **Installation**: JARs are copied to `/usr/lib/spark/jars/` on all nodes
3. **Availability**: JARs are automatically available to all Spark applications

### JAR Storage Location

All AltaStata JARs are stored in GCS and automatically installed:
```
gs://altastata-spark-files/altastata-jars/
├── altastata-hadoop-YYYY.MM.DD-uber.jar (126.1 MiB)
├── bcpkix-jdk18on-1.85.jar (1.09 MiB)
├── bcprov-jdk18on-1.85.jar (8.28 MiB)
└── bcutil-jdk18on-1.85.jar (688.63 KiB)
```

### Using AltaStata JARs

While JARs are installed in `/usr/lib/spark/jars/`, they may need explicit configuration for full access:

#### Python (Jupyter)
```python
# JARs are automatically available for executors
from pyspark.sql import SparkSession

spark = SparkSession.builder \
    .appName("AltaStata Spark") \
    .getOrCreate()

print("✅ AltaStata JARs available for Spark operations!")
```

#### Scala (Zeppelin) - Method 1: Explicit Loading
```scala
%spark
// Explicitly add AltaStata JARs to Spark context
val jarPaths = Array(
    "/usr/lib/spark/jars/altastata-hadoop-YYYY.MM.DD-uber.jar",
    "/usr/lib/spark/jars/bcpkix-jdk18on-1.85.jar",
    "/usr/lib/spark/jars/bcprov-jdk18on-1.85.jar",
    "/usr/lib/spark/jars/bcutil-jdk18on-1.85.jar"
)

// Add JARs to Spark context
jarPaths.foreach(jar => spark.sparkContext.addJar(jar))

println("✅ AltaStata JARs added to Spark context!")

// Verify JARs are loaded
val loadedJars = spark.sparkContext.listJars()
val altaStataJars = loadedJars.filter(jar => jar.contains("altastata") || jar.contains("bc"))
println(s"📦 Loaded AltaStata JARs: ${altaStataJars.length}")
```

#### Scala (Zeppelin) - Method 2: Interpreter Configuration
For persistent access, configure Zeppelin interpreter settings:

1. **Go to Zeppelin**: Click gear icon (⚙️) → "Interpreter"
2. **Find "spark" interpreter** → Click "edit"
3. **Add property**:
   - `spark.driver.extraClassPath`: `/usr/lib/spark/jars/altastata-hadoop-YYYY.MM.DD-uber.jar:/usr/lib/spark/jars/bcpkix-jdk18on-1.85.jar:/usr/lib/spark/jars/bcprov-jdk18on-1.85.jar:/usr/lib/spark/jars/bcutil-jdk18on-1.85.jar`
4. **Click "Save"** → **Restart interpreter**

Then test:
```scala
%spark
// Test AltaStata classes
try {
    val altaStataClass = Class.forName("com.altastata.filesystem.AltaStataFileSystem")
    println(s"✅ AltaStata FileSystem class found: ${altaStataClass.getName}")
} catch {
    case e: ClassNotFoundException => println("❌ AltaStata classes not found: " + e.getMessage())
}
```

## Example Notebooks

### Python Notebook (Jupyter): `altastata_python_demo.ipynb`

```python
# Cell 1: Setup
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

# Configuration
user_properties = """
accounttype=google-cloud-storage
projectId=altastatastoragecheck
gcs.bucket.prefix=altastata-performance-test
GOOGLE_APPLICATION_CREDENTIALS=/path/to/gcp-admin-credentials.json
acccontainer-prefix=altastata-dataproc-
"""

# Create Spark session (JARs automatically available)
spark = SparkSession.builder \
    .appName("AltaStata Python Demo") \
    .getOrCreate()

# Cell 2: Data Processing
# Create sample data
data = [
    ("AltaStata", "storage", 100),
    ("Spark", "processing", 95),
    ("Encryption", "security", 98)
]

df = spark.createDataFrame(data, ["component", "category", "score"])
df.show()

# Cell 3: Analysis
result = df.groupBy("category").agg(
    F.count("*").alias("count"),
    F.avg("score").alias("avg_score")
)
result.show()
```

### Scala Notebook (Zeppelin): `altastata_scala_demo.zpln`

```scala
%spark
// Cell 1: Setup and JAR Loading
val userProperties = """
accounttype=google-cloud-storage
projectId=altastatastoragecheck
gcs.bucket.prefix=altastata-performance-test
GOOGLE_APPLICATION_CREDENTIALS=/path/to/gcp-admin-credentials.json
acccontainer-prefix=altastata-dataproc-
"""

// Explicitly add AltaStata JARs to Spark context
val jarPaths = Array(
    "/usr/lib/spark/jars/altastata-hadoop-YYYY.MM.DD-uber.jar",
    "/usr/lib/spark/jars/bcpkix-jdk18on-1.85.jar",
    "/usr/lib/spark/jars/bcprov-jdk18on-1.85.jar",
    "/usr/lib/spark/jars/bcutil-jdk18on-1.85.jar"
)

jarPaths.foreach(jar => spark.sparkContext.addJar(jar))
println("✅ AltaStata JARs loaded into Spark context!")
```

%spark
// Cell 2: Data Processing
val data = Seq(
    ("AltaStata", "storage", 100),
    ("Spark", "processing", 95),
    ("Encryption", "security", 98)
)

val df = spark.createDataFrame(data).toDF("component", "category", "score")
df.show()

%spark
// Cell 3: Analysis
import org.apache.spark.sql.functions._
val result = df.groupBy("category")
    .agg(count("*").alias("count"), avg("score").alias("avg_score"))
result.show()
```

## Cost Management

### Stop Cluster (Save Money)

```bash
gcloud dataproc clusters stop altastata-full-cluster --region=us-central1
```

### Start Cluster (When Needed)

```bash
gcloud dataproc clusters start altastata-full-cluster --region=us-central1
```

### Cost Comparison

| Usage Pattern | Monthly Cost | Savings |
|---------------|--------------|---------|
| **24/7 Running** | ~$410 | - |
| **Weekdays Only** | ~$100 | 75% |
| **4 hours/day** | ~$50 | 88% |
| **Weekends Off** | ~$300 | 27% |

### Management Script

```bash
#!/bin/bash
# save as: manage-dataproc.sh

case "$1" in
  "start")
    echo "Starting Dataproc cluster..."
    gcloud dataproc clusters start altastata-full-cluster --region=us-central1
    ;;
  "stop")
    echo "Stopping Dataproc cluster..."
    gcloud dataproc clusters stop altastata-full-cluster --region=us-central1
    ;;
  "status")
    echo "Cluster status:"
    gcloud dataproc clusters list --region=us-central1
    ;;
  *)
    echo "Usage: $0 {start|stop|status}"
    exit 1
    ;;
esac
```

## Troubleshooting

### Common Issues

**1. JAR Loading Issues**
```bash
# Check JAR availability
gsutil ls gs://altastata-spark-files/altastata-jars/

# Verify JARs are installed on cluster
gcloud compute ssh altastata-full-cluster-m --zone=us-central1-a --command="ls -la /usr/lib/spark/jars/ | grep -E '(altastata|bc)'"

# Verify permissions
gcloud projects get-iam-policy altastata-dataproc
```

**2. Zeppelin Class Loading Issues**
```scala
%spark
// Debug: Check if JARs are loaded in Spark context
val loadedJars = spark.sparkContext.listJars()
val altaStataJars = loadedJars.filter(jar => jar.contains("altastata") || jar.contains("bc"))
println(s"📦 Loaded AltaStata JARs: ${altaStataJars.length}")
altaStataJars.foreach(jar => println(s"  - $jar"))

// Debug: Test class loading
try {
    val altaStataClass = Class.forName("com.altastata.filesystem.AltaStataFileSystem")
    println("✅ AltaStata classes accessible!")
} catch {
    case e: ClassNotFoundException => 
        println("❌ AltaStata classes not found in driver classpath")
        println("💡 Try configuring Zeppelin interpreter settings")
}
```

**3. Spark Session Issues**
- **Problem**: `spark.stop()` hangs in Zeppelin
- **Solution**: Don't stop the Spark session in Zeppelin - it's managed by the environment
- **Alternative**: Configure interpreter settings for persistent JAR access

**2. Cross-Project Access Issues**
```bash
# Check storage project access
gcloud projects get-iam-policy altastatastoragecheck
```

**3. Cluster Creation Issues**
```bash
# Check API enablement
gcloud services list --enabled --filter="name:dataproc.googleapis.com"

# Check service account permissions
gcloud iam service-accounts get-iam-policy altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com
```

**4. Notebook Access Issues**
```bash
# Check cluster status
gcloud dataproc clusters describe altastata-full-cluster --region=us-central1

# Get web interface URLs
gcloud dataproc clusters describe altastata-full-cluster --region=us-central1 --format="value(config.gceClusterConfig.metadata.items[0].value)"
```

### Verification Commands

```bash
# Check cluster status
gcloud dataproc clusters list --region=us-central1

# Check components
gcloud dataproc clusters describe altastata-full-cluster --region=us-central1 --format="value(config.softwareConfig.optionalComponents)"

# Check billing
gcloud billing projects describe altastata-dataproc

# Check GCS access
gsutil ls gs://altastata-spark-files/altastata-jars/
```

## Best Practices

1. **Use Zeppelin for Scala**: Full Scala support with `%scala` magic
2. **Use Jupyter for Python**: Better Python integration
3. **Stop when not using**: Save costs by stopping the cluster
4. **Backup notebooks**: Save important work to GCS
5. **Monitor costs**: Use Cloud Console billing dashboard
6. **Use consistent JARs**: Keep JAR versions synchronized

## Next Steps

1. ✅ **Cluster created** - `altastata-full-cluster`
2. ✅ **Jupyter and Zeppelin** - Both available
3. ✅ **AltaStata JARs** - Loaded and ready
4. 🔄 **Create notebooks** - Python and Scala examples
5. 🔄 **Test AltaStata operations** - File system and data processing
6. 🔄 **Performance testing** - Run AltaStata benchmarks

---

**Note**: This setup uses your credits account (`012A33-E3A0F9-7F4612`) and provides both Python and Scala environments for AltaStata development and testing.
