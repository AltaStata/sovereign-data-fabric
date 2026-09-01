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

import com.altastata.filesystem.securecloud.CloudMsgsHandler
import com.altastata.utils.Account
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.services.sqs.model.{PurgeQueueInProgressException, PurgeQueueRequest, ReceiveMessageRequest, SendMessageRequest}
import org.slf4j.LoggerFactory

import java.time.Duration

class AmazonSQSManager(implicit account: Account) extends CloudMsgsHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  val region = Region.US_WEST_2

  val sqsClient: SqsClient =
    if (account.userProps.getProperty("cognito-identity-id") == null)
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

        val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
          .apiCallTimeout(Duration.ofSeconds(600))
          .apiCallAttemptTimeout(Duration.ofSeconds(180))
          .build()

        val client = SqsClient.builder()
          .region(region)
          .credentialsProvider(credentialsProvider)
          .overrideConfiguration(clientOverrideConfiguration)
          //.httpClient(NettyNioAsyncHttpClient.builder()
          //  .readTimeout(Duration.ofSeconds(60))
          //  .build())
          .build()

        client
      }
    else {
      val altastataCognitoProvider =  account.cognitoClient.getCredentialsProvider(account.MY_USER, account.getCognitoPassword)

      val client = SqsClient.builder()
        .credentialsProvider(altastataCognitoProvider)
        .region(Region.of(account.getProperty("region")))
        .build()
        
      client
    }

  /*
  private def getClientConfiguration : ClientConfiguration = {
    val clientConfiguration = new ClientConfiguration()

    clientConfiguration
        .withUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/43.0.2357.65 Safari/537.36")
    
    clientConfiguration.withConnectionTimeout(5 * 60000).withSocketTimeout(40000).withMaxConnections(300).withMaxErrorRetry(10) 
    //TODO: android does not support it: .withTcpKeepAlive(true)

    if (account.userProps.getProperty("proxyHost") != null) {
      clientConfiguration.withProxyHost(account.userProps.getProperty("proxyHost"))
                         .withProxyPort(Integer.parseInt(account.userProps.getProperty("proxyPort")))
    }
    
    clientConfiguration
  }

   */

// TODO: synchronize using findLockForSQS(userId)
//  val sqsAccessManagers = Collections.synchronizedMap(HashMap[String, Object])
//  
//  def findLockForSQS(userId: String): Object = {
//    sqsAccessManagers.getOrElseUpdate(userId, new Object)
//  }

  private def getCognitoIdentityIdForUser(userId: String): String = {
    if (userId == account.MY_USER) {
      return account.userProps.getProperty("cognito-identity-id")
    }
    else {
        return account.fileSystemModel.retrieveUserdata(userId).cognitoIdentityId.getOrElse(null)
    }
  }
  
  override def sendMsgToUser(userId: String, msg: String): Unit = {
    logger.trace(s"\tsendMsgToUser userId: ${userId} msg: ${msg}")
    
    // do not send message, if this is Cognito user
    if (userId == account.USERS_SUFFIX || getCognitoIdentityIdForUser(userId) == null) {
      val receiveMessageRequest =
          ReceiveMessageRequest.builder.queueUrl(account.ACCOUNT_CONTAINER_PREFIX + userId + "sqs").maxNumberOfMessages(1).waitTimeSeconds(0).build

      if (sqsClient.receiveMessage(receiveMessageRequest).messages().size() > 0) {
        val sendMsgRequest = SendMessageRequest.builder.queueUrl(account.ACCOUNT_CONTAINER_PREFIX + userId + "sqs").messageBody(msg).build

        sqsClient.sendMessage(sendMsgRequest)
      }
    }
  }
  
  override def receiveMsgsForUser(): Boolean = {
    logger.trace(s"\treceiveMsgsForUser userId: ${account.MY_USER}")

    if (getCognitoIdentityIdForUser(account.MY_USER) == null) {

      val receiveMessageRequest =
        ReceiveMessageRequest.builder.queueUrl(account.ACCOUNT_CONTAINER_PREFIX + account.MY_USER + "sqs").maxNumberOfMessages(1).waitTimeSeconds(0).build

      if (sqsClient.receiveMessage(receiveMessageRequest).messages().size > 0) {
        true
      }
      else {
        false
      }
    }
    else {
      true
    }
  }
  
  override def clearMsgs(): Unit = {
    if (getCognitoIdentityIdForUser(account.MY_USER) == null) {

      logger.info(s"Amazon purge SQS")

      try {
        val purgeQueueRequest =
          PurgeQueueRequest.builder.queueUrl(account.ACCOUNT_CONTAINER_PREFIX + account.MY_USER + "sqs").build

        sqsClient.purgeQueue(purgeQueueRequest)
      }
      catch {
        case t: PurgeQueueInProgressException => logger.warn(t.getMessage)
      }
    }
  }
}

