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

package com.altastata.cloud.amazon_java2

import com.altastata.cloud.amazon_java2.templates.KMSPolicy
import com.altastata.filesystem.securecloud.CloudHSMHandler
import com.altastata.utils.Account
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.GetUserRequest
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.{CreateKeyRequest, DecryptRequest, EncryptRequest, KmsException}

import java.io.File
import java.nio.ByteBuffer
import java.time.Duration

class AmazonKmsManager(implicit account: Account) extends CloudHSMHandler {

  private val logger = LoggerFactory.getLogger(getClass)
  
  val region = Region.of(account.getProperty("kms-region"))

  val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
    .apiCallTimeout(Duration.ofSeconds(600))
    .apiCallAttemptTimeout(Duration.ofSeconds(180))
    .build()

  var amazonAccountId: String = null

  val kms: KmsClient =
    if (account.userProps.getProperty("cognito-identity-id") == null) {
          {
            val credentialsProvider: software.amazon.awssdk.auth.credentials.AwsCredentialsProvider =
              if (account.userProps.getProperty("AWSAccessKeyId") != null && account.userProps.getProperty("AWSSecretKey") != null) {
                val awsCreds =
                  if (account.userProps.getProperty("metadata-encryption") == "HSM")
                    AwsBasicCredentials.create(account.getProperty("AWSAccessKeyId"), account.getProperty("AWSSecretKey"))
                  else
                    AwsBasicCredentials.create(account.getAndDecryptProperty("AWSAccessKeyId"), account.getAndDecryptProperty("AWSSecretKey"))
                StaticCredentialsProvider.create(awsCreds)
              }
              else
                DefaultCredentialsProvider.create()

            // detect amazonAccountId
            val iamClient = IamClient.builder
              .credentialsProvider(credentialsProvider)
              .region(region)
              .build

            try {
                val response = iamClient.getUser(GetUserRequest.builder.build)
                val arn = response.user.arn
                amazonAccountId = arn.split(":")(4)
            } catch {
                case e: Exception =>
                  e.printStackTrace()
            } finally if (iamClient != null) iamClient.close()

            val client = KmsClient.builder()
              .region(region)
              .credentialsProvider(credentialsProvider)
              .overrideConfiguration(clientOverrideConfiguration)
              //.httpClient(NettyNioAsyncHttpClient.builder()
              //  .readTimeout(Duration.ofSeconds(60))
              //  .build())
              .build()

            client
          }
    }
    else {
      val altastataCognitoProvider = account.cognitoClient.getCredentialsProvider(account.MY_USER, account.getCognitoPassword)

      val client = KmsClient.builder()
        .credentialsProvider(altastataCognitoProvider)
        .region(region)
        .build()

      client
    }

  override def createHSMKeysForUser(userName: String, userType: String): (String, String) = {
    val hsmKey = createKMSKeysForUser(userName, userType, "Encrypt")
    val hsmSignKey = createKMSKeysForUser(userName, userType, "Decrypt")

    (hsmKey, hsmSignKey)
  }

  /**
   * Creates a customer managed KMS key for a specific user in AWS KMS.
   *
   * @param userName the target username associated with the key
   * @param userType the type of the user (e.g., Custodian, Client, Administrator)
   * @param othersAction the permitted cryptographic actions for other parties
   * @return the resolved AWS KMS Key ID, or null if key generation fails
   */
  def createKMSKeysForUser(userName: String, userType: String, othersAction: String): String = {
    var policyText = ""

    if (othersAction == "Encrypt")
      policyText = KMSPolicy.encryptionKeyPolicy(amazonAccountId, userType, userName, account.CUSTODIAN_USER)
    else if (othersAction == "Decrypt")
      policyText = KMSPolicy.signatureKeyPolicy(amazonAccountId, userType, userName, account.CUSTODIAN_USER)
    else logger.error("Incorrect KMSActions provided: " + othersAction)

    val req = CreateKeyRequest.builder()
      .description(s"Key for protecting critical data for: $userName; other users can only $othersAction")
      .policy(policyText)
      .build()

    for (i <- 0 until 10) {
      try {
        val result = kms.createKey(req)
        logger.info("createKeyResult: " + result)

        return result.keyMetadata().keyId()
      } catch {
        case ex: KmsException =>
          logger.warn(s"Cannot create KMS key for user: $userName attempt: $i error: ${ex.getMessage}")
          Thread.sleep(5000)
      }
    }

    null
  }

