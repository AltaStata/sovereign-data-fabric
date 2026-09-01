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

package com.altastata.filesystem.securecloud

import scala.concurrent._
import scala.collection.JavaConverters._
import java.nio.ByteBuffer
import scala.collection.mutable.Buffer

trait CloudMsgsHandler {

  /**
   * Sends a secure notification/message to a designated recipient user.
   *
   * @param userId the target recipient user ID
   * @param msg the message string payload to transmit
   */
  def sendMsgToUser(userId: String, msg: String): Unit  
  
  /**
   * Checks for and receives pending secure messages queue from the cloud channel.
   *
   * @return true if there are pending messages received and processed; false otherwise
   */
  def receiveMsgsForUser(): Boolean
  
  /**
   * Clears out any processed messages from the cloud message queue.
   */
  def clearMsgs(): Unit
}

