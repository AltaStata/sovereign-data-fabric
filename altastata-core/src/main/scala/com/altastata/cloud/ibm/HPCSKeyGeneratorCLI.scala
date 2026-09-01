/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.cloud.ibm

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.{File, FileWriter}
import java.math.BigInteger
import java.nio.file.{Files, Paths}
import java.security._
import java.security.cert.X509Certificate
import java.util.{Base64, Date}

/**
 * HPCS Key Generator CLI Tool
 * 
 * Command-line tool for generating RSA keys in IBM HPCS.
 * Designed to run on LinuxONE (s390x) where the PKCS#11 library is available.
 * 
 * Compatible with full AltaStata classpath including BouncyCastle.
 * 
 * Usage: See printUsage() method for command line arguments.
 * 
 * Arguments:
 *   - username: The username for the key label (e.g., "alice222")
 *   - iam-api-key: IBM Cloud IAM API key (used as PKCS#11 PIN)
 *   - output-dir: Optional output directory (default: current directory)
 * 
 * The tool will:
 *   1. Generate RSA-4096 key pair in HPCS (private key never leaves HSM)
 *   2. Create self-signed certificate
 *   3. Import certificate to HPCS (required for Java KeyStore access)
 *   4. Output public key PEM (to send to AltaStata Admin)
 *   5. Save marker file, public key, and certificate to output directory
 * 
 * Required JVM flags (PKCS#11):
 *   --add-opens jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED
 */
object HPCSKeyGeneratorCLI {
  
  private val SEPARATOR = "=" * 70
  private val DASHES = "-" * 50
  
