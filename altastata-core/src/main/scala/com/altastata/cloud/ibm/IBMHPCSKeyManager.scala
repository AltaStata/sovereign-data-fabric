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

import com.altastata.utils.{Account, Constants, FileSecurity}
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory

import java.io.{ByteArrayInputStream, File, FileWriter}
import java.math.BigInteger
import java.security._
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.{Base64, Date}
import javax.crypto.Cipher

/**
 * IBM HPCS Key Manager for AltaStata
 * 
 * Provides HSM-protected RSA key operations via IBM Hyper Protect Crypto Services (HPCS).
 * Supports two modes: GREP11 (gRPC, preferred) and PKCS#11 (.so library, legacy).
 * 
 * Key features:
 * - RSA key pair generation inside HPCS (private key never leaves HSM)
 * - Unwrap (decrypt) operations using HSM-protected private key
 * - Sign operations using HSM-protected private key
 * - Public key returned for local encryption/verification
 * 
 * == GREP11 (gRPC) — preferred, no .so library required ==
 * 
 * Prerequisites:
 * 1. grep11client.yaml configured with HPCS endpoint, instance ID, and API key
 * 2. Private key blob file (hpcs-privkey.blob) generated via HPCSKeyGeneratorCLI
 * 
 * Properties required in account configuration:
 * - hpcs-yaml-path: Path to grep11client.yaml (or set GREP11_YAML env var);
 *                   standard path: /etc/ep11client/grep11client.yaml
 * - hpcs-priv-key-blob-path: Path to hpcs-privkey.blob (or hpcs-priv-key-blob for base64)
 * - hpcs-key-label: Label for the RSA key pair (defaults to myuser when unset)
 * 
 * == PKCS#11 (.so library) — legacy, requires native library ==
 * 
 * Prerequisites:
 * 1. IBM PKCS#11 library installed (pkcs11-grep11-*.so)
 * 2. grep11client.yaml configured with HPCS credentials
 * 3. Token initialized with pkcs11-tool
 * 
 * Properties required:
 * - hpcs-pkcs11-library: Path to PKCS#11 library (auto-detected per OS/arch if unset)
 * - hpcs-user-pin: User PIN / API key for PKCS#11 operations
 * - hpcs-token-space: HPCS token space (defaults to "GREP11 Token")
 * - hpcs-key-label: Label for the RSA key pair (defaults to myuser when unset)
 */
