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

package com.altastata.utils

import java.util.UUID
import scala.concurrent._
import scala.concurrent.duration.Duration
import java.util.Properties
import java.io.FileInputStream
import com.altastata.filesystem.FileSystemModel
import com.altastata.filesystem.securecloud.{CloudHSMHandler, CloudMsgsHandler, CloudObjectHandler, CloudUserCreatingHandler, OpsExecutors, SecureCloudEventProcessor, SecureCloudFileSystemModel}
import com.altastata.cloud.amazon_java2.{AmazonCloudFileSystemModel, AmazonCloudObjectHandler, AmazonKmsManager, AmazonSQSManager, AmazonUserCreatingManager, CognitoClient}
import com.altastata.cloud.azure_v12.AzureCloudObjectHandler
import com.altastata.cloud.localfs.LocalFSCloudObjectHandler
import com.altastata.cloud.azure_v12.AzureSQSManager
import com.altastata.filesystem.UserMetadata
import com.altastata.cloud.ibm.{IBMCloudObjectHandler, IBMHPCSKeyManager}
import com.altastata.cloud.minio.{MinIOCloudObjectHandler}
import com.altastata.cloud.fusion.{FusionCloudObjectHandler}

import scala.language.postfixOps
import java.io.File
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import java.io.FileOutputStream
import scala.util.{Try, Success, Failure}
import com.altastata.crypto.AsymmetricKeysGenerator
import com.bettercloud.vault.VaultConfig
import com.bettercloud.vault.Vault

import java.util.Collections
import java.util.HashMap
import java.security.PublicKey
import com.altastata.filesystem.common.FileSystemHandler

import java.io.StringReader
import com.altastata.cloud.azure_v12.AzureCloudFileSystemModel
import com.altastata.cloud.localfs.LocalMessagesManager

import java.nio.file.Files
import java.nio.file.Paths
import java.io.IOError
import java.io.IOException
import java.io.Reader
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.concurrent.TrieMap
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.Level
import com.altastata.cloud.trustee.TrusteePropertyResolver
import com.altastata.crypto.CertTrustAnchor
import com.altastata.licensing.{AccountLicensing, OrgLicense}

class Account {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Logical username of the custodian user for key sharing delegation.
   */
  def CUSTODIAN_USER = ORGANIZATION + "custodian"

  /**
   * Suffix appended to user metadata storage containers.
   */
  def USERS_SUFFIX = "users"

  /**
   * Organization namespace derived from the global account prefix.
   */
  def ORGANIZATION = ACCOUNT_CONTAINER_PREFIX.split('-')(1)

  /**
   * Path of the hidden, private directory used to store local account metadata.
   */
  def PRIVATE_DATA_DIRECTORY = ".altastata/"

  /**
   * The canonical folder name used to store message queue elements.
   */
  def QUEUE_NAME = "msgqueue"

  /**
   * Path of the ranges configuration file in private data directory.
   */
  def CATALOG_RANGES = PRIVATE_DATA_DIRECTORY + "catalog.ranges"

  val userProps: Properties = new Properties()
  val userMsgs = Collections.synchronizedList(new java.util.ArrayList[String]())

  val userNameToSigningPublicKey = TrieMap[String, PublicKey]()
  
  /**
   * Per-account bounded ciphertext cache. One Caffeine cache keyed by file path holds a
   * bundle of (encrypted metadata, encrypted single-chunk-file chunk 0, encrypted
   * DATA_PROPERTIES attributes), so a revocation event can drop everything for a file
   * with one `invalidate(path)` call. Sizing comes from `cacheSizeBytes` (read from
   * `*.user.properties`). Replaces the previous unbounded
   * `serializedMetadataCache: ConcurrentHashMap`. See `com.altastata.cache.AltaStataCaches`.
   */
  val caches: com.altastata.cache.AltaStataCaches = new com.altastata.cache.AltaStataCaches(this)
  
  var cognitoPassword: String = null
  val cognitoClient = new CognitoClient()(this)

  val secureCloudEventProcessor: SecureCloudEventProcessor = new SecureCloudEventProcessor()(this)

  val secureCloudFileSystemModel: SecureCloudFileSystemModel = new SecureCloudFileSystemModel()(this)

  /**
   * Gets the SecureCloudFileSystemModel associated with this account.
   *
   * @return the active SecureCloudFileSystemModel instance
   */
  def getSecureCloudFileSystemModel(): SecureCloudFileSystemModel = secureCloudFileSystemModel

  val fileSystemHandler: FileSystemHandler = new FileSystemHandler(this)

  /**
   * Gets the active FileSystemHandler managing directory lifecycle states.
   *
   * @return the active FileSystemHandler instance
   */
  def getFileSystemHandler(): FileSystemHandler = fileSystemHandler

  /**
   * The user ID that authenticated with the client application (e.g., email address or principal name).
   */
  def MY_USER = userProps.getProperty("myuser")

  /**
   * The global prefix ensuring all buckets/containers created by this account are globally unique.
   * e.g., `altastata-mycompany-`
   */
  def ACCOUNT_CONTAINER_PREFIX = userProps.getProperty("acccontainer-prefix")

  /**
   * Logical account type identifier (e.g., amazon-s3-secure, azure-secure).
   */
  def ACCOUNT_TYPE = userProps.getProperty("accounttype")
  
  /**
   * Bucket for storing user metadata (public certificates, keys).
   * Users can only list and get the data.
   */
  def USERS_BUCKET = ACCOUNT_CONTAINER_PREFIX + USERS_SUFFIX
  
  /**
   * Bucket for storing the actual encrypted data chunks.
   * The permissions for <CHUNKS_BUCKET>/alice-gmail-com/'*' are:
   *
   * - Nobody can list
   * - All users can get
   * - Custodian user can do nothing
   * - User bob123 can get, write, delete
   *
   */
  def CHUNKS_BUCKET = ACCOUNT_CONTAINER_PREFIX + "chunks"