  /**
   * Encrypts a serialized byte array using a comma-separated chain of AWS KMS Key IDs.
   *
   * @param serialized the raw plaintext bytes to encrypt
   * @param keys a comma-separated list of KMS Key IDs/ARN strings
   * @return the resulting ciphertext byte array after applying the KMS key chain
   */
  def encryptObjectWithHSM(serialized: Array[Byte], keys: String): Array[Byte] = {
    val keysList = keys.split(",").map(_.trim)

    var array = serialized
    for (key <- keysList) {
      val myBytes = SdkBytes.fromByteArray(array)
      val encryptRequest = EncryptRequest
                            .builder
                            .keyId(key)
                            .plaintext(myBytes)
                            .build()

      val response = kms.encrypt(encryptRequest)

      // Get the encrypted data.
      array = response.ciphertextBlob().asByteArray()
    }

    array
  }
  
  /**
   * Decrypts a ciphertext byte array using a comma-separated chain of AWS KMS Key IDs in reverse.
   *
   * @param encrypted the ciphertext bytes to decrypt
   * @param keys a comma-separated list of KMS Key IDs/ARN strings
   * @return the recovered plaintext bytes
   */
  def decryptObjectWithHSM(encrypted: Array[Byte], keys: String): Array[Byte] = {
    val keysList = keys.split(",").map(_.trim).toList.reverse

    // Each layer unwraps the previous ciphertext; feed the latest plaintext into the next decrypt.
    var current = encrypted

    for (key <- keysList) {
      val myBytes = SdkBytes.fromByteArray(current)
      val decryptRequest = DecryptRequest
                            .builder
                            .ciphertextBlob(myBytes)
                            .keyId(key)
                            .build

      val decryptResponse = kms.decrypt(decryptRequest)
      current = decryptResponse.plaintext().asByteArray()
    }

    current
  }
  
}


object AmazonKeyManagerObject {
    /**
     * Main entry point to run and test AWS KMS actions.
     *
     * @param args command line arguments
     */
    def main(args: Array[String]): Unit = {
      if (args.length < 1) {
        System.err.println("Usage: AmazonKeyManagerObject <account-dir-or-name> [password]")
        System.err.println("Password may also be set via ALTASTATA_PASSWORD.")
        System.exit(1)
      }
      val account: Account = new Account()
      val accountArg = args(0).trim
      val accountDir =
        if (new File(accountArg).isAbsolute) accountArg
        else Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountArg
      val password = if (args.length > 1) args(1) else sys.env.getOrElse("ALTASTATA_PASSWORD", "")
      if (password.isEmpty) {
        System.err.println("Password is required (argument or ALTASTATA_PASSWORD).")
        System.exit(1)
      }
      account.loadAccountProperties(accountDir)
      account.setPassword(password.toCharArray)

      val kms = new AmazonKmsManager()(account)

/*
    val scheduleKeyDeletionRequest = 
          new ScheduleKeyDeletionRequest().withKeyId("3898db86-6110-495c-a3cf-b03fd32fb677").withPendingWindowInDays(7)
    kms.scheduleKeyDeletion(scheduleKeyDeletionRequest)
*/

/*
    val limit = 10
    val marker = null

    val req = new ListKeysRequest().withMarker(marker).withLimit(limit)
    val result = kms.listKeys(req)
    
    val keys = result.getKeys.asScala
    
    keys.foreach { keyListEntry => {
        val req = new DescribeKeyRequest().withKeyId(keyListEntry.getKeyId)
        val result = kms.describeKey(req)
        
        println(keyListEntry.getKeyId + " - " + result)
        
        val PendingWindowInDays = 7

        val scheduleKeyDeletionRequest = 
          new ScheduleKeyDeletionRequest().withKeyId(keyListEntry.getKeyId).withPendingWindowInDays(PendingWindowInDays)
        kms.scheduleKeyDeletion(scheduleKeyDeletionRequest)
      }
    }
*/    
  }

}

