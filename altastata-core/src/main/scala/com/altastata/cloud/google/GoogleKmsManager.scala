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

package com.altastata.cloud.google

import com.altastata.filesystem.securecloud.CloudHSMHandler
import com.altastata.utils.Account
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose
import com.google.cloud.kms.v1.CryptoKeyVersion.{CryptoKeyVersionAlgorithm, CryptoKeyVersionState}
import com.google.cloud.kms.v1._
import com.google.iam.v1.{Binding, GetIamPolicyRequest, Policy, SetIamPolicyRequest}
import com.google.protobuf.ByteString
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
 * GCP Cloud KMS implementation of [[CloudHSMHandler]] (AWS AmazonKmsManager parity).
 *
 * Creates two symmetric ENCRYPT_DECRYPT keys per user (data wrap + signature wrap)
 * and encrypts/decrypts with a nested key chain (comma-separated resource names).
 *
 * Required account properties:
 * - google-project
 * - kms-location (e.g. europe-west1)
 * - kms-key-ring (e.g. altastata)
 * - credentials (service-account JSON; decrypted for non-admin users)
 */
class GoogleKmsManager(implicit account: Account) extends CloudHSMHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  private lazy val projectId: String =
    requiredProp("google-project")

  private lazy val locationId: String =
    requiredProp("kms-location")

  private lazy val keyRingId: String =
    Option(account.getProperty("kms-key-ring")).filter(_.nonEmpty).getOrElse("altastata")

  private lazy val credentialsJson: String =
    if (account.MY_USER == "admin") account.getProperty("credentials")
    else account.getAndDecryptProperty("credentials")

  private lazy val kms: KeyManagementServiceClient = {
    val creds = ServiceAccountCredentials.fromStream(
      IOUtils.toInputStream(credentialsJson, "UTF-8"))
    KeyManagementServiceClient.create(
      KeyManagementServiceSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build())
  }

  private def requiredProp(name: String): String = {
    val v = account.getProperty(name)
    if (v == null || v.trim.isEmpty)
      throw new IllegalStateException(s"Missing required property for Google Cloud KMS: $name")
    v.trim
  }

  private def keyRingName: KeyRingName = KeyRingName.of(projectId, locationId, keyRingId)

  private def keyRingResourceName: String =
    s"projects/$projectId/locations/$locationId/keyRings/$keyRingId"

  /** Ensure the configured key ring exists (create if missing). */
  private def ensureKeyRing(): Unit = {
    try {
      kms.getKeyRing(keyRingResourceName)
    } catch {
      case _: Exception =>
        try {
          val parent = LocationName.of(projectId, locationId).toString
          kms.createKeyRing(parent, keyRingId, KeyRing.newBuilder().build())
          logger.info(s"Created Cloud KMS key ring: $keyRingResourceName")
        } catch {
          case e: Exception if isAlreadyExists(e) =>
            logger.debug(s"Key ring already exists: $keyRingResourceName")
          case e: Exception =>
            throw new RuntimeException(s"Failed to create key ring $keyRingResourceName", e)
        }
    }
  }

  private def isAlreadyExists(e: Throwable): Boolean = {
    var t: Throwable = e
    while (t != null) {
      val msg = Option(t.getMessage).getOrElse("")
      if (t.getClass.getName.contains("AlreadyExists") || msg.contains("ALREADY_EXISTS") || msg.contains("Already exists"))
        return true
      t = t.getCause
    }
    false
  }

  override def createHSMKeysForUser(userName: String, userType: String): (String, String) = {
    ensureKeyRing()
    val baseId = GoogleKmsManager.sanitizeKeyId(userName)
    val encryptKey = createSymmetricKey(s"$baseId-encrypt", s"AltaStata HSM encrypt key for $userName ($userType)")
    val signKey = createSymmetricKey(s"$baseId-sign", s"AltaStata HSM sign key for $userName ($userType)")

    // Encrypt Key: user's SA gets EncrypterDecrypter; allAuthenticatedUsers get Encrypter (share with user)
    grantKmsRoles(encryptKey, userName, Seq("roles/cloudkms.cryptoKeyEncrypterDecrypter"), Seq("roles/cloudkms.cryptoKeyEncrypter"))

    // Sign Key: user's SA gets EncrypterDecrypter; allAuthenticatedUsers get Decrypter (verify signature)
    grantKmsRoles(signKey, userName, Seq("roles/cloudkms.cryptoKeyEncrypterDecrypter"), Seq("roles/cloudkms.cryptoKeyDecrypter"))

    (encryptKey, signKey)
  }

  /**
   * Per-user service account email for KMS IAM bindings (as-{org}-{user}, max 30 chars).
   * Uses AltaStata SA naming from GoogleAdmin, not client_email from the admin credentials JSON.
   */
  private def expectedUserServiceAccountEmail(userName: String, organization: String, project: String): Option[String] = {
    var saId = s"as-${organization.toLowerCase}-${userName.toLowerCase}"
    if (saId.length > 30) {
      saId = saId.substring(0, 30)
      if (saId.endsWith("-")) saId = saId.substring(0, 29)
    }
    Some(s"$saId@$project.iam.gserviceaccount.com")
  }

  private def expectedUserServiceAccountEmail(userName: String): Option[String] =
    expectedUserServiceAccountEmail(userName, account.ORGANIZATION, projectId)

  private def createSymmetricKey(cryptoKeyId: String, description: String): String = {
    val key =
      CryptoKey.newBuilder()
        .setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT)
        .setVersionTemplate(
          CryptoKeyVersionTemplate.newBuilder()
            .setAlgorithm(CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION))
        .build()

    for (i <- 0 until 10) {
      try {
        val created = kms.createCryptoKey(keyRingName, cryptoKeyId, key)
        logger.info(s"Created Cloud KMS key: ${created.getName} ($description)")
        return created.getName
      } catch {
        case e: Exception if isAlreadyExists(e) =>
          val existing = CryptoKeyName.of(projectId, locationId, keyRingId, cryptoKeyId).toString
          logger.info(s"Cloud KMS key already exists: $existing")
          ensureEnabledPrimaryVersion(existing)
          return existing
        case e: Exception =>
          logger.warn(s"Cannot create Cloud KMS key $cryptoKeyId attempt $i: ${e.getMessage}")
          Thread.sleep(2000)
      }
    }
    throw new RuntimeException(s"Failed to create Cloud KMS key: $cryptoKeyId")
  }

  /** After teardown, crypto keys may exist with DESTROY_SCHEDULED primary versions; create a fresh one. */
  private def ensureEnabledPrimaryVersion(cryptoKeyResourceName: String): Unit = {
    val key = kms.getCryptoKey(cryptoKeyResourceName)
    val primaryState =
      if (key.hasPrimary) key.getPrimary.getState
      else CryptoKeyVersionState.CRYPTO_KEY_VERSION_STATE_UNSPECIFIED

    if (primaryState == CryptoKeyVersionState.ENABLED) return

    logger.info(s"Creating new Cloud KMS key version for $cryptoKeyResourceName (primary state: $primaryState)")
    val version = kms.createCryptoKeyVersion(
      cryptoKeyResourceName,
      CryptoKeyVersion.newBuilder().build())
    val versionId = version.getName.split("/").last
    kms.updateCryptoKeyPrimaryVersion(
      UpdateCryptoKeyPrimaryVersionRequest.newBuilder()
        .setName(cryptoKeyResourceName)
        .setCryptoKeyVersionId(versionId)
        .build())
    logger.info(s"Set primary Cloud KMS key version $versionId on $cryptoKeyResourceName")
  }

  /**
   * Grant specified roles to the user's SA and public/custodian roles on a crypto key.
   */
  private def grantKmsRoles(
      cryptoKeyResourceName: String,
      userName: String,
      userRoles: Seq[String],
      publicRoles: Seq[String]): Unit = {
    val userMembers = scala.collection.mutable.LinkedHashSet.empty[String]
    expectedUserServiceAccountEmail(userName).foreach(email => userMembers += s"serviceAccount:$email")
    val custodianEmail = Option(account.getProperty("kms-custodian-sa-email"))
    custodianEmail.filter(_.nonEmpty).foreach(email => userMembers += s"serviceAccount:$email")
    
    val publicMembers = Seq("allAuthenticatedUsers")

    if (userMembers.isEmpty) {
      logger.debug(s"Skipping IAM grant on $cryptoKeyResourceName (no SA email)")
      return
    }

    try {
      val policy = kms.getIamPolicy(
        GetIamPolicyRequest.newBuilder().setResource(cryptoKeyResourceName).build())
      
      val updatedBuilder = Policy.newBuilder(policy).clearBindings()
      
      // We will rebuild bindings from scratch to ensure desired state
      val allDesiredRoles = (userRoles ++ publicRoles).toSet
      
      // Add bindings for roles we don't manage
      policy.getBindingsList.asScala.filterNot(b => allDesiredRoles.contains(b.getRole)).foreach(updatedBuilder.addBindings)
      
      // Add bindings for user roles
      userRoles.foreach { role =>
        val existingMembers = policy.getBindingsList.asScala.find(_.getRole == role).map(_.getMembersList.asScala.toSet).getOrElse(Set.empty)
        val newMembers = existingMembers ++ userMembers
        updatedBuilder.addBindings(Binding.newBuilder().setRole(role).addAllMembers(newMembers.asJava).build())
      }
      
      // Add bindings for public roles
      publicRoles.foreach { role =>
        val existingMembers = policy.getBindingsList.asScala.find(_.getRole == role).map(_.getMembersList.asScala.toSet).getOrElse(Set.empty)
        val newMembers = existingMembers ++ publicMembers
        updatedBuilder.addBindings(Binding.newBuilder().setRole(role).addAllMembers(newMembers.asJava).build())
      }

      kms.setIamPolicy(
        SetIamPolicyRequest.newBuilder()
          .setResource(cryptoKeyResourceName)
          .setPolicy(updatedBuilder.build())
          .build())
      logger.info(s"Granted specific roles on $cryptoKeyResourceName")
    } catch {
      case e: Exception =>
        logger.warn(s"Could not set IAM on $cryptoKeyResourceName: ${e.getMessage}")
    }
  }

  /**
   * Encrypts a serialized byte array using a comma-separated keychain of Google Cloud KMS key URIs.
   *
   * @param serialized the plaintext bytes to encrypt
   * @param keys a comma-separated sequence of Google KMS key resources
   * @return the encrypted ciphertext byte array
   */
  def encryptObjectWithHSM(serialized: Array[Byte], keys: String): Array[Byte] = {
    val keyList = GoogleKmsManager.parseKeyChain(keys)
    GoogleKmsManager.encryptChain(serialized, keyList, encryptOnce)
  }

  /**
   * Decrypts a ciphertext byte array using a comma-separated keychain of Google Cloud KMS key URIs.
   *
   * @param encrypted the ciphertext bytes to decrypt
   * @param keys a comma-separated sequence of Google KMS key resources
   * @return the decrypted plaintext bytes
   */
  def decryptObjectWithHSM(encrypted: Array[Byte], keys: String): Array[Byte] = {
    val keyList = GoogleKmsManager.parseKeyChain(keys)
    GoogleKmsManager.decryptChain(encrypted, keyList, decryptOnce)
  }

  private def encryptOnce(keyResourceName: String, plaintext: Array[Byte]): Array[Byte] = {
    val response = kms.encrypt(
      EncryptRequest.newBuilder()
        .setName(keyResourceName)
        .setPlaintext(ByteString.copyFrom(plaintext))
        .build())
    response.getCiphertext.toByteArray
  }

  private def decryptOnce(keyResourceName: String, ciphertext: Array[Byte]): Array[Byte] = {
    val response = kms.decrypt(
      DecryptRequest.newBuilder()
        .setName(keyResourceName)
        .setCiphertext(ByteString.copyFrom(ciphertext))
        .build())
    response.getPlaintext.toByteArray
  }
}