  /**
   * Bucket for storing encrypted data attributes (access controls, sizes, tags).
   * The permissions for <DATA_PROPERTIES_BUCKET>/alice-gmail-com/'*' are:
   *
   * - Nobody can list
   * - All users can get
   * - User bob123 can get, write, delete
   *
   */
  def DATA_PROPERTIES_BUCKET = ACCOUNT_CONTAINER_PREFIX + "dataattributes"

  /**
   * Bucket defining the logical directory structure and containing pointers to metadata.
   * The permissions for <CATALOG_BUCKET>/alice-gmail-com/'*' are:
   *
   * - All users can get
   * - Custodian user can list, get, write, delete
   * - User bob123 can list, get, write, delete
   * - other users can do nothing
   */
  def CATALOG_BUCKET = ACCOUNT_CONTAINER_PREFIX + "catalog"

  /**
   * The global cloud bucket/container used to post sharing and revocation change notifications.
   */
  def CHANGES_BUCKET = ACCOUNT_CONTAINER_PREFIX + "changes"

  /**
   * Gets the encrypted RSA private key PEM string value.
   */
  def ENCRYPTED_RSA_PRIVATE_KEY_PEM = encryptedRSAPrivateKeyPEM

  /**
   * Gets the encrypted Kyber private key PEM string value.
   */
  def ENCRYPTED_KYBER_PRIVATE_KEY_PEM = encryptedKyberPrivateKeyPEM

  /**
   * Gets the encrypted Dilithium private key PEM string value.
   */
  def ENCRYPTED_DILITHIUM_PRIVATE_KEY_PEM = encryptedDilithiumPrivateKeyPEM

  var encryptedRSAPrivateKeyPEM: String = null

  var encryptedKyberPrivateKeyPEM: String = null

  var encryptedDilithiumPrivateKeyPEM: String = null

  var ALTASTATA_PUBLIC_KEY_FILE: String = File.separator + "altastata_public.key"

  var currentPropertyFileName: String = null

  var accountDir: String = null

  private var accountPassword: Array[Char] = null
  var accountPasswordNextExpiredTime = 0L

  var myFileSystemModel: FileSystemModel = null
  var myCloudObjectHandler: CloudObjectHandler = null
  var myCloudMsgsHandler: CloudMsgsHandler = null
  var myCloudHSMHandler: CloudHSMHandler = null

  /** Single HPCS key manager instance per account (when key-protection=HPCS). Reused for sign/unwrap. */
  var hpcsKeyManager: IBMHPCSKeyManager = null

  /** Cached HPCS public key resolved during account init (from AWS userdata cert or account dir public.key/public.pem). */
  private var cachedHPCSPublicKeyResolved: Boolean = false
  private var cachedHPCSPublicKey: Option[PublicKey] = None

  private var runtimeOrgLicense: Option[OrgLicense] = None
  private var runtimeCertTrustPublicKey: PublicKey = CertTrustAnchor.communityIssuerPublicKey
  private var runtimeLicensedIdentity: Boolean = false

  private[altastata] def setRuntimeLicensing(
    license: Option[OrgLicense],
    certTrustPublicKey: PublicKey,
    licensedIdentity: Boolean
  ): Unit = {
    runtimeOrgLicense = license
    runtimeCertTrustPublicKey = certTrustPublicKey
    runtimeLicensedIdentity = licensedIdentity
  }

  /**
   * Gets the verified OrgLicense subscription active for the current runtime.
   *
   * @return Option containing the OrgLicense if licensed; None otherwise
   */
  def getOrgLicense: Option[OrgLicense] = runtimeOrgLicense

  /**
   * Gets the trust anchor public key used to verify user certificates.
   *
   * @return the trusted authority PublicKey instance
   */
  def getCertTrustPublicKey: PublicKey = runtimeCertTrustPublicKey

  /** When false, user certs are verified by signature against org-ca.pem only (Enterprise customer CA). */
  def enforceAltaStataIssuerCn: Boolean = !runtimeLicensedIdentity

  /**
   * Resolves and returns the active FileSystemModel logic matching the configured account type.
   *
   * @return the resolved FileSystemModel instance
   */
  def fileSystemModel: FileSystemModel = {
    ensureHPCSInitialized()
    if (myFileSystemModel == null) {
      myFileSystemModel = userProps.getProperty("accounttype") match {
        case null => throw new IllegalStateException("The user property 'accounttype' is not defined")
        case "amazon-s3" => new AmazonCloudFileSystemModel(userProps.getProperty("bucketname"))(this)
        case "azure" => new AzureCloudFileSystemModel(userProps.getProperty("containername"))(this)
        case "amazon-s3-secure" => secureCloudFileSystemModel.startResolvingCheckpoints()
        case "amazon-s3-cof-secure" => secureCloudFileSystemModel.startResolvingCheckpoints()
        case "azure-secure" => secureCloudFileSystemModel.startResolvingCheckpoints()
        case "ibm-cos-secure" => secureCloudFileSystemModel.startResolvingCheckpoints()
        case s if s.matches(""".*-secure""") => secureCloudFileSystemModel.startResolvingCheckpoints()
      }
    }

    myFileSystemModel
  }

  /**
   * Resolves and returns the active CloudObjectHandler bridging filesystem operations with cloud storage.
   *
   * @return the resolved CloudObjectHandler instance
   */
  def cloudObjectHandler: CloudObjectHandler = {
    ensureHPCSInitialized()
    if (myCloudObjectHandler == null) {

      myCloudObjectHandler = userProps.getProperty("accounttype") match {
        case null => throw new IllegalStateException("The user property 'accounttype' is not defined")
        case "localfs-secure" => new LocalFSCloudObjectHandler()(this)
        case "amazon-s3-secure" => new AmazonCloudObjectHandler()(this)
        case "amazon-s3-cof-secure" => new AmazonCloudObjectHandler()(this)
        case "azure-secure" => new AzureCloudObjectHandler()(this)
        case "ibm-cos-secure" => new IBMCloudObjectHandler()(this)
        case "minio-secure" => new MinIOCloudObjectHandler()(this)
        case "fusion-secure" => new FusionCloudObjectHandler()(this)
        case "google-secure" => {
          // Reflective load (not `new GoogleCloudObjectHandler`) so Account.scala compiles with -PnoGCP:
          // that build excludes com.altastata.cloud.google.* sources and GCP deps from altastata-core.
          // At runtime the class must be present in the JAR (full build); google-secure on a noGCP JAR fails here.
          val clazz = Class.forName("com.altastata.cloud.google.GoogleCloudObjectHandler")
          val constructor = clazz.getDeclaredConstructor(classOf[Account])
          constructor.newInstance(this).asInstanceOf[CloudObjectHandler]
        }
        case "multicloud-secure" => new com.altastata.cloud.multicloud.MultiCloudObjectHandler()(this)
      }
    }

    myCloudObjectHandler
  }

