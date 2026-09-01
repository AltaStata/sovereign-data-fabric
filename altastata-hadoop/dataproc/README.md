# AltaStata Dataproc Integration

This directory contains all files related to Google Cloud Dataproc integration with AltaStata.

## Contents

### 📋 Documentation
- **`GCP_DATAPROC_SETUP.md`** - Comprehensive guide for setting up Dataproc with AltaStata, including Jupyter and Zeppelin notebooks, JAR loading, and example workflows

### 🔧 Configuration & Scripts
- **`altastata-dataproc-init.sh`** - Initialization action script that automatically installs AltaStata JARs on all Dataproc cluster nodes
- **`build-dataproc-jar.sh`** - Script to build AltaStata Hadoop JAR with Dataproc-specific exclusions (Netty, BouncyCastle)
- **`rebuild-dataproc-cluster.sh`** - Complete script to delete existing cluster, upload JAR, and create new cluster
- **`check-cluster-status.sh`** - Script to check current cluster status and details

## Quick Start

### 1. Build Dataproc JAR
Build the AltaStata Hadoop JAR with Dataproc-specific exclusions:
```bash
./build-dataproc-jar.sh
```

### 2. Rebuild Dataproc Cluster (Recommended)
Use the automated rebuild script for the complete workflow:
```bash
./rebuild-dataproc-cluster.sh
```

This script handles the entire process:
- ✅ **Builds JAR** with Dataproc-specific exclusions
- ✅ **Deletes existing cluster** safely
- ✅ **Uploads JAR** to GCS
- ✅ **Creates new cluster** with latest configuration
- ✅ **Provides status** and access information

### 3. Check Cluster Status
Check the current status of your Dataproc cluster:
```bash
./check-cluster-status.sh
```

### 4. Read the Setup Guide
For detailed information, read the comprehensive setup guide:
```bash
cat GCP_DATAPROC_SETUP.md
```

### 5. Manual Cluster Creation (Alternative)
If you prefer manual control:
```bash
# Upload initialization script to GCS
gsutil cp altastata-dataproc-init.sh gs://altastata-spark-files/init-scripts/

# Create cluster with initialization action
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

### 6. Access Notebooks
- **Jupyter**: Available in Dataproc web interfaces
- **Zeppelin**: Available in Dataproc web interfaces

## Features

✅ **Automatic JAR Installation** - AltaStata JARs automatically installed on all nodes
✅ **Jupyter & Zeppelin Support** - Both notebook environments available
✅ **Scala & Python Support** - Full language support for AltaStata operations
✅ **Cost Management** - Stop/start clusters to save money
✅ **GCS Integration** - Direct access to Google Cloud Storage

## Architecture

The Dataproc integration uses:
- **Initialization Actions** - Automatically install AltaStata JARs in `/usr/lib/spark/jars/`
- **S3-Compatible API** - AltaStata works with GCS using S3 compatibility
- **Cross-Project Access** - Dataproc project accesses storage project buckets
- **Service Accounts** - Secure authentication between projects

## Related Files

- **AltaStata JARs**: Stored in `gs://altastata-spark-files/altastata-jars/`
- **GCS Buckets**: `gs://altastata-spark-files/` for JARs and scripts
- **Service Account**: `altastata-dataproc-admin@altastata-dataproc.iam.gserviceaccount.com`

## Troubleshooting

See `GCP_DATAPROC_SETUP.md` for detailed troubleshooting steps, including:
- JAR loading issues
- Cross-project access problems
- Cluster creation errors
- Notebook access issues

---

**Note**: This setup uses your Google Cloud credits account and provides both Python and Scala environments for AltaStata development and testing.
