# AltaStata S3 Gateway Architecture

This diagram shows the overall architecture of the AltaStata S3 Gateway, including client applications, core features, gateway services, and supported cloud storage providers.

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
    subgraph Gateway["AltaStata S3 Gateway"]
        subgraph CoreFeatures["Core Features"]
            Encrypt["🔒 Data Encryption<br/>RSA/AES"]
            Verify["✅ Data Verification<br/>Integrity Checks"]
            Compress["📦 Compression<br/>Data Optimization"]
        end
        
        subgraph GatewayServices["Gateway Services"]
            S3API["🌐 S3-Compatible API"]
            Auth["🔐 Authentication<br/>AWS SigV4"]
            UserMgmt["👤 User Management"]
            CredMgmt["🗝️ Credential Management"]
            Multipart["📤 Multipart Upload"]
        end
    end

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
    classDef featureStyle fill:#fff3e0,stroke:#e65100,stroke-width:2px,font-size:16px,font-weight:bold
    classDef serviceStyle fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,font-size:16px,font-weight:bold
    classDef cloudStyle fill:#e8f5e8,stroke:#1b5e20,stroke-width:2px,font-size:16px,font-weight:bold
    
    %% Bold borders for panels/subgraphs
    classDef panelStyle stroke-width:4px,font-size:18px,font-weight:bold

    class MinioUI,AWSCLI,AWSSDK,Boto3,Snowflake,S3Browser clientStyle
    class Encrypt,Verify,Compress featureStyle
    class S3API,Auth,UserMgmt,CredMgmt,Multipart serviceStyle
    class AWS,Azure,GCP,IBM,MinioServer,POSIX,Ceph cloudStyle
    class ClientApps,Gateway,CoreFeatures,GatewayServices,PublicClouds,PrivateClouds panelStyle
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

#### Core Features
- **🔒 Data Encryption** - RSA/AES encryption for data security
- **✅ Data Verification** - Integrity checks to ensure data consistency
- **📦 Compression** - Data optimization for efficient storage

#### Gateway Services
- **🌐 S3-Compatible API** - Full S3 protocol compatibility
- **🔐 Authentication** - AWS SigV4 signature validation
- **👤 User Management** - Multi-tenant user support
- **🗝️ Credential Management** - Secure access key management
- **📤 Multipart Upload** - Support for large file uploads

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