  /**
   * Resolves and returns the active CloudMsgsHandler handling SQS/message queue transfers.
   *
   * @return the resolved CloudMsgsHandler instance
   */
  def cloudMsgsHandler: CloudMsgsHandler = {
    ensureHPCSInitialized()
    if (myCloudMsgsHandler == null) {
      myCloudMsgsHandler = userProps.getProperty("accounttype") match {
        case null => throw new IllegalStateException("The user property 'accounttype' is not defined")
        case "localfs-secure" => new LocalMessagesManager()(this)
        case "amazon-s3-secure" => new LocalMessagesManager()(this)
        case "amazon-s3-cof-secure" => new LocalMessagesManager()(this)
        case "azure-secure" => new LocalMessagesManager()(this)
        case "ibm-cos-secure" => new LocalMessagesManager()(this)
        case "minio-secure" => new LocalMessagesManager()(this)
        case "fusion-secure" => new LocalMessagesManager()(this)
        case "google-secure" => new LocalMessagesManager()(this) // GooglePubSubManager exists but is not wired here yet
        case "multicloud-secure" => new LocalMessagesManager()(this)
      }
    }

    myCloudMsgsHandler
  }

  /**
   * Resolves and returns the active CloudHSMHandler bridging with key protection modules (AWS KMS / GCP KMS).
   *
   * @return the resolved CloudHSMHandler instance, or null if HSM is disabled
   */
  def cloudHSMHandler: CloudHSMHandler = {
    ensureHPCSInitialized()
    if (myCloudHSMHandler == null) {
      myCloudHSMHandler = userProps.getProperty("accounttype") match {
        case null => throw new IllegalStateException("The user property 'accounttype' is not defined")
        case "amazon-s3-secure" => new AmazonKmsManager()(this)
        case "amazon-s3-cof-secure" => new AmazonKmsManager()(this)
        case "azure-secure" => null
        case "ibm-cos-secure" => null
        case "minio-secure" => null
        case "fusion-secure" => null  // HSM handled via HPCS or CEX
        case "google-secure" => {
          // Same -PnoGCP rationale as GoogleCloudObjectHandler above (optional Google module at compile time).
          val clazz = Class.forName("com.altastata.cloud.google.GoogleKmsManager")
          val ctor = clazz.getConstructor(classOf[Account])
          ctor.newInstance(this).asInstanceOf[CloudHSMHandler]
        }
        case "localfs-secure" => null
        case "multicloud-secure" => null
      }
    }

    myCloudHSMHandler
  }

  /**
   * Resolves and returns a CloudUserCreatingHandler matching the account settings to register new users.
   *
   * @param properties configuration details
   * @return the resolved CloudUserCreatingHandler instance
   */
  def cloudUserCreatingHandler(properties: Properties): CloudUserCreatingHandler = {
    properties.getProperty("accounttype") match {
      case null => throw new IllegalStateException("CloudUserCreatingHandler: The user property 'accounttype' is not defined")
      case "amazon-s3-secure" => new AmazonUserCreatingManager(properties)(this)
      case accountType => throw new IllegalStateException(s"CloudUserCreatingHandler: The account type '$accountType' is not supported for automatic creation. It must be created by an administrator.")
    }
  }

  /**
   * Retrieves a synchronized Java List containing all local and cloud notifications posted during this session.
   *
   * @return the list of string notifications
   */
  def getUserMsgs(): java.util.List[String] = {
    userMsgs
  }

  var threadSQS: Thread = null

  /**
   * Clears loaded user properties and stops the change-queue poller if running.
   * Used by SetupUI after enrollment so a temporarily started event loop does not
   * keep listing the changes bucket. Prefer [[abandonFailedLogin]] after license/login failures.
   */
  def cleanUserProperties(): Unit = {
    // Stop first so the poller cannot observe a cleared userProps mid-iteration.
    stopEventLoop()
    userProps.clear()
  }

  /**
   * Full rollback after a failed login / license gate: clears keys, handlers, licensing state.
   * Prevents half-loaded accounts (e.g. PQC PEMs loaded, then JWT rejected) from prompting for password forever.
   */
  def abandonFailedLogin(): Unit = {
    resetAccountProperties()
  }

  /**
   * Extends the specified Properties configuration with default parameter properties if they are missing.
   *
   * @param props the target properties to enhance
   */
  def addDefaultProps(props: Properties) = {
    if (props.getProperty("compresstypes") == null) props.setProperty("compresstypes", ".*.(txt|csv|parquet)")
    if (props.getProperty("delete-previous-on-upload") == null) props.setProperty("delete-previous-on-upload", "false")
    if (props.getProperty("encrypt-names") == null) props.setProperty("encrypt-names", "false")
    if (props.getProperty("sqs-interval") == null) props.setProperty("sqs-interval", "5")
    if (props.getProperty("password-timeout-interval") == null) props.setProperty("password-timeout-interval", "900")
    if (props.getProperty("password-source") == null) props.setProperty("password-source", "ui")
    if (props.getProperty("enterprise-custodian-mode") == null) props.setProperty("enterprise-custodian-mode", "false")
  }

  /**
   * Parses and loads account user properties directly from a plain-text configuration string.
   *
   * @param text the raw properties file plaintext content
   */
  def loadUserProperties(text: String) = {
    logger.info(s"loadUserProperties from text")

    resetAccountProperties()

    try {
      userProps.load(new StringReader(text))
      addDefaultProps(userProps)

      resolveTrusteeAndPassword().foreach(e => logger.error(e))
      AccountLicensing.refresh(this)
      startEventLoopAfterPropertiesLoad()
    } catch {
      case e: SecurityException =>
        abandonFailedLogin()
        throw e
    }
  }