object GoogleKmsManager {

  /** Max length for full crypto key id including "-encrypt" / "-sign" suffix (Cloud KMS limit 63). */
  private val MaxBaseKeyIdLength = 63 - "-encrypt".length

  /** Hex chars of SHA-256 over the original username appended to the sanitized id. */
  private val KeyIdHashSuffixLength = 8

  /**
   * Cloud KMS crypto key ids: letters, digits, underscore, hyphen; leave room for -encrypt/-sign.
   *
   * A short digest of the *original* username is appended so that names which collide after
   * case-folding, special-character replacement, or truncation (e.g. "Alice.User" vs
   * "alice-user") still map to distinct key ids. Existing keys are unaffected: this function
   * is only used at key creation, and the resulting id is stored in user metadata.
   */
  def sanitizeKeyId(userName: String): String = {
    val cleaned = userName.toLowerCase.replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-")
      .stripPrefix("-").stripSuffix("-")
    val base = if (cleaned.isEmpty) "user" else cleaned
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(userName.getBytes("UTF-8"))
    val suffix = digest.take(KeyIdHashSuffixLength / 2).map("%02x".format(_)).mkString
    ("as-" + base).take(MaxBaseKeyIdLength - KeyIdHashSuffixLength - 1) + "-" + suffix
  }

  /**
   * Splits a comma-separated key string into a sequence of individual trimmed key URIs.
   *
   * @param keys the comma-separated key chain string
   * @return the sequence of individual key names/URIs
   */
  def parseKeyChain(keys: String): Seq[String] =
    keys.split(",").map(_.trim).filter(_.nonEmpty).toSeq

  /**
   * Nested encrypt: apply keys left-to-right (same order as AmazonKmsManager).
   */
  def encryptChain(
      plaintext: Array[Byte],
      keyNames: Seq[String],
      encryptFn: (String, Array[Byte]) => Array[Byte]): Array[Byte] = {
    var current = plaintext
    for (key <- keyNames) {
      current = encryptFn(key, current)
    }
    current
  }

  /**
   * Nested decrypt: apply keys right-to-left, updating ciphertext each step
   * (unlike AmazonKmsManager which reuses the original blob).
   */
  def decryptChain(
      ciphertext: Array[Byte],
      keyNames: Seq[String],
      decryptFn: (String, Array[Byte]) => Array[Byte]): Array[Byte] = {
    var current = ciphertext
    for (key <- keyNames.reverse) {
      current = decryptFn(key, current)
    }
    current
  }
}
