# AltaStata S3 Gateway Simplified Architecture

This diagram shows a simplified version of the AltaStata S3 Gateway architecture, with all gateway components at the same level without nested subgraphs.

```mermaid
graph TB
    %% Client Layer
    subgraph ClientApps["Client Applications"]
        MinioUI["🖥️ MinIO UI"]
        AWSCLI["⚡ AWS CLI"]
        AWSSDK["📦 AWS SDK"]
        Boto3["🐍 boto3"]
        Snowflake["❄️ Snowflake"]
        S3Browser["🌐 S3 Browser"]
    end

    %% Gateway Layer
    Gateway["🌐 AltaStata S3 Gateway"]

    %% Cloud Provider Layer
    subgraph PublicClouds["Public Clouds"]
        AWS["☁️ Amazon S3<br/>AWS"]
        Azure["🔵 Azure Blob<br/>Microsoft"]
        GCP["🟡 Cloud Storage<br/>Google"]
        IBM["🔷 Cloud Object<br/>IBM"]
    end
    
    subgraph PrivateClouds["Private/On-Premise"]
        MinioServer["🗄️ MinIO Server<br/>Self-hosted"]
        POSIX["📁 POSIX Filesystem<br/>Local Storage"]
        Ceph["🐙 Ceph Storage<br/>Distributed"]
    end

    %% Connections from clients to gateway (as a whole)
    MinioUI --> Gateway
    AWSCLI --> Gateway
    AWSSDK --> Gateway
    Boto3 --> Gateway
    Snowflake --> Gateway
    S3Browser --> Gateway

    %% Connections from gateway to cloud providers
    Gateway --> AWS
    Gateway --> Azure
    Gateway --> GCP
    Gateway --> IBM
    Gateway --> MinioServer
    Gateway --> POSIX
    Gateway --> Ceph

    %% Styling - normal borders for items, bold for panels
    classDef clientStyle fill:#e1f5fe,stroke:#01579b,stroke-width:2px,font-size:16px,font-weight:bold
    classDef gatewayStyle fill:#fff3e0,stroke:#e65100,stroke-width:6px,font-size:24px,font-weight:bold,color:#000000
    classDef cloudStyle fill:#e8f5e8,stroke:#1b5e20,stroke-width:2px,font-size:16px,font-weight:bold
    
    %% Bold borders for panels/subgraphs
    classDef panelStyle stroke-width:4px,font-size:18px,font-weight:bold

    class MinioUI,AWSCLI,AWSSDK,Boto3,Snowflake,S3Browser clientStyle
    class Gateway gatewayStyle
    class AWS,Azure,GCP,IBM,MinioServer,POSIX,Ceph cloudStyle
    class ClientApps,PublicClouds,PrivateClouds panelStyle
```

## Architecture Overview

### Client Applications
The AltaStata S3 Gateway supports any S3-compatible client:
- **MinIO UI** - Web-based interface for MinIO
- **AWS CLI** - Command-line interface for AWS services
- **AWS SDK** - Software development kits for various languages
- **boto3** - Python SDK for AWS services
- **Snowflake** - Data warehouse platform
- **S3 Browser** - GUI client for S3 storage

### AltaStata S3 Gateway
The gateway acts as a transparent proxy that provides S3-compatible access to multiple cloud storage providers while adding security, optimization, and management capabilities.

### Cloud Storage Providers

#### Public Clouds
- **Amazon S3** (AWS)
- **Azure Blob Storage** (Microsoft)
- **Google Cloud Storage** (GCP)
- **IBM Cloud Object Storage**

#### Private/On-Premise
- **MinIO Server** (Self-hosted)
- **POSIX Filesystem** (Local storage)
- **Ceph Storage** (Distributed storage)

## Key Benefits

1. **Universal Compatibility** - Any S3-compatible client can connect
2. **Multi-Cloud Support** - Single interface to multiple cloud providers
3. **Enhanced Security** - Encryption, verification, and authentication
4. **Data Optimization** - Compression and integrity checks
5. **Seamless Integration** - No client-side changes required