  // PKCS#11 configuration - auto-detect based on platform
  private def getPKCS11Library: Option[String] = {
    val osArch = System.getProperty("os.arch").toLowerCase
    val paths = if (osArch.contains("s390")) {
      Seq("/opt/hpcs/pkcs11-grep11-s390x.so")
    } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
      Seq("/opt/hpcs/pkcs11-grep11-arm64.so", "/opt/hpcs/pkcs11-grep11-arm64.dylib")
    } else {
      Seq("/opt/hpcs/pkcs11-grep11-amd64.so", "/opt/hpcs/pkcs11-grep11-amd64.dylib")
    }
    paths.find(p => new File(p).exists())
  }
  
  /**
   * Main execution entry point for the HPCS Key Generator CLI. Parses command-line
   * arguments, verifies paths, initializes the SunPKCS11 provider, generates an HSM-backed
   * RSA key pair, signs a self-signed X.509 certificate, and writes the keys/certs to disk.
   *
   * @param args command-line arguments: username, IAM API key, and optional output directory path
   */
  def main(args: Array[String]): Unit = {
    println(SEPARATOR)
    println("AltaStata HPCS Key Generator CLI")
    println("Generate RSA keys protected by IBM Hyper Protect Crypto Services")
    println(SEPARATOR)
    println()
    
    // Parse arguments
    if (args.length < 2) {
      printUsage()
      System.exit(1)
    }
    
    val username = args(0)
    val iamApiKey = args(1)
    val outputDir = if (args.length > 2) args(2) else "."
    
    val keyLabel = username
    
    println(s"Username: $username")
    println(s"Key Label: $keyLabel")
    println(s"Output Directory: $outputDir")
    println()
    
    try {
      // Ensure output directory exists
      val outputPath = Paths.get(outputDir)
      if (!Files.exists(outputPath)) {
        Files.createDirectories(outputPath)
        println(s"Created output directory: $outputDir")
      }
      
      // Get PKCS#11 library
      val pkcs11Library = getPKCS11Library.getOrElse {
        println()
        println("ERROR: PKCS#11 library not found.")
        println()
        println("Please ensure:")
        println("  1. IBM PKCS#11 library is installed at /opt/hpcs/pkcs11-grep11-*.so")
        println("  2. grep11client.yaml is configured at /etc/ep11client/grep11client.yaml")
        println("  3. You are running on LinuxONE (s390x)")
        println()
        System.exit(1)
        ""
      }
      
      // Initialize PKCS#11 directly (standalone - no Account dependency)
      println(DASHES)
      println("Step 1: Initializing PKCS#11 Provider")
      println(DASHES)
      println(s"  Library: $pkcs11Library")
      
      // PKCS#11 configuration with CKA attributes
      val configContent =
        s"""name = HPCS-CLI
           |library = $pkcs11Library
           |slotListIndex = 0
           |
           |attributes(generate, CKO_PRIVATE_KEY, CKK_RSA) = {
           |  CKA_SIGN = true
           |  CKA_DECRYPT = true
           |  CKA_SENSITIVE = true
           |  CKA_EXTRACTABLE = false
           |}
           |
           |attributes(generate, CKO_PUBLIC_KEY, CKK_RSA) = {
           |  CKA_VERIFY = true
           |  CKA_ENCRYPT = true
           |}
           |""".stripMargin
      
      val configFile = File.createTempFile("pkcs11-cli-", ".cfg")
      configFile.deleteOnExit()
      val writer = new FileWriter(configFile)
      writer.write(configContent)
      writer.close()
      
      // Initialize provider - CRITICAL: use classOf[Provider], not sunPKCS11.getClass
      val sunPKCS11 = Security.getProvider("SunPKCS11")
      val configureMethod = classOf[Provider].getMethod("configure", classOf[String])
      val provider = configureMethod.invoke(sunPKCS11, configFile.getAbsolutePath).asInstanceOf[Provider]
      Security.addProvider(provider)
      
      val keyStore = KeyStore.getInstance("PKCS11", provider)
      keyStore.load(null, iamApiKey.toCharArray)
      
      println(s"  Provider: ${provider.getName}")
      println("  ✓ PKCS#11 initialized")
      println()
      
      // Generate key pair in HPCS
      println(DASHES)
      println("Step 2: Generating RSA Key Pair in HPCS")
      println(DASHES)
      
      val keyPairGenerator = KeyPairGenerator.getInstance("RSA", provider)
      keyPairGenerator.initialize(4096)
      val keyPair = keyPairGenerator.generateKeyPair()
      
      val publicKeyPEM = publicKeyToPEM(keyPair.getPublic)
      
      println("  ✓ RSA-4096 key pair generated in HPCS")
      println("  ✓ Private key is protected by HSM (never extractable)")
      println()
      
      // Create and import certificate
      println(DASHES)
      println("Step 3: Creating and Importing Certificate")
      println(DASHES)
      
      val certificatePEM = createSelfSignedCertificateAndImport(username, keyPair, keyStore, keyLabel, iamApiKey)
      
      println("  ✓ Self-signed certificate created")
      println("  ✓ Certificate imported to HPCS")
      println()
      
      // Output results
      outputResults(username, keyLabel, publicKeyPEM, certificatePEM, outputDir)
      
      println()
      println(SEPARATOR)
      println("SUCCESS! HPCS key generation complete!")
      println(SEPARATOR)
      println()
      println("Next steps:")
      println("  1. Send the PUBLIC KEY to AltaStata Admin for user creation (Admin will set key-protection=HPCS and metadata-encryption=RSA)")
      println("  2. GREP11 config (endpoint, instance, API key) is read from grep11client.yaml via GREP11_YAML env or hpcs-yaml-path")
      println("     Private key blob is in your account dir. No need to add hpcs-user-pin, hpcs-key-label, or hpcs-token-space to properties.")
      println()
      
    } catch {
      case e: Exception =>
        println()
        println("ERROR: " + e.getMessage)
        e.printStackTrace()
        System.exit(1)
    }
  }
  
  private def outputResults(
      username: String, 
      keyLabel: String, 
      publicKeyPEM: String, 
      certificatePEM: String,
      outputDir: String): Unit = {
    
    println(DASHES)
    println("Step 4: Saving Output Files")
    println(DASHES)
    
    // Save public key
    val publicKeyPath = Paths.get(outputDir, "public.key")
    Files.write(publicKeyPath, publicKeyPEM.getBytes)
    println(s"  Public key saved: $publicKeyPath")
    
    // Save certificate
    val certPath = Paths.get(outputDir, "certificate.pem")
    Files.write(certPath, certificatePEM.getBytes)
    println(s"  Certificate saved: $certPath")
    
    // Save marker file
    val markerPath = Paths.get(outputDir, "hpcs.marker")
    val markerContent = 
      s"""key-label=$keyLabel
         |created=${System.currentTimeMillis}
         |hpcs-protected=true
         |username=$username
         |""".stripMargin
    Files.write(markerPath, markerContent.getBytes)
    println(s"  Marker file saved: $markerPath")
    
    // Print public key
    println()
    println(DASHES)
    println("PUBLIC KEY (send to AltaStata Admin):")
    println(DASHES)
    println(publicKeyPEM)
    println(DASHES)
  }
  
  /**
   * Create self-signed certificate and import to HPCS keystore (BouncyCastle; Java 17–safe).
   */
  private def createSelfSignedCertificateAndImport(
      username: String, 
      keyPair: KeyPair, 
      keyStore: KeyStore, 
      keyLabel: String,
      pin: String): String = {
    
    val publicKey = keyPair.getPublic
    val privateKey = keyPair.getPrivate
    
    // Certificate validity - 10 years
    val notBefore = new Date()
    val notAfter = new Date(notBefore.getTime + 10L * 365 * 24 * 60 * 60 * 1000)
    
    val serialNumber = BigInteger.valueOf(System.currentTimeMillis)
    val dn = new X500Name(s"CN=$username, O=AltaStata, C=US")
    val signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
    val certHolder =
      new JcaX509v3CertificateBuilder(dn, serialNumber, notBefore, notAfter, dn, publicKey).build(
        signer)
    val cert: X509Certificate = new JcaX509CertificateConverter().getCertificate(certHolder)
    
    // Import to keystore
    val certChain = Array[java.security.cert.Certificate](cert)
    keyStore.setKeyEntry(keyLabel, privateKey, pin.toCharArray, certChain)
    
    // Convert to PEM
    val base64Cert = Base64.getEncoder.encodeToString(cert.getEncoded)
    val sb = new StringBuilder
    sb.append("-----BEGIN CERTIFICATE-----\n")
    base64Cert.grouped(64).foreach { line =>
      sb.append(line)
      sb.append("\n")
    }
    sb.append("-----END CERTIFICATE-----")
    sb.toString()
  }
  
  private def publicKeyToPEM(publicKey: PublicKey): String = {
    val encoded = Base64.getEncoder.encodeToString(publicKey.getEncoded)
    val sb = new StringBuilder
    sb.append("-----BEGIN PUBLIC KEY-----\n")
    encoded.grouped(64).foreach { line =>
      sb.append(line)
      sb.append("\n")
    }
    sb.append("-----END PUBLIC KEY-----")
    sb.toString()
  }
  
  private def printUsage(): Unit = {
    println("Usage: HPCSKeyGeneratorCLI <username> <iam-api-key> [output-dir]")
    println()
    println("Arguments:")
    println("  username     - Username for the key label (e.g., 'alice222')")
    println("  iam-api-key  - IBM Cloud IAM API key (used as PKCS#11 PIN)")
    println("  output-dir   - Optional output directory (default: current directory)")
    println()
    println("Example:")
    println("  java --add-opens jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED \\")
    println("       -cp '/path/to/lib/*' \\")
    println("       com.altastata.cloud.ibm.HPCSKeyGeneratorCLI \\")
    println("       alice222 <IAM_API_KEY> /tmp/alice222-keys")
    println()
    println("The tool generates RSA-4096 keys in IBM HPCS:")
    println("  - Private key stays in HSM (never extractable)")
    println("  - Public key is exported for credential encryption")
    println("  - Self-signed certificate is created and imported to HPCS")
    println()
    println("Prerequisites:")
    println("  - IBM PKCS#11 library at /opt/hpcs/pkcs11-grep11-*.so")
    println("  - grep11client.yaml at /etc/ep11client/grep11client.yaml")
    println("  - Running on LinuxONE (s390x) with HPCS access")
    println()
    println("Note: Compatible with BouncyCastle in classpath.")
    println()
  }
}