class IBMHPCSKeyManager(implicit account: Account) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Resolve PKCS#11 library path with fallback order:
   * 1. Properties file (hpcs-pkcs11-library)
   * 2. Environment variable (HPCS_PKCS11_LIBRARY)
   * 3. Standard default paths based on OS
   */
  private def pkcs11Library: String = {
    // 1. Check properties file first (highest priority)
    Option(account.getProperty("hpcs-pkcs11-library"))
      .filter(_.nonEmpty)
      .orElse {
        // 2. Check environment variable
        Option(System.getenv("HPCS_PKCS11_LIBRARY"))
          .filter(_.nonEmpty)
      }
      .orElse {
        // 3. Standard default paths based on OS and architecture
        val osName = System.getProperty("os.name").toLowerCase
        val osArch = System.getProperty("os.arch").toLowerCase
        
        if (osName.contains("linux")) {
          if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            Some("/opt/hpcs/pkcs11-grep11-amd64.so")
          } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            Some("/opt/hpcs/pkcs11-grep11-arm64.so")
          } else if (osArch.contains("s390x") || osArch.contains("s390")) {
            // IBM z/Architecture (mainframe)
            Some("/opt/hpcs/pkcs11-grep11-s390x.so")
          } else {
            None
          }
        } else if (osName.contains("mac")) {
          if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            Some("/opt/hpcs/pkcs11-grep11-arm64.dylib")
          } else {
            Some("/opt/hpcs/pkcs11-grep11-amd64.dylib")
          }
        } else if (osName.contains("z/os") || osName.contains("zos")) {
          // IBM z/OS mainframe
          Some("/opt/hpcs/pkcs11-grep11-s390x.so")
        } else {
          None
        }
      }
      .orNull
  }
  
  private def tokenLabel: String = Option(account.getProperty("hpcs-token-space")).getOrElse("GREP11 Token")
  private def userPin: String = account.getProperty("hpcs-user-pin")
  private def keyLabel: String = Option(account.getProperty("hpcs-key-label")).getOrElse(account.MY_USER)

  // GREP11 (gRPC) config - no PKCS#11 .so required.
  // Resolve order: explicit prop / env, then grep11client.yaml in accountDir (same as blob).
  private lazy val grep11YamlPath: Option[String] = {
    Option(HpcsGrep11KeyGenerator.resolveYamlPath(account.getProperty("hpcs-yaml-path")))
      .filter(_.nonEmpty)
      .orElse {
        Option(account.accountDir).filter(_.nonEmpty).flatMap { dir =>
          val f = new File(dir, "grep11client.yaml")
          if (f.isFile) Some(f.getAbsolutePath) else None
        }
      }
  }
  private lazy val grep11ConfigFromYaml: Option[com.altastata.cloud.ibm.Grep11ConfigFromYaml] = {
    grep11YamlPath.flatMap { p =>
      try {
        val f = new File(p)
        if (f.exists()) Some(com.altastata.cloud.ibm.Grep11ConfigFromYaml.load(f.toPath))
        else {
          logger.warn(s"GREP11 yaml configured but file not found: $p")
          None
        }
      } catch { case e: Exception =>
        logger.warn(s"GREP11 yaml configured but failed to load: $p - ${e.getMessage}")
        None
      }
    }
  }
  private def grep11Endpoint: String = grep11ConfigFromYaml.map(_.endpoint).orElse(Option(account.getProperty("hpcs-endpoint")).filter(_.nonEmpty)).orElse(Option(System.getenv("HPCS_ENDPOINT"))).orNull
  private def grep11Port: Int = grep11ConfigFromYaml.map(_.port).orElse(Option(account.getProperty("hpcs-port")).filter(_.nonEmpty).map(_.toInt)).orElse(Option(System.getenv("HPCS_PORT")).filter(_.nonEmpty).map(_.toInt)).getOrElse(13412)
  private def grep11InstanceId: String = grep11ConfigFromYaml.map(_.instanceId).orElse(Option(account.getProperty("hpcs-instance-id")).filter(_.nonEmpty)).orElse(Option(System.getenv("HPCS_INSTANCEID"))).orNull
  private def grep11ApiKey: String = grep11ConfigFromYaml.map(_.apiKey).orElse(Option(account.getProperty("hpcs-user-pin")).filter(_.nonEmpty)).orElse(Option(System.getenv("HPCS_API_KEY"))).orNull
  /** Private key as serialized KeyBlob from file path, base64 property, or hpcs-privkey.blob in the account dir. */
  private def grep11PrivateKeyBlob: Array[Byte] = {
    /**
     * Reads the entire contents of a file if it exists and is a regular file.
     *
     * @param path the path to the file
     * @return an Option containing the file's bytes, or None if the file does not exist
     */
    def readFileIfExists(path: String): Option[Array[Byte]] = {
      val f = new File(path)
      if (f.isFile) Some(java.nio.file.Files.readAllBytes(f.toPath)) else None
    }
    val pathCandidates = Seq(
      Option(account.getProperty("hpcs-priv-key-blob-path")).filter(_.nonEmpty),
      Option(System.getenv("HPCS_PRIV_KEY_BLOB_PATH")).filter(_.nonEmpty)
    ).flatten
    pathCandidates.flatMap(readFileIfExists).headOption
      .orElse {
        Option(account.getProperty("hpcs-priv-key-blob")).filter(_.nonEmpty)
          .map(b => Base64.getDecoder.decode(b))
      }
      .orElse {
        Option(account.accountDir).filter(_.nonEmpty).flatMap { dir =>
          readFileIfExists(new File(dir, "hpcs-privkey.blob").getAbsolutePath)
        }
      }
      .orNull
  }
  /** GREP11 path: endpoint + instance ID + API key + private KeyBlob. */
  private def isGrep11Configured: Boolean =
    grep11Endpoint != null && grep11InstanceId != null && grep11ApiKey != null && grep11PrivateKeyBlob != null

  @volatile private var grep11Client: com.altastata.cloud.ibm.Grep11RsaClient = _

  // Cached PKCS#11 provider and keystore (used when GREP11 not configured)
  @volatile private var pkcs11Provider: Provider = _
  @volatile private var keyStore: KeyStore = _

  /**
   * Check if HPCS is properly configured (GREP11 gRPC or PKCS#11 .so).
   * GREP11: yaml+blob via prop/env or files in accountDir; PKCS#11: library + pin.
   */
  def isHPCSConfigured: Boolean = {
    if (isGrep11Configured) return true
    if (pkcs11Library == null) {
      logger.debug("HPCS not configured: hpcs-pkcs11-library or GREP11 (hpcs-endpoint + priv key blob) not set")
      return false
    }
    if (userPin == null) {
      logger.debug("HPCS not configured: hpcs-user-pin property not set")
      return false
    }
    val libraryFile = new File(pkcs11Library)
    if (!libraryFile.exists()) {
      logger.debug(s"HPCS not available: PKCS#11 library not found at $pkcs11Library")
      return false
    }
    true
  }
  
  /**
   * Validate HPCS configuration and throw descriptive error if not available.
   * GREP11: grep11client.yaml + hpcs-privkey.blob (prop/env or accountDir).
   * PKCS#11: hpcs-pkcs11-library + hpcs-user-pin.
   */
  def validateConfiguration(): Unit = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    if (keyLabel == null || keyLabel.isEmpty) {
      errors += "Key label is missing. Set property: myuser (username) or hpcs-key-label in user properties file"
    }
    if (isGrep11Configured) {
      return
    }
    val accountDirHasGrep11Files = Option(account.accountDir).filter(_.nonEmpty).exists { dir =>
      new File(dir, "grep11client.yaml").isFile || new File(dir, "hpcs-privkey.blob").isFile
    }
    val grep11Intended = grep11YamlPath.isDefined ||
      Option(account.getProperty("hpcs-priv-key-blob-path")).filter(_.nonEmpty).isDefined ||
      Option(account.getProperty("hpcs-priv-key-blob")).filter(_.nonEmpty).isDefined ||
      accountDirHasGrep11Files
    if (grep11Intended) {
      if (grep11YamlPath.isEmpty) {
        errors += "Missing grep11client.yaml (place it in the account directory, or set hpcs-yaml-path / GREP11_YAML)"
      } else if (grep11ConfigFromYaml.isEmpty) {
        errors += s"Failed to load GREP11 config from: ${grep11YamlPath.get}"
      }
      if (grep11PrivateKeyBlob == null) {
        errors += "Missing private key blob (place hpcs-privkey.blob in the account directory)"
      }
    } else {
      if (pkcs11Library == null) {
        errors += "Neither GREP11 nor PKCS#11 configured. For GREP11 place grep11client.yaml and hpcs-privkey.blob in the account directory. For PKCS#11 set: hpcs-pkcs11-library and hpcs-user-pin."
      } else if (!new File(pkcs11Library).exists()) {
        errors += s"PKCS#11 library not found at: $pkcs11Library"
      }
      if (userPin == null) {
        errors += "Missing property: hpcs-user-pin (required in user properties file)"
      }
    }
    if (errors.nonEmpty) {
      val msg = s"HPCS configuration errors:\n  - ${errors.mkString("\n  - ")}\n\n" +
        "GREP11: place grep11client.yaml and hpcs-privkey.blob in the account directory " +
        "(or set hpcs-yaml-path / GREP11_YAML).\n" +
        "PKCS#11: set hpcs-pkcs11-library and hpcs-user-pin.\n\n" +
        "See HPCS_KEY_PROTECTION.md and GREP11_SIGNINGSERVER_REFERENCE.md for setup."
      throw new IllegalStateException(msg)
    }
  }

  /** Lazy init of GREP11 client when using gRPC path. */
  private def ensureGrep11Client(): Unit = synchronized {
    if (grep11Client == null && isGrep11Configured) {
      validateConfiguration()
      logger.info(s"Initializing GREP11 client: $grep11Endpoint:$grep11Port")
      grep11Client = new com.altastata.cloud.ibm.Grep11RsaClient(grep11Endpoint, grep11Port, grep11InstanceId, grep11ApiKey)
    }
  }

  /**
   * Initialize PKCS#11 provider and keystore
   * 
   * Supports both Java 8 (InputStream constructor) and Java 9+ (configure method)
   */
  private def initializePKCS11(): Unit = synchronized {
    if (pkcs11Provider == null) {
      validateConfiguration()
      
      logger.info(s"Initializing PKCS#11 provider with library: $pkcs11Library")

      // Create PKCS#11 configuration
      // Try multiple formats to support different Java versions and PKCS#11 implementations
      val providerName = s"HPCS-${account.MY_USER}".replaceAll("[^a-zA-Z0-9_-]", "_")
      
      // PKCS#11 configuration with required attributes for HPCS
      // CKA_SIGN and CKA_DECRYPT are REQUIRED for crypto operations
      // Without these, Java will get CKR_KEY_FUNCTION_NOT_PERMITTED
      val configContent =
        s"""name = $providerName
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

      try {
        // Try Java 9+ method first, fall back to Java 8 if needed
        pkcs11Provider = try {
          initializePKCS11Java9Plus(configContent)
        } catch {
          case e: Exception =>
            val actualError = e match {
              case ite: java.lang.reflect.InvocationTargetException if ite.getCause != null =>
                ite.getCause
              case _ => e
            }
            logger.warn(s"Java 9+ PKCS#11 initialization failed: ${actualError.getMessage}")
            
            // Fall back to Java 8 method
            try {
              initializePKCS11Java8(configContent)
            } catch {
              case e2: Exception =>
                throw new RuntimeException(
                  s"PKCS#11 initialization failed. " +
                  s"Library: $pkcs11Library, Token: $tokenLabel. " +
                  s"Error: ${actualError.getMessage}", actualError)
            }
        }
        
        // Remove existing provider with same name if present
        val existingProvider = Security.getProvider(pkcs11Provider.getName)
        if (existingProvider != null) {
          Security.removeProvider(pkcs11Provider.getName)
        }
        
        Security.addProvider(pkcs11Provider)
        logger.info(s"PKCS#11 provider loaded: ${pkcs11Provider.getName}")

        // Load keystore
        keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
        keyStore.load(null, userPin.toCharArray)
        logger.info(s"PKCS#11 keystore loaded successfully")

      } catch {
        case e: Exception =>
          logger.error(s"Failed to initialize PKCS#11: ${e.getMessage}", e)
          throw new RuntimeException(s"HPCS PKCS#11 initialization failed: ${e.getMessage}", e)
      }
    }
  }
  
  /**
   * Initialize PKCS#11 for Java 9+ using Provider.configure() static method
   */
  private def initializePKCS11Java9Plus(configContent: String): Provider = {
    // Create a temporary config file
    val configFile = File.createTempFile("pkcs11-", ".cfg")
    configFile.deleteOnExit()
    
    val writer = new FileWriter(configFile)
    try {
      writer.write(configContent)
    } finally {
      writer.close()
    }
    
    // Get the SunPKCS11 provider prototype
    var sunPKCS11 = Security.getProvider("SunPKCS11")
    
    if (sunPKCS11 == null) {
      // Provider not pre-registered, instantiate directly (Java 9+)
      val providerClass = Class.forName("sun.security.pkcs11.SunPKCS11")
      try {
        val stringConstructor = providerClass.getConstructor(classOf[String])
        return stringConstructor.newInstance(configFile.getAbsolutePath).asInstanceOf[Provider]
      } catch {
        case _: NoSuchMethodException =>
          val noArgConstructor = providerClass.getConstructor()
          sunPKCS11 = noArgConstructor.newInstance().asInstanceOf[Provider]
      }
    }
    
    // Use Provider.configure() method (Java 9+)
    // CRITICAL: Must use classOf[Provider], NOT sunPKCS11.getClass - the latter causes SIGSEGV
    val configureMethod = classOf[Provider].getMethod("configure", classOf[String])
    configureMethod.invoke(sunPKCS11, configFile.getAbsolutePath).asInstanceOf[Provider]
  }
  
  /**
   * Initialize PKCS#11 for Java 8 using InputStream constructor
   */
  private def initializePKCS11Java8(configContent: String): Provider = {
    val providerClass = Class.forName("sun.security.pkcs11.SunPKCS11")
    val constructor = providerClass.getConstructor(classOf[java.io.InputStream])
    constructor.newInstance(new ByteArrayInputStream(configContent.getBytes)).asInstanceOf[Provider]
  }
  
  /**
   * Check if private key exists in HSM (local/blob presence only for GREP11).
   */
  def hasPrivateKey: Boolean = {
    if (isGrep11Configured) {
      val blob = grep11PrivateKeyBlob
      return blob != null && blob.length > 0
    }
    try {
      initializePKCS11()
      keyStore.containsAlias(keyLabel)
    } catch {
      case _: Exception => false
    }
  }

  /**
   * True when the private key can be used (GREP11: probe sign against HPCS; PKCS#11: alias in keystore).
   */
  def verifyPrivateKeyAccessible: Boolean = {
    if (isGrep11Configured) {
      val blob = grep11PrivateKeyBlob
      if (blob == null || blob.length == 0) return false
      try {
        ensureGrep11Client()
        val probe = "altastata-hpcs-probe".getBytes("UTF-8")
        val signature = grep11Client.sign(blob, probe)
        signature != null && signature.length > 0
      } catch {
        case e: Exception =>
          logger.warn(s"HPCS GREP11 key verification failed: ${e.getMessage}")
          false
      }
    } else {
      hasPrivateKey
    }
  }

  /**
   * Generate RSA key pair in HPCS.
   * 
   * The private key never leaves the HSM.
   * The public key is returned for local encryption and verification operations.
   * 
   * @return Tuple of (publicKeyPEM, keyLabel) - keyLabel serves as the "token" reference
   */
  def generateKeyPairInHPCS(): (String, Array[Byte]) = {
    logger.info(s"Generating RSA key pair in HPCS for user ${account.MY_USER}")

    initializePKCS11()

    try {
      // Check if key already exists
      if (keyStore.containsAlias(keyLabel)) {
        logger.warn(s"Key with label '$keyLabel' already exists in HSM")
        throw new IllegalStateException(s"Key '$keyLabel' already exists. Delete it first or use a different label.")
      }

      // Generate RSA key pair using PKCS#11 provider
      val keyPairGenerator = KeyPairGenerator.getInstance("RSA", pkcs11Provider)
      keyPairGenerator.initialize(4096)
      val keyPair = keyPairGenerator.generateKeyPair()

      logger.info(s"Generated RSA-4096 key pair in HPCS with label: $keyLabel")

      // Convert public key to PEM format
      val publicKeyPEM = publicKeyToPEM(keyPair.getPublic)

      // The "token" is just the key label - the actual private key is in the HSM
      val tokenBytes = keyLabel.getBytes("UTF-8")

      logger.info(s"HPCS key generation complete. Public key ready for certificate signing.")

      (publicKeyPEM, tokenBytes)

    } catch {
      case e: Exception =>
        logger.error(s"Failed to generate key pair in HPCS: ${e.getMessage}", e)
        throw new RuntimeException(s"HPCS key generation failed: ${e.getMessage}", e)
    }
  }

  /**
   * Write public key PEM and hpcs.marker to an account directory.
   * Used by both the UI handler and HPCSCreateKey main so the logic is not duplicated.
   */
  def writePublicKeyToAccountDirectory(publicKeyPEM: String, keyLabelValue: String, dirPath: String): Unit =
    writePublicKeyToAccountDirectory(publicKeyPEM, keyLabelValue, dirPath, null)

  /**
   * Write public key PEM, optional private key blob, and hpcs.marker to an account directory.
   * When privateKeyBlob is non-null (GREP11 path), also writes hpcs-privkey.blob.
   */
  def writePublicKeyToAccountDirectory(
      publicKeyPEM: String,
      keyLabelValue: String,
      dirPath: String,
      privateKeyBlob: Array[Byte]): Unit = {
    com.altastata.cloud.ibm.HpcsAccountGuard.assertSafeToWriteKeyFiles(dirPath)
    val dir = java.nio.file.Paths.get(dirPath)
    val utf8 = java.nio.charset.StandardCharsets.UTF_8
    java.nio.file.Files.write(dir.resolve("public.key"), publicKeyPEM.getBytes(utf8))
    if (privateKeyBlob != null && privateKeyBlob.nonEmpty) {
      val privateKeyBlobPath = dir.resolve("hpcs-privkey.blob")
      java.nio.file.Files.write(privateKeyBlobPath, privateKeyBlob)
      FileSecurity.restrictToOwner(privateKeyBlobPath)
      logger.info(s"Private key blob written to $dirPath/hpcs-privkey.blob")
    }
    val markerContent = s"key-label=$keyLabelValue\ncreated=${System.currentTimeMillis()}\nhpcs-protected=true\n"
    java.nio.file.Files.write(dir.resolve("hpcs.marker"), markerContent.getBytes(utf8))
    logger.info(s"Public key and marker written to $dirPath")
  }

  /**
   * Unwrap (decrypt) data using HPCS-protected private key.
   * 
   * The encrypted data is decrypted inside the HSM.
   * The private key never leaves the HSM.
   * 
   * @param encrypted The encrypted data (RSA-encrypted)
   * @param token The key label (from hpcs-key-label property)
   * @return Decrypted plaintext
   */
  def unwrap(encrypted: Array[Byte], token: Array[Byte] = keyLabel.getBytes("UTF-8")): Array[Byte] = {
    val label = new String(token, "UTF-8")

    if (isGrep11Configured) {
      try {
        ensureGrep11Client()
        grep11Client.decryptOaepSha256(grep11PrivateKeyBlob, encrypted)
      } catch {
        case e: Exception =>
          logger.error(s"HPCS GREP11 unwrap failed: ${e.getMessage}", e)
          throw new RuntimeException(s"HPCS unwrap failed: ${e.getMessage}", e)
      }
    } else {
      initializePKCS11()
      try {
        val privateKey = keyStore.getKey(label, userPin.toCharArray).asInstanceOf[PrivateKey]
        if (privateKey == null) throw new IllegalStateException(s"Private key '$label' not found in HPCS")
        val cipher = Cipher.getInstance(Constants.RSA_OAEP, pkcs11Provider)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(encrypted)
      } catch {
        case e: Exception =>
          logger.error(s"HPCS unwrap failed: ${e.getMessage}", e)
          throw new RuntimeException(s"HPCS unwrap failed: ${e.getMessage}", e)
      }
    }
  }

  /**
   * Sign data using HPCS-protected private key.
   * 
   * The data is signed inside the HSM.
   * The private key never leaves the HSM.
   * 
   * @param data The data to sign
   * @param token The key label (from hpcs-key-label property)
   * @return Signature
   */
  def sign(data: Array[Byte], token: Array[Byte] = keyLabel.getBytes("UTF-8")): Array[Byte] = {
    val label = new String(token, "UTF-8")
    logger.debug(s"Signing ${data.length} bytes using HPCS key: $label")

    if (isGrep11Configured) {
      try {
        ensureGrep11Client()
        grep11Client.sign(grep11PrivateKeyBlob, data)
      } catch {
        case e: Exception =>
          logger.error(s"HPCS GREP11 sign failed: ${e.getMessage}", e)
          throw new RuntimeException(s"HPCS sign failed: ${e.getMessage}", e)
      }
    } else {
      initializePKCS11()
      try {
        val privateKey = keyStore.getKey(label, userPin.toCharArray).asInstanceOf[PrivateKey]
        if (privateKey == null) throw new IllegalStateException(s"Private key '$label' not found in HPCS")
        val signature = Signature.getInstance("SHA256withRSA", pkcs11Provider)
        signature.initSign(privateKey)
        signature.update(data)
        signature.sign()
      } catch {
        case e: Exception =>
          logger.error(s"HPCS sign failed: ${e.getMessage}", e)
          throw new RuntimeException(s"HPCS sign failed: ${e.getMessage}", e)
      }
    }
  }

  /** Optional RSA modulus bits (1024, 2048, or 4096). When set, used for getRSABlockSize when public key is not available (e.g. GREP11 without hpcs-cert-path). */
  private def hpcsRsaModulusBits: Option[Int] =
    Option(account.getProperty("hpcs-rsa-modulus-bits")).filter(_.nonEmpty).flatMap { s =>
      val n = s.trim.toInt
      if (n == 1024 || n == 2048 || n == 4096) Some(n) else { logger.warn(s"hpcs-rsa-modulus-bits=$s ignored (use 1024, 2048, 4096)"); None }
    }

  /**
   * RSA block size in bytes for this HPCS key (used e.g. for decryptArrayWithRSA).
   * Derived from the public key modulus bit length when available (e.g. RSA 2048 → 256, RSA 4096 → 512).
   * When public key is not available (e.g. GREP11 without hpcs-cert-path or hpcs-public-key-pem):
   * uses hpcs-rsa-modulus-bits if set (2048 or 4096), otherwise defaults to 256 (2048-bit). For 4096-bit
   * keys without a cert/PEM, set hpcs-rsa-modulus-bits=4096 so block size is 512.
   */
  def getRSABlockSize(): Int = {
    getPublicKey() match {
      case Some(pub) =>
        pub.asInstanceOf[RSAPublicKey].getModulus.bitLength() / 8
      case None =>
        hpcsRsaModulusBits match {
          case Some(bits) =>
            bits / 8
          case None =>
            logger.debug("HPCS public key not available; using default block size 256 (2048-bit). For 4096-bit keys set hpcs-rsa-modulus-bits=4096")
            256
        }
    }
  }

  /**
   * Verify signature using public key (local operation, no HSM needed).
   */
  def verify(publicKey: PublicKey, data: Array[Byte], signature: Array[Byte]): Boolean = {
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initVerify(publicKey)
    sig.update(data)
    sig.verify(signature)
  }

  /**
   * Get public key from HSM by label (PKCS#11). For GREP11 we do not read the public key here;
   * it is taken from the certificate (AWS userdata) via Account.getHPCSPublicKeyOption() when we have it.
   */
  def getPublicKey(label: String = keyLabel): Option[PublicKey] = {
    if (isGrep11Configured)
      return None
    try {
      initializePKCS11()
      val cert = keyStore.getCertificate(label)
      if (cert != null) Some(cert.getPublicKey) else None
    } catch {
      case _: Exception => None
    }
  }

  /**
   * Import certificate to HPCS.
   * 
   * CRITICAL: The certificate MUST be imported to HPCS with the same label as the private key.
   * Java's SunPKCS11 KeyStore requires a certificate to be present in HPCS to access the private key.
   * 
   * This should be called after:
   * 1. Key pair is generated in HPCS (generateKeyPairInHPCS)
   * 2. Public key is sent to AltaStata PKI
   * 3. AltaStata issues certificate
   * 4. User receives certificate and calls this method
   * 
   * @param certificatePEM The certificate in PEM format (from AltaStata PKI)
   * @param label The key label (must match the private key label in HPCS)
   */
  def importCertificateToHPCS(certificatePEM: String, label: String = keyLabel): Unit = {
    logger.info(s"Importing certificate to HPCS for key: $label")
    
    initializePKCS11()
    
    try {
      // Parse PEM certificate
      val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
      val certInputStream = new ByteArrayInputStream(certificatePEM.getBytes("UTF-8"))
      val certificate = certFactory.generateCertificate(certInputStream).asInstanceOf[java.security.cert.X509Certificate]
      
      logger.debug(s"Certificate subject: ${certificate.getSubjectX500Principal}")
      logger.debug(s"Certificate issuer: ${certificate.getIssuerX500Principal}")
      
      // Check if private key exists
      if (!keyStore.containsAlias(label)) {
        throw new IllegalStateException(
          s"Private key '$label' not found in HPCS. " +
          "Generate the key pair first using generateKeyPairInHPCS()."
        )
      }
      
      // Get the private key handle (not the actual key)
      val privateKey = keyStore.getKey(label, userPin.toCharArray).asInstanceOf[PrivateKey]
      
      if (privateKey == null) {
        throw new IllegalStateException(s"Could not retrieve private key handle for '$label'")
      }
      
      // Store the private key with its certificate chain
      // This associates the certificate with the existing private key in HPCS
      val certChain = Array[java.security.cert.Certificate](certificate)
      keyStore.setKeyEntry(label, privateKey, userPin.toCharArray, certChain)
      
      logger.info(s"Certificate successfully imported to HPCS for key: $label")
      
      // Verify the import
      val storedCert = keyStore.getCertificate(label)
      if (storedCert == null) {
        logger.warn("Certificate import may have failed - certificate not found after import")
      } else {
        logger.info("Certificate verified in HPCS keystore")
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"Failed to import certificate to HPCS: ${e.getMessage}", e)
        throw new RuntimeException(s"HPCS certificate import failed: ${e.getMessage}", e)
    }
  }

  /**
   * Check if certificate exists in HPCS for the given key label
   */
  def hasCertificate(label: String = keyLabel): Boolean = {
    try {
      initializePKCS11()
      keyStore.getCertificate(label) != null
    } catch {
      case _: Exception => false
    }
  }

  /**
   * Generate RSA key pair in HPCS AND create/import self-signed certificate.
   * 
   * This is the recommended method for new user setup - it does everything in one call:
   * 1. Generates RSA key pair in HPCS (private key never leaves HSM)
   * 2. Creates self-signed X.509 certificate
   * 3. Imports certificate to HPCS (required for Java KeyStore access)
   * 
   * @param username Username for certificate CN
   * @return Tuple of (publicKeyPEM, certificatePEM)
   */
  def generateKeyPairWithCertificate(username: String = account.MY_USER): (String, String) = {
    logger.info(s"Generating RSA key pair with certificate in HPCS for user: $username")
    
    initializePKCS11()
    
    try {
      // Check if key already exists
      if (keyStore.containsAlias(keyLabel)) {
        logger.warn(s"Key with label '$keyLabel' already exists in HSM")
        throw new IllegalStateException(s"Key '$keyLabel' already exists. Delete it first or use a different label.")
      }
      
      // Generate RSA key pair using PKCS#11 provider
      val keyPairGenerator = KeyPairGenerator.getInstance("RSA", pkcs11Provider)
      keyPairGenerator.initialize(4096)
      val keyPair = keyPairGenerator.generateKeyPair()
      
      logger.info(s"Generated RSA-4096 key pair in HPCS with label: $keyLabel")
      
      // Convert public key to PEM format
      val publicKeyPEM = publicKeyToPEM(keyPair.getPublic)
      
      // Create self-signed certificate
      val certificate = createSelfSignedCertificate(username, keyPair)
      
      // Import certificate to HPCS (associates it with the private key)
      val certChain = Array[java.security.cert.Certificate](certificate)
      keyStore.setKeyEntry(keyLabel, keyPair.getPrivate, userPin.toCharArray, certChain)
      
      logger.info(s"Certificate imported to HPCS for key: $keyLabel")
      
      // Convert certificate to PEM
      val certificatePEM = certificateToPEM(certificate)
      
      (publicKeyPEM, certificatePEM)
      
    } catch {
      case e: Exception =>
        logger.error(s"Failed to generate key pair with certificate: ${e.getMessage}", e)
        throw new RuntimeException(s"HPCS key generation failed: ${e.getMessage}", e)
    }
  }

  /**
   * Create a self-signed X.509 certificate for the given key pair (BouncyCastle; Java 17–safe).
   *
   * @param username Username for certificate CN
   * @param keyPair The key pair (public key will be in cert, private key signs it)
   * @param validityYears How long the certificate is valid (default 10 years)
   * @return X509Certificate
   */
  def createSelfSignedCertificate(
      username: String, 
      keyPair: KeyPair, 
      validityYears: Int = 10): X509Certificate = {
    
    logger.debug(s"Creating self-signed certificate for: $username")
    
    val publicKey = keyPair.getPublic
    val privateKey = keyPair.getPrivate
    
    val notBefore = new Date()
    val notAfter =
      new Date(notBefore.getTime + validityYears.toLong * 365 * 24 * 60 * 60 * 1000)

    val serialNumber = BigInteger.valueOf(System.currentTimeMillis)

    val dn = new X500Name(s"CN=$username, O=AltaStata, C=US")
    val signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
    val certHolder =
      new JcaX509v3CertificateBuilder(dn, serialNumber, notBefore, notAfter, dn, publicKey).build(
        signer)
    val cert = new JcaX509CertificateConverter().getCertificate(certHolder)

    logger.debug(s"Self-signed certificate created, valid until: $notAfter")

    cert
  }

  /**
   * Convert X509 certificate to PEM format
   */
  def certificateToPEM(certificate: X509Certificate): String = {
    val encoded = Base64.getEncoder.encodeToString(certificate.getEncoded)
    val sb = new StringBuilder
    sb.append("-----BEGIN CERTIFICATE-----\n")
    encoded.grouped(64).foreach { line =>
      sb.append(line)
      sb.append("\n")
    }
    sb.append("-----END CERTIFICATE-----")
    sb.toString()
  }

  /**
   * Convert public key to PEM format
   */
  private def publicKeyToPEM(publicKey: PublicKey): String = {
    val encoded = Base64.getEncoder.encodeToString(publicKey.getEncoded)
    val sb = new StringBuilder
    sb.append("-----BEGIN PUBLIC KEY-----\n")
    
    // Split into 64-character lines
    encoded.grouped(64).foreach { line =>
      sb.append(line)
      sb.append("\n")
    }
    
    sb.append("-----END PUBLIC KEY-----")
    sb.toString()
  }

  /**
   * Cleanup - remove PKCS#11 provider and close GREP11 client
   */
  def cleanup(): Unit = synchronized {
    if (grep11Client != null) {
      try { grep11Client.close() } catch { case _: Exception => }
      grep11Client = null
      logger.info("GREP11 client closed")
    }
    if (pkcs11Provider != null) {
      Security.removeProvider(pkcs11Provider.getName)
      pkcs11Provider = null
      keyStore = null
      logger.info("PKCS#11 provider removed")
    }
  }
}

object IBMHPCSKeyManager {

  /**
   * Check if key-protection is set to HPCS
   */
  def isHPCSKeyProtection(implicit account: Account): Boolean = {
    account.getProperty("key-protection") == "HPCS"
  }

  /**
   * GREP11 endpoints by region (for documentation/configuration reference)
   */
  val ENDPOINTS = Map(
    "us-south" -> "ep11.us-south.hs-crypto.cloud.ibm.com",
    "us-east" -> "ep11.us-east.hs-crypto.cloud.ibm.com",
    "eu-de" -> "ep11.eu-de.hs-crypto.cloud.ibm.com",
    "eu-gb" -> "ep11.eu-gb.hs-crypto.cloud.ibm.com",
    "jp-tok" -> "ep11.jp-tok.hs-crypto.cloud.ibm.com"
  )

  /**
   * Required properties for GREP11 (gRPC) configuration — preferred path
   */
  val REQUIRED_PROPERTIES_GREP11 = List(
    "hpcs-yaml-path",           // Path to grep11client.yaml (or GREP11_YAML env)
    "hpcs-priv-key-blob-path"   // Path to hpcs-privkey.blob (or hpcs-priv-key-blob for base64)
  )

  /**
   * Required properties for PKCS#11 configuration — legacy path
   */
  val REQUIRED_PROPERTIES_PKCS11 = List(
    "hpcs-pkcs11-library",  // Path to PKCS#11 .so file (auto-detected if unset)
    "hpcs-user-pin"         // User PIN / API key for HSM operations
  )

  /** Alias for backward compatibility */
  val REQUIRED_PROPERTIES = REQUIRED_PROPERTIES_PKCS11

  /**
   * Optional properties with defaults
   */
  val OPTIONAL_PROPERTIES = Map(
    "hpcs-token-space" -> "GREP11 Token",
    "hpcs-key-label" -> "{username}"
  )
}