  /**
   * Loads account properties from a local home folder directory path.
   *
   * RSA/PQC: call {@link #setPassword} afterwards (that also starts the event loop).
   * HSM/HPCS ({@code metadata-encryption=HSM} or {@code key-protection=HPCS}):
   * the event loop starts automatically at the end of this load; a stray local
   * private key file is ignored for login purposes.
   * Trustee-resolved passwords during load also start the event loop via {@link #setPassword}.
   *
   * @param dirName the path of the local directory containing the configuration properties
   * @return an array of error strings captured during properties parsing and license checking
   */
  def loadAccountProperties(dirName: String): Array[String] = {
    logger.info("loadAccountProperties: " + dirName)

    resetAccountProperties()

    val errors = new ListBuffer[String]()

    try {
      try {
        readAccountConfigurationFromDirectory(dirName, errors)
      }
      catch {
        case t: IOException => errors += s"Can't loadAccountProperties: ${t.getMessage}"
      }

      resolveTrusteeAndPassword().foreach(e => errors += e)
      // License gate at property load — independent of setPassword / UI (HSM has no local private key).
      AccountLicensing.refresh(this)
      startEventLoopAfterPropertiesLoad()

      errors.foreach { error => logger.warn(error) }

      errors.toArray
    } catch {
      case e: SecurityException =>
        abandonFailedLogin()
        throw e
    }
  }

  /**
   * Loads user properties and an encrypted private key PEM from text strings, initializing handlers.
   *
   * See {@link #loadAccountProperties} for when the event loop starts.
   *
   * @param userProperties the raw properties file configuration text
   * @param privateKeyEncrypted the encrypted RSA private key PEM string
   * @return an array of captured parsing/initialization error messages
   */
  def loadAccountPropertiesFromText(userProperties: String, privateKeyEncrypted: String): Array[String] = {
    logger.info("loadAccountProperties ... ")

    resetAccountProperties()

    val errors = new ListBuffer[String]()

    try {
      try {
        userProps.load(new StringReader(userProperties))
        addDefaultProps(userProps)
      }
      catch {
        case t: IOException => errors += s"Can't loadAccountProperties from ${userProperties}: ${t.getMessage}"
      }

      resolveTrusteeAndPassword().foreach(e => errors += e)
      AccountLicensing.refresh(this)

      if (userProps.size > 0 && userProps.getProperty("accounttype").endsWith("-secure")) {
        encryptedRSAPrivateKeyPEM = privateKeyEncrypted
      }

      startEventLoopAfterPropertiesLoad()

      errors.foreach { error => logger.warn(error) }

      errors.toArray
    } catch {
      case e: SecurityException =>
        abandonFailedLogin()
        throw e
    }
  }

  /**
   * Resolve Trustee properties and extract password if available.
   * Removes Password property from user properties for security (passwords must come from Trustee or setPassword()).
   * @return Optional error message if Trustee resolution fails
   */
  /**
   * Resolves properties referencing IBM Cloud Trustee CDH API and extracts passwords if applicable.
   * Cleans up plain text password references from configuration properties to maximize security.
   * 
   * @return Optional error message if Trustee attestation or resolution fails.
   */
  private def resolveTrusteeAndPassword(): Option[String] = {
    if (userProps.getProperty("Password") != null) {
      logger.warn("Security: Password property found in properties file. Removing it. " +
        "Passwords must be provided via Trustee (Trustee.Password) or set programmatically via setPassword().")
      userProps.remove("Password")
    }

    val trusteeResolver = new TrusteePropertyResolver(userProps)
    val error = Try {
      trusteeResolver.resolveTrusteeProperties()
    } match {
      case Success(_) => None
      case Failure(e) => Some(s"Failed to resolve Trustee properties: ${e.getMessage}")
    }

    val password = trusteeResolver.extractPassword()
    if (password != null) {
      setPassword(password.toCharArray)
    }

    error
  }

  private def resetAccountProperties() = {
    // Always stop the poller first. cleanUserProperties() may have already cleared
    // userProps; if we gated stop on props.size we would leave an interrupted-but-alive
    // thread and startEventLoop could spawn a duplicate on the next login.
    stopEventLoop()

    if (userProps.size() > 0) {

      userProps.clear
      userMsgs.clear
      userNameToSigningPublicKey.clear
      caches.invalidateAll()

      secureCloudFileSystemModel.usersMetadata.clear

      myFileSystemModel = null
      myCloudObjectHandler = null
      myCloudMsgsHandler = null
      myCloudHSMHandler = null
      hpcsKeyManager = null
      cachedHPCSPublicKeyResolved = false
      cachedHPCSPublicKey = None
      AccountLicensing.reset(this)

      accountPassword = null
      accountPasswordNextExpiredTime = 0L

      encryptedRSAPrivateKeyPEM = null
      encryptedKyberPrivateKeyPEM = null
      encryptedDilithiumPrivateKeyPEM = null
    }
  }

