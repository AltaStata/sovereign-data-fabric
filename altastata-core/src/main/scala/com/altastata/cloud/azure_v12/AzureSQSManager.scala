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

package com.altastata.cloud.azure_v12

import com.altastata.filesystem.securecloud.CloudMsgsHandler
import com.altastata.utils.Account
import com.azure.storage.queue._
import com.azure.storage.queue.models._
import org.slf4j.LoggerFactory

import java.net.URI
import scala.collection.JavaConverters._

/**
 * TODO: Need to test it, I just used the ChatGPT to create this code
 */

class AzureSQSManager(implicit account: Account) extends CloudMsgsHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  // Replace the word "blob" with the word "queue"
  /**
   * Retrieves the base URI for the queue service.
   *
   * @return the base URI for the queue endpoint
   */
  def baseuri = new URI(account.userProps.getProperty("azure-account").replace(".blob.", ".queue."))

  // Using QueueServiceClientBuilder in Azure SDK v12
  var myQueueClient: QueueClient = new QueueClientBuilder()
    .endpoint(baseuri.toString)
    .sasToken(getQueueSAS(account.MY_USER))
    .queueName(account.ACCOUNT_CONTAINER_PREFIX + account.MY_USER + "sqs")
    .buildClient()

  /**
   * Retrieves the SAS token associated with the target user's SQS Queue.
   *
   * @param userName the target username
   * @return the resolved SAS token string
   */
  def getQueueSAS(userName: String): String = {
    if (userName == account.MY_USER) {
      account.getAndDecryptProperty("sas-" + account.ACCOUNT_CONTAINER_PREFIX + account.MY_USER + "sqs")
    } else {
      val userData = account.fileSystemModel.retrieveUserdata(userName)
      val otherUserQueueSAS = userData.producerQueueSAS

      if (otherUserQueueSAS == null) {
        throw new AzureManagerContainerNotFoundException(s"No SQS SAS found for $userName.")
      }

      otherUserQueueSAS.getOrElse(null)
    }
  }

  override def sendMsgToUser(userId: String, msg: String): Unit = {
    val queueClient = new QueueClientBuilder()
      .endpoint(baseuri.toString)
      .sasToken(getQueueSAS(userId))
      .queueName(account.ACCOUNT_CONTAINER_PREFIX + userId + "sqs")
      .buildClient()

    try {
      val peek = queueClient.peekMessage()

      if (peek == null) {
        logger.debug(s"sendMsgToUser adding message to ${userId}sqs")
        queueClient.sendMessage(msg) // Azure SDK v12 sends messages this way
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  }

  override def receiveMsgsForUser(): Boolean = {
    try {
      val msg = myQueueClient.receiveMessage()

      if (msg == null) {
        return false
      } else {
        // Deleting the message after processing it
        myQueueClient.deleteMessage(msg.getMessageId, msg.getPopReceipt)
        return true
      }
    } catch {
      case t: QueueStorageException => logger.warn("receiveMsgsForUser", t); return false
    }
  }

  override def clearMsgs(): Unit = {
    var count: Int = 0
    try {
      var messages: Iterable[QueueMessageItem] = null

      do {
        messages = myQueueClient.receiveMessages(31).asScala
        messages.foreach { msg =>
          myQueueClient.deleteMessage(msg.getMessageId, msg.getPopReceipt)
          count += 1
        }
      } while (messages.nonEmpty)

      logger.debug(s"receiveMsgsForUser $count messages purged from ${account.MY_USER}sqs")
    } catch {
      case t: Throwable => logger.warn("clearMsgs", t)
    }
  }
}
