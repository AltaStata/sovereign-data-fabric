# Azure CLI Setup Guide for AltaStata Application

This guide explains how to install, configure, and use the Azure CLI with the AltaStata Application for secure cloud operations.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Azure Storage Account Setup](#azure-storage-account-setup)
- [Environment Variables](#environment-variables)
- [AltaStata Integration](#altastata-integration)
- [Common Commands](#common-commands)
- [Troubleshooting](#troubleshooting)

## Overview

The Azure CLI provides command-line access to Azure services and integrates with the AltaStata Application for secure cloud storage operations. This setup enables:

- ✅ **Azure Storage Management**: Create and manage storage accounts and containers
- ✅ **Secure Access**: Configure authentication and access policies
- ✅ **AltaStata Integration**: Connect AltaStata Application to Azure storage
- ✅ **Container Operations**: Manage blob containers for data storage
- ✅ **Queue Management**: Handle Azure Service Bus queues for messaging

## Prerequisites

- Azure subscription with appropriate permissions
- Administrative access to create storage accounts
- Network access to Azure services
- Java 17+ (for AltaStata Application)

## Installation

### 1. Install Azure CLI

#### macOS (using Homebrew)
```bash
# Install Azure CLI
brew install azure-cli

# Verify installation
az --version
```

#### Windows (using PowerShell)
```powershell
# Download and install from Microsoft
Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi
Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'
```

#### Linux (Ubuntu/Debian)
```bash
# Add Microsoft repository
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Verify installation
az --version
```

### 2. Verify Installation

```bash
# Check Azure CLI version
az --version

# Expected output should show:
# azure-cli                         2.50.0
# core                              2.50.0
# telemetry                          1.0.8
```

## Configuration

### 1. Login to Azure

```bash
# Interactive login (opens browser)
az login

# Login with service principal (for automation)
az login --service-principal --username <app-id> --password <password> --tenant <tenant-id>
```

### 2. Set Default Subscription

```bash
# List available subscriptions
az account list --output table

# Set default subscription
az account set --subscription "Your Subscription Name"

# Verify current subscription
az account show
```

### 3. Configure Default Resource Group

```bash
# Create resource group for AltaStata
az group create --name "altastata-rg" --location "East US"

# Set as default
az configure --defaults group=altastata-rg location="East US"
```

## Azure Storage Account Setup

### 1. Create Storage Account

**Important:** create with Hierarchical Namespace **disabled**. HNS (ADLS Gen2) adds
`hdi_isfolder` directory markers that break AltaStata catalog listing/delete semantics.
HNS cannot be turned off after the account exists — choose correctly at create time.

```bash
# Create storage account (flat Blob Storage — NOT Data Lake / HNS)
az storage account create \
  --name "altastatastorage" \
  --resource-group "altastata-rg" \
  --location "East US" \
  --sku "Standard_LRS" \
  --kind "StorageV2" \
  --access-tier "Hot" \
  --enable-hierarchical-namespace false

# Get storage account key
az storage account keys list \
  --account-name "altastatastorage" \
  --resource-group "altastata-rg" \
  --output table
```

### 2. Create Containers for AltaStata

```bash
# Create containers for AltaStata organization
az storage container create \
  --name "altastata-orgname-users-all" \
  --account-name "altastatastorage" \
  --auth-mode login

az storage container create \
  --name "altastata-orgname-chunks-user1" \
  --account-name "altastatastorage" \
  --auth-mode login

az storage container create \
  --name "altastata-orgname-dataattributes-user1" \
  --account-name "altastatastorage" \
  --auth-mode login

az storage container create \
  --name "altastata-orgname-catalog-user1" \
  --account-name "altastatastorage" \
  --auth-mode login

az storage container create \
  --name "altastata-orgname-changes-user1" \
  --account-name "altastatastorage" \
  --auth-mode login
```

### 3. Create Storage Queue

```bash
# Create queue for messaging
az storage queue create \
  --name "altastata-orgname-user1sqs" \
  --account-name "altastatastorage" \
  --auth-mode login
```

## Environment Variables

### 1. Set Azure Environment Variables

```bash
# Set Azure account URL
export AZURE_ACCOUNT_URL="https://altastatastorage.blob.core.windows.net"

# Set Azure storage connection string
export AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=https;AccountName=altastatastorage;AccountKey=YOUR_ACCOUNT_KEY;EndpointSuffix=core.windows.net"

# Set Azure subscription ID
export AZURE_SUBSCRIPTION_ID="your-subscription-id"

# Set Azure resource group
export AZURE_RESOURCE_GROUP="altastata-rg"
```

### 2. Create .env File (for local development)

```bash
# Create .env file
cat > .env << EOF
AZURE_ACCOUNT_URL=https://altastatastorage.blob.core.windows.net
AZURE_STORAGE_CONNECTION_STRING=DefaultEndpointsProtocol=https;AccountName=altastatastorage;AccountKey=YOUR_ACCOUNT_KEY;EndpointSuffix=core.windows.net
AZURE_SUBSCRIPTION_ID=your-subscription-id
AZURE_RESOURCE_GROUP=altastata-rg
EOF
```

## AltaStata Integration

### 1. Configure AltaStata Admin

The Admin Tool includes Azure support. To configure:

1. **Launch the AltaStata Admin Tool** (see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md)):

2. **Configure Azure in Admin UI**:
   - Click the Azure button in the cloud configuration dialog
   - Enter your Azure account details:
     - `azureAccount`: Your storage account name
     - `adminStorageConnectionString`: Your full connection string

### 2. Azure Configuration Properties

The Azure configuration requires these properties:

```properties
# Azure Storage Account Configuration
azureAccount=altastatastorage
adminStorageConnectionString=DefaultEndpointsProtocol=https;AccountName=altastatastorage;AccountKey=YOUR_ACCOUNT_KEY;EndpointSuffix=core.windows.net
```

## Common Commands

### 1. Storage Account Management

```bash
# List storage accounts
az storage account list --output table

# Get storage account details
az storage account show --name "altastatastorage" --resource-group "altastata-rg"

# Get storage account keys
az storage account keys list --account-name "altastatastorage" --resource-group "altastata-rg"
```

### 2. Container Operations

```bash
# List containers
az storage container list --account-name "altastatastorage" --auth-mode login

# Create container
az storage container create --name "new-container" --account-name "altastatastorage" --auth-mode login

# Delete container
az storage container delete --name "old-container" --account-name "altastatastorage" --auth-mode login
```

### 3. Blob Operations

```bash
# List blobs in container
az storage blob list --container-name "altastata-orgname-users-all" --account-name "altastatastorage" --auth-mode login

# Upload file
az storage blob upload --file "local-file.txt" --container-name "altastata-orgname-users-all" --name "remote-file.txt" --account-name "altastatastorage" --auth-mode login

# Download file
az storage blob download --container-name "altastata-orgname-users-all" --name "remote-file.txt" --file "downloaded-file.txt" --account-name "altastatastorage" --auth-mode login
```

### 4. Queue Operations

```bash
# List queues
az storage queue list --account-name "altastatastorage" --auth-mode login

# Create queue
az storage queue create --name "new-queue" --account-name "altastatastorage" --auth-mode login

# Send message to queue
az storage message put --queue-name "altastata-orgname-user1sqs" --content "Hello World" --account-name "altastatastorage" --auth-mode login
```

### 5. Generate SAS Tokens

```bash
# Generate SAS token for container (read/write/list/delete)
az storage container generate-sas \
  --name "altastata-orgname-users-all" \
  --account-name "altastatastorage" \
  --permissions "rwdl" \
  --expiry "2025-12-31T23:59:59Z" \
  --auth-mode login

# Generate SAS token for specific blob
az storage blob generate-sas \
  --container-name "altastata-orgname-users-all" \
  --name "specific-blob.txt" \
  --account-name "altastatastorage" \
  --permissions "r" \
  --expiry "2025-12-31T23:59:59Z" \
  --auth-mode login
```

## Troubleshooting

### 1. Authentication Issues

```bash
# Check current login status
az account show

# Re-login if needed
az login

# Check available subscriptions
az account list --output table
```

### 2. Storage Account Access Issues

```bash
# Verify storage account exists
az storage account show --name "altastatastorage" --resource-group "altastata-rg"

# Check storage account keys
az storage account keys list --account-name "altastatastorage" --resource-group "altastata-rg"

# Test container access
az storage container list --account-name "altastatastorage" --auth-mode login
```

### 3. Permission Issues

```bash
# Check current user permissions
az role assignment list --assignee $(az account show --query user.name --output tsv) --output table

# Assign Storage Blob Data Contributor role if needed
az role assignment create \
  --assignee $(az account show --query user.name --output tsv) \
  --role "Storage Blob Data Contributor" \
  --scope "/subscriptions/$(az account show --query id --output tsv)/resourceGroups/altastata-rg/providers/Microsoft.Storage/storageAccounts/altastatastorage"
```

### 4. Network Connectivity Issues

```bash
# Test Azure connectivity
az storage account show --name "altastatastorage" --resource-group "altastata-rg"

# Check firewall rules (if enabled)
az storage account show --name "altastatastorage" --resource-group "altastata-rg" --query "networkRuleSet"
```

## Security Best Practices

1. **Use Managed Identity** when possible instead of storage account keys
2. **Rotate storage account keys** regularly
3. **Use SAS tokens** with appropriate expiration times
4. **Enable soft delete** for blob containers
5. **Enable versioning** for important data
6. **Use private endpoints** for production environments
7. **Enable logging and monitoring** for audit trails

## Next Steps

1. **Test the configuration** by running the AltaStata Application
2. **Create user accounts** through the AltaStata Admin UI
3. **Verify data operations** work correctly with Azure storage
4. **Set up monitoring** and alerting for your Azure resources
5. **Configure backup policies** for your data

For more information about Azure CLI, visit: https://docs.microsoft.com/en-us/cli/azure/