  private def readAccountConfigurationFromDirectory(dirName: String, errors: ListBuffer[String]) = {

    val directory = new File(dirName)
    accountDir = directory.getAbsolutePath

    val allFiles = getListOfFiles(directory)

    // stores last properties files name
    var tmp: String = null

    allFiles filter { _.getName.endsWith("user.properties") } map { file =>
      tmp = file.getAbsolutePath
      val fr = new FileReader(file.getAbsolutePath)

      userProps.load(fr)
      addDefaultProps(userProps)

      fr.close
    } size match {
      case 0 => logger.warn(s"No *user.properties file was found at ${directory.getAbsolutePath}")
      case 1 => { currentPropertyFileName = tmp; logger.debug(s"One *user.properties file was found at ${directory.getAbsolutePath}") }
      case _ => errors += s"More than one *user.properties files were found at ${directory.getAbsolutePath}"
    }

    if (userProps.size > 0 && userProps.getProperty("accounttype").endsWith("-secure")) {

      // load properties for hashicorp.vault if needed
      if (userProps.getProperty("password-source") == "hashicorp.vault") {
        allFiles filter { _.getName.endsWith("hashicorp.vault.properties") } map { file =>
          tmp = file.getAbsolutePath
          val fr = new FileReader(file.getAbsolutePath)

          userProps.load(fr)
          addDefaultProps(userProps)

          fr.close
        } size match {
          case 0 => errors += s"No *hashicorp.vault.properties file was found at ${directory.getAbsolutePath}"
          case 1 => { currentPropertyFileName = tmp; logger.debug(s"One *hashicorp.vault.properties file was found at ${directory.getAbsolutePath}") }
          case _ => errors += s"More than one *hashicorp.vault.properties files were found at ${directory.getAbsolutePath}"
        }
      }

      if (userProps.getProperty("metadata-encryption") == "RSA") {
        // load Private key (exclude org-ca-private.key — org CA signing material, not the user key)
        allFiles.filter { f =>
            val n = f.getName
            n.endsWith("private.key") && n != "org-ca-private.key"
          }
          .map { file => encryptedRSAPrivateKeyPEM = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath)), "UTF-8") }
          .size match {
          case 0 => logger.warn(s"No *private.key file was found at ${directory.getAbsolutePath}")
          case 1 => logger.debug(s"One *private.key file was found at ${directory.getAbsolutePath}")
          case _ => errors += s"More than one *private.key files were found at ${directory.getAbsolutePath}. \n\nIf this is PQC account, make sure that metadata-encryption=PQC at the account properties."
        }
      }
      else if (userProps.getProperty("metadata-encryption") == "PQC") {

        // load Private key
        allFiles.filter {
            _.getName.endsWith("kyber_private.key")
          }
          .map { file => encryptedKyberPrivateKeyPEM = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath)), "UTF-8") }
          .size match {
          case 0 => logger.warn(s"No *kyber_private.key file was found at ${directory.getAbsolutePath}")
          case 1 => logger.debug(s"One *kyber_private.key file was found at ${directory.getAbsolutePath}")
          case _ => errors += s"More than one *kyber_private.key files were found at ${directory.getAbsolutePath}"
        }

        // load Private key
        allFiles.filter {
            _.getName.endsWith("dilithium_private.key")
          }
          .map { file => encryptedDilithiumPrivateKeyPEM = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath)), "UTF-8") }
          .size match {
          case 0 => logger.warn(s"No *dilithium_private.key file was found at ${directory.getAbsolutePath}")
          case 1 => logger.debug(s"One *dilithium_private.key file was found at ${directory.getAbsolutePath}")
          case _ => errors += s"More than one *dilithium_private.key files were found at ${directory.getAbsolutePath}"
        }

      }

  
  // load altastata public key
  //      allFiles filter { _.getName.endsWith("altastata_public.key") } map { file => ALTASTATA_PUBLIC_KEY_FILE = file.getAbsolutePath } size match {
  //        case 0 => errors += s"No *altastata_public.key file was found at ${directory.getAbsolutePath}"
  //        case 1 => logger.debug(s"One *altastata_public.key file was found at ${directory.getAbsolutePath}")
  //        case _ => errors += s"More than one *altastata_public.key files were found at ${directory.getAbsolutePath}"
  //      }              
    }    
  }
  
  /**
   * Persists the current configuration properties back to the local active configuration properties file.
   *
   * @param comments standard header comments to prepend to the properties file
   */
  def saveCurrentProprtiesFile(comments: String) = {
    logger.info(s"currentPropertyFileName: ${currentPropertyFileName}")
    val fs = new FileOutputStream(currentPropertyFileName)
    try {
      userProps.store(fs, comments)
    } finally {
      fs.close()
    }
    FileSecurity.restrictToOwner(Paths.get(currentPropertyFileName))
  }

  /**
   * The regex pattern defining which file types should be compressed before upload (e.g. ".*.(txt|csv)").
   */
  def compressPattern: String = {
    if (userProps.getProperty("compresstypes") == null)
      return ""

    userProps.getProperty("compresstypes")
  }

  /**
   * Checks whether older lifecycle versions of a file should be pruned/deleted from storage upon upload.
   *
   * @return true to delete old versions; false to retain full historical versions
   */
  def isDeletePreviousVersionOnUpload(): Boolean = {
    Try(userProps.getProperty("delete-previous-on-upload").toBoolean).getOrElse(false)
  }

  /**
   * The configured identifier name for the active media player component engine.
   *
   * @return the media player engine name string
   */
  def getMediaPlayerName(): String = {
    Try(userProps.getProperty("media-player").toString).getOrElse("javafx.media")
  }

  /**
   * Checks whether directory pathnames/filenames should be cryptographically obfuscated/encrypted.
   *
   * @return true to encrypt logical pathnames; false to retain plaintext file hierarchies
   */
  def isEncryptNames: Boolean = {
    Try(userProps.getProperty("encrypt-names").toBoolean).getOrElse(false)
  }

  /**
   * The polling frequency in milliseconds to check the cloud changes/events message queue.
   */
  def sqsInterval: Long = {
    Try(userProps.getProperty("sqs-interval").toLong).getOrElse(5L) * 1000
  }

  /**
   * The duration in milliseconds that must elapse before a cached master password entry expires.
   */
  def passwordTimeoutInterval: Long = {
    Try(userProps.getProperty("password-timeout-interval").toLong).getOrElse(15 * 60L) * 1000 // 15 mins default
  }

  /**
   * Checks whether this account is configured for Enterprise Custodian mode
   * ({@code enterprise-custodian-mode=true} in user properties).
   *
   * @return true if running in custodian mode; false otherwise
   */
  def isCustodianMode: Boolean = {
    Try(userProps.getProperty("enterprise-custodian-mode").toBoolean).getOrElse(false)
  }

  /**
   * Per-account total budget for the in-process ciphertext cache (encrypted metadata,
   * single-chunk small-file ciphertext, encrypted DATA_PROPERTIES attributes).
   * Property: `cache-size-bytes`. Default: 280 MiB.
   */
  def cacheSizeBytes: Long = {
    Try(userProps.getProperty("cache-size-bytes").toLong).getOrElse(280L * 1024L * 1024L)
  }

  /**
   * @return The default is True
   */
  def generateUUIDForDataLocator: Boolean = {
    Try(userProps.getProperty("generate-uuid-for-datalocator").toBoolean).getOrElse(true)
  }

  /**
   * @return The default is False
   */
  def generateUUIDForDataAttributesPath: Boolean = {
    Try(userProps.getProperty("generate-uuid-for-dataattributes-path").toBoolean).getOrElse(false)
  }
  
  /**
   * Retrieves the path to the loaded local account configuration directory.
   *
   * @return the local account directory path string, or null
   */
  def getAccountDir(): String = accountDir

  /** Bind account folder so `license.jwt` / `org-ca.pem` resolve (e.g. Cognito setup from props text). */
  def setAccountDir(dirName: String): Unit = {
    accountDir = Option(dirName).map(_.trim).filter(_.nonEmpty).map(d => new File(d).getAbsolutePath).orNull
  }

  /**
   * HPCS public key only when we have the certificate (AWS userdata).
   * We do not read the public key from anywhere else; we only decrypt until we have the certificate.
   */
  def getHPCSPublicKeyOption(): Option[PublicKey] = {
    if (cachedHPCSPublicKeyResolved) return cachedHPCSPublicKey
    var result: Option[PublicKey] = None
    if (userProps.getProperty("key-protection") == "HPCS" && hpcsKeyManager != null && myFileSystemModel != null) {
      try {
        val ud = myFileSystemModel.retrieveUserdata(MY_USER)
        if (ud.publicKeyCert.isDefined) {
          val (pk, _) = AsymmetricKeysGenerator.decodeRSAPublicKeyFromCertForUser(
            ud.publicKeyCert.get.getBytes("UTF-8"),
            MY_USER,
            checkEndDate = false,
            this
          )
          result = Some(pk)
        }
      } catch { case e: Exception =>
        logger.warn(s"Failed to resolve HPCS public key for user $MY_USER: ${e.getMessage}")
      }
    }
    cachedHPCSPublicKey = result
    cachedHPCSPublicKeyResolved = true
    result
  }

  private def getListOfFiles(dir: File): List[File] = try {
    dir.listFiles.filter(_.isFile).toList
  }
  catch {
    case t: Throwable => logger.error(s"Account: cannot list files at ${dir}"); List.empty[File]
  }

  /**
   * True when a session password is cached in memory after {@code setPassword}.
   * Unlike {@link #getPassword}, this does not contact Vault or reload keys.
   */
  def isPasswordSet: Boolean = accountPassword != null

  /**
   * Retrieves the user's master password or PIN used to unlock local asymmetric keys.
   *
   * The method supports multiple backends configured via `password-source`:
   * - `hashicorp.vault`: Reads the password dynamically from a HashiCorp Vault cubbyhole.
   * - Default: Assumes the password was provided via UI or command line and cached in memory.
   *
   * For cloud-managed keys (HSM/HPCS) or managed identities (`accounttype` == "amazon-s3" or "azure"), 
   * this may return an empty array as no local private key unlocking is necessary.
   *
   * @return A character array containing the user's password.
   */
  def getPassword(): Array[Char] = {
    // see README_hashivault.txt how to configure hashicorp approle and cubbyhole
    if (userProps.getProperty("password-source") == "hashicorp.vault") {
      val vaultConfig = new VaultConfig(userProps.getProperty("hashicorp.vault.url"),
                                        userProps.getProperty("hashicorp.vault.token"))

      val vault = new Vault(vaultConfig)
      
      accountPasswordNextExpiredTime = System.currentTimeMillis + 365 * 24 * 60 * 60 * 1000 
      accountPassword = vault.logical().read("cubbyhole/" + MY_USER).getData().get("passwordkey").toCharArray
      
      // Software RSA only — HSM/HPCS have no local PEM to verify against Vault password.
      if (requiresLocalPassword) {
        AsymmetricKeysGenerator.loadRSAPrivateKey(accountPassword)(this)
      }
      return accountPassword
    }
    
    if (userProps.getProperty("accounttype") == "amazon-s3" || userProps.getProperty("accounttype") == "azure") {
      return "".toCharArray
    }

    // Default: if it should be provided via UI
    if (accountPassword != null) {
      updateAccountPasswordNextExpiredTime()
    }

    return accountPassword
  }

  /**
   * Resets and updates the timestamp when the cached password is considered expired and needs re-verification.
   */
  def updateAccountPasswordNextExpiredTime() =  {
    accountPasswordNextExpiredTime = System.currentTimeMillis + passwordTimeoutInterval
  }

  /** Initialize HPCS first when key-protection=HPCS; other managers may need it to decrypt access keys. */
  private def ensureHPCSInitialized(): Unit = {
    if (userProps.getProperty("key-protection") == "HPCS" && hpcsKeyManager == null) {
      hpcsKeyManager = new IBMHPCSKeyManager()(this)
      logger.info("HPCS key manager initialized for account (key-protection=HPCS)")
    }
  }

  /** Enrollment only: unlock keys/handlers without login identity gate. */
  private[altastata] def initForEnrollment(password: Array[Char]): Unit = this.synchronized {
    if (password == null) return
    try {
      unlockKeysAndHandlers(password)
    } catch {
      case e: SecurityException =>
        abandonFailedLogin()
        throw e
    }
  }

  /**
   * Initializes or updates the account with the provided master password.
   *
   * This is a critical security boundary. Upon receiving the password, this method:
   * 1. Resets any previously loaded HPCS configuration.
   * 2. Attempts to load and decrypt the local asymmetric private keys (RSA or PQC).
   * 3. Refreshes the organizational license entitlement.
   * 4. Extends the password expiration timer.
   * 5. Reinitializes all core file system models, cloud handlers, and HSM connectors to use the newly unlocked keys.
   * 6. Verifies the user's identity certificate (unless bypassed by HSM/HPCS configurations).
   * 7. Starts the change-queue event loop if it is not already running.
   *
   * If any cryptographic verification or license check fails, the login is aborted (`abandonFailedLogin`), 
   * and a `SecurityException` is thrown.
   *
   * {@code setPassword(null)} clears a timed-out session password and does not restart the loop.
   * HSM+HPCS may use {@code setPassword(null)} to initialize hardware managers and start the loop.
   *
   * @param password The character array containing the user's master password/PIN.
   * @throws SecurityException if the password is incorrect, keys cannot be loaded, or identity verification fails.
   */
  def setPassword(password: Array[Char]): Unit = this.synchronized {
    if (password != null) {
      try {
        unlockKeysAndHandlers(password)

        // RSA/PQC: block login until MY_USER cert verifies (Community = AltaStata-signed).
        // HSM/HPCS: entitlement already enforced in AccountLicensing.refresh at property load.
        val metaEnc = userProps.getProperty("metadata-encryption")
        val keyProt = userProps.getProperty("key-protection")
        if (metaEnc != "HSM" && keyProt != "HPCS") {
          secureCloudFileSystemModel.verifyUserIdentity()
        }

        import scala.concurrent.ExecutionContext.Implicits.global

        Future {
          secureCloudFileSystemModel.preloadUsers()
        }

        // Session is unlocked — start (or no-op if already running) the change-queue poller.
        startEventLoop()
      } catch {
        case e: SecurityException =>
          abandonFailedLogin()
          throw e
      }

    } else {
      accountPassword = password
      accountPasswordNextExpiredTime = System.currentTimeMillis
      // Hardware passwordless init (no local PEM passphrase). key-protection=HPCS is
      // primary and applies with metadata-encryption HSM, RSA, or PQC; metadata-encryption=HSM
      // alone is also passwordless. Otherwise this is a software-key timeout clear.
      if (isHardwarePasswordlessAccount) {
        try {
          AccountLicensing.refresh(this)
          ensureHPCSInitialized()
          myFileSystemModel = null
          fileSystemModel
          cloudObjectHandler
          cloudMsgsHandler
          cloudHSMHandler
          updateLoggingLevels()
          startEventLoop()
        } catch {
          case e: SecurityException =>
            abandonFailedLogin()
            throw e
        }
      }
      // else: password clear on timeout — leave any running poller idle until the next login.
    }
  }

  /** Shared by {@link #setPassword} and {@link #initForEnrollment}. */
  private def unlockKeysAndHandlers(password: Array[Char]): Unit = {
    hpcsKeyManager = null
    cachedHPCSPublicKeyResolved = false
    cachedHPCSPublicKey = None
    // Initialize HPCS before any other managers; their access keys may be encrypted and HPCS decrypts them
    val hpcsProtected = userProps.getProperty("key-protection") == "HPCS"
    if (hpcsProtected) {
      hpcsKeyManager = new IBMHPCSKeyManager()(this)
      logger.info("HPCS key manager initialized for account (key-protection=HPCS)")
    }

    userProps.getProperty("metadata-encryption") match {
      case "RSA" => {
        // key-protection=HPCS wins: signing/unwrap keys live in HPCS — skip local PEM.
        if (!hpcsProtected) {
          AsymmetricKeysGenerator.loadRSAPrivateKey(password)(this)
        } else {
          logger.info("HPCS+RSA: skipping local RSA private key load")
        }
      }
      case "PQC" => {
        // Same as RSA: HPCS is primary; local Kyber/Dilithium PEMs are not used (future path).
        if (!hpcsProtected) {
          AsymmetricKeysGenerator.loadPQCPrivateKey("KYBER", password)(this)
          AsymmetricKeysGenerator.loadPQCPrivateKey("DILITHIUM", password)(this)
        } else {
          logger.info("HPCS+PQC: skipping local PQC private key load")
        }
      }
      case "HSM" => {
        // Cloud HSM metadata path: no local private key to load or check
        logger.info("HSM user - skipping private key loading (uses hardware security module)")
        if (!hpcsProtected) {
          hpcsKeyManager = null
        }
      }
    }

    AccountLicensing.refresh(this)

    accountPasswordNextExpiredTime = System.currentTimeMillis + passwordTimeoutInterval
    accountPassword = password

    myFileSystemModel = null
    fileSystemModel
    cloudObjectHandler
    cloudMsgsHandler
    cloudHSMHandler
    updateLoggingLevels()

    logger.info("All models and handlers are up")
  }

  /**
   * Gets the cached Cognito password string.
   *
   * @return the Cognito password string, or null
   */
  def getCognitoPassword(): String = cognitoPassword
  
  /**
   * Sets the Cognito password for authentication.
   *
   * @param password the Cognito password string to set
   */
  def setCognitoPassword(password: String): Unit = {
       cognitoPassword = password
  }
  
  /**
   * Encrypts a plaintext configuration property value using the designated public key and algorithm type, returning Base64.
   *
   * @param value the plaintext property string to encrypt
   * @param publicKey the public key to encrypt with
   * @param encryptionType the encryption type (RSA or PQC)
   * @return the Base64-encoded encrypted string representation
   */
  def encryptProperty(value: String, publicKey: PublicKey, encryptionType: String = userProps.getProperty("metadata-encryption")): String = {
    if (value == null) {
      throw new IllegalArgumentException("Cannot encrypt null value")
    }
    
    val encryptedProperty = encryptionType match {
      case "RSA" => {
        secureCloudFileSystemModel.encryptArrayWithRSA(value.getBytes, publicKey, Constants.RSA_OAEP)
      }
      case "PQC" => {
        secureCloudFileSystemModel.encryptArrayWithKyber(value.getBytes, publicKey)
      }
    }

    Base64.getEncoder().encodeToString(encryptedProperty)
  }

  /**
   * Retrieves a property value by its configuration key from user properties.
   *
   * @param key the property key to lookup
   * @return the property value string, or null
   */
  def getProperty(key: String): String = userProps.getProperty(key)

  /**
   * Updates SQS notification polling interval and restarts the event thread if it wasn't running.
   *
   * @param interval the new polling interval in seconds
   */
  def checkNotifications(interval: String): Unit = {
    val prevSQSInterval = sqsInterval
    
    userProps.setProperty("sqs-interval", interval)
    
    // launch if was not running
    if (prevSQSInterval <= 0) {
      startEventLoop()
    }
    else {
      logger.info(s"checkNotifications cannot start secureCloudEventProcessor")
    }

    logger.info(s"checkNotifications interval: ${interval}, prevSQSInterval: ${prevSQSInterval}")
  }

  /**
   * Starts the background change-queue poller if it is not already running.
   *
   * Normally invoked from {@link #setPassword}. For HSM/HPCS accounts, also
   * invoked automatically at the end of property load; if the session is not
   * yet unlocked, this method unlocks hardware managers first.
   */
  def startEventLoop(): Unit = this.synchronized {
    if (isHardwarePasswordlessAccount && !isPasswordSet) {
      logger.info(
        s"startEventLoop: unlocking HSM/HPCS session " +
          s"(metadata-encryption=${userProps.getProperty("metadata-encryption")}, " +
          s"key-protection=${userProps.getProperty("key-protection")})")
      // setPassword starts the loop on success — avoid starting twice here.
      setPassword(Array.emptyCharArray)
      return
    }

    if (threadSQS != null && threadSQS.isAlive) return
    if (sqsInterval > 0 && userProps.size > 0 &&
        userProps.getProperty("accounttype").endsWith("-secure") && MY_USER != "admin") {
      logger.info("starting secureCloudEventProcessor")
      threadSQS = new Thread(secureCloudEventProcessor.runnableSQS)
      threadSQS.start()
    } else {
      logger.info("cannot start secureCloudEventProcessor")
    }
  }

  /** Interrupt and drop the change-queue poller reference, if any. */
  private def stopEventLoop(): Unit = {
    // Clear the reference under the lock, but join outside it — the dying poller
    // (and cloud I/O it may still be in) must not block other Account.synchronized callers.
    val t = this.synchronized {
      val cur = threadSQS
      threadSQS = null
      cur
    }
    if (t != null && t.isAlive) {
      Try(t.interrupt())
      // Brief join so a following startEventLoop does not overlap the dying thread
      // (sleep/list in runnableSQS can outlive interrupt by a few ms).
      Try(t.join(1000))
    }
  }

  /**
   * No local private-key passphrase:
   * - {@code key-protection=HPCS} (primary; pairs with metadata-encryption HSM, RSA, or PQC)
   * - or {@code metadata-encryption=HSM} without software PEM keys
   *
   * A stray {@code private.key} / PQC PEM on disk is ignored for login decisions.
   */
  private def isHardwarePasswordlessAccount: Boolean = {
    userProps.getProperty("key-protection") == "HPCS" ||
      userProps.getProperty("metadata-encryption") == "HSM"
  }

  /**
   * True when login requires {@link #setPassword} with a local private-key
   * passphrase (RSA/PQC software keys). False for HSM/HPCS — driven by account
   * properties, not by whether a PEM file happens to be present.
   */
  def requiresLocalPassword: Boolean = !isHardwarePasswordlessAccount

  /**
   * After property load: start the poller when Trustee already set a password,
   * or when this is an HSM/HPCS account (auto startEventLoop). RSA/PQC without
   * password waits for an explicit {@link #setPassword}.
   */
  private def startEventLoopAfterPropertiesLoad(): Unit = {
    if (isPasswordSet || isHardwarePasswordlessAccount) {
      startEventLoop()
    }
  }

  /** Decrypt a Base64-encoded value (e.g. custodian decrypting SAS from UserAttributesForCustodian). */
  def decryptPropertyValue(encryptedBase64: String): String = {
    if (MY_USER == "admin" || userProps.getProperty("metadata-encryption") == "HSM") {
      return encryptedBase64
    }
    val decoded = Base64.getDecoder.decode(encryptedBase64.getBytes(StandardCharsets.UTF_8))
    val decrypted = userProps.getProperty("metadata-encryption") match {
      case "RSA" => secureCloudFileSystemModel.decryptArrayWithRSA(decoded, Constants.RSA_OAEP)(this)
      case "PQC" => secureCloudFileSystemModel.decryptArrayWithKyber(decoded)(this)
      case _ => decoded
    }
    new String(decrypted, StandardCharsets.UTF_8)
  }

  /**
   * Retrieves a property value and decrypts it using our private key (if encrypted).
   *
   * @param key the property key to lookup
   * @return the decrypted property plaintext string value
   */
  def getAndDecryptProperty(key: String): String = {
    val value = userProps.getProperty(key)
    decryptPropertyValue(value)
  }

  /**
   * Evaluates and updates dynamic logging levels based on the user's configuration properties.
   *
   * It scans the loaded `userProps` for keys matching `logging.level.*` (e.g., `logging.level.root` 
   * or `logging.level.com.altastata.filesystem`) and adjusts the underlying Logback `LoggerContext` 
   * in real-time. This allows administrators or users to tune verbosity without restarting the application.
   */
  def updateLoggingLevels(): Unit = {
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    
    // Update root logger
    val rootLevel = userProps.getProperty("logging.level.root")
    if (rootLevel != null) {
      val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      rootLogger.setLevel(Level.toLevel(rootLevel))
    }
    
    // Update specific loggers
    val keys = userProps.propertyNames()
    while (keys.hasMoreElements) {
      val key = keys.nextElement().toString
      if (key.startsWith("logging.level.") && key != "logging.level.root") {
        val loggerName = key.substring("logging.level.".length)
        val level = userProps.getProperty(key)
        val logger = loggerContext.getLogger(loggerName)
        logger.setLevel(Level.toLevel(level))
      }
    }
  }

}

object Account {
  val altaStataConfig = new AltaStataConfig()

  /**
   * The home directory path where all local user account profile subfolders reside.
   */
  def ALTASTATA_ACCOUNTS_HOME = altaStataConfig.ALTASTATA_HOME + File.separator + "accounts"
}
