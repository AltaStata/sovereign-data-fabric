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

import java.io.FileInputStream
import java.net.URI

import com.altastata.filesystem.securecloud.CloudMsgsHandler
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.cloud.pubsub.v1.Subscriber
import java.util

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutures
import com.google.api.gax.core.{CredentialsProvider, FixedCredentialsProvider}
import com.google.auth.oauth2.ServiceAccountCredentials
import org.apache.commons.io.IOUtils

// Pub/Sub handler — source kept; excluded from default compile in build.gradle (GooglePubSub**).
// google-secure accounts use LocalMessagesManager in Account.scala today; switch here when Pub/Sub is needed.
// Based on https://cloud.google.com/pubsub/docs/quickstart-client-libraries#pubsub-client-libraries-java
class GooglePubSubManager(implicit account: Account) extends CloudMsgsHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  var credentialsProvider: CredentialsProvider = FixedCredentialsProvider.create(
    ServiceAccountCredentials.fromStream(IOUtils.toInputStream(account.getAndDecryptProperty("credentials"), "UTF-8")))

  private def topicName(userId: String): String = {
    "projects/" + account.getProperty("google-project") + "/topics/" + account.getProperty("acccontainer-prefix") + userId + "sqs"
  }

  private def subscriptionName(userId: String): String = {
    "projects/" + account.getProperty("google-project") + "/subscriptions/" + account.getProperty("acccontainer-prefix") + userId + "sqs"
  }

  var wereMsgs = false

  class MessageReceiverExample extends MessageReceiver {
    override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit = {
      logger.debug("Message Id: " + message.getMessageId + " Data: " + message.getData.toStringUtf8)

      // Ack only after all work for the message is complete.
      consumer.ack()

      wereMsgs = true
    }
  }

  val subscriber = Subscriber.newBuilder(subscriptionName(account.MY_USER), new MessageReceiverExample)
    .setCredentialsProvider(credentialsProvider).build

  val runnableSQS = new Runnable {
    override def run(): Unit = {
      subscriber.startAsync.awaitRunning()

      // Allow the subscriber to run indefinitely unless an unrecoverable error occurs.
      subscriber.awaitTerminated()
    }
  }

  new Thread(runnableSQS).start()

  override def receiveMsgsForUser(): Boolean = {

    try { // create a subscriber bound to the asynchronous message receiver

      if (wereMsgs) {
        wereMsgs = false
        return true
      }
      else {
        return false
      }

    } catch {
      case t: IllegalStateException =>
        logger.warn("receiveMsgsForUser", t); return false
    }
  }
  
  override def clearMsgs(): Unit = {
    wereMsgs = false
  }

  override def sendMsgToUser(userId: String, msg: String): Unit = {

    val publisher = Publisher.newBuilder(topicName(userId)).setCredentialsProvider(credentialsProvider).build

    val futures = new util.ArrayList[ApiFuture[String]]

    try {
      val pubsubMessage = PubsubMessage.newBuilder()
        .setData(ByteString.copyFromUtf8(msg))
        .build()

      val future: ApiFuture[String] = publisher.publish(pubsubMessage)

      futures.add(future)
    }
    catch {
      case t: Throwable => t.printStackTrace()
    }
    finally {

      // Wait on any pending requests
      val messageIds = ApiFutures.allAsList(futures).get.asScala

      for (messageId <- messageIds) {
        logger.debug(messageId)
      }

      if (publisher != null) { // When finished with the publisher, shutdown to free up resources.
        publisher.shutdown
      }
    }
  }

}
