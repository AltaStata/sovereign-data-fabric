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

package com.altastata.cloud.localfs

import java.util.Properties
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import scala.collection._
import java.io.OutputStream
import scala.sys.process.BasicIO
import org.slf4j.LoggerFactory
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Buffer
import java.io.Closeable
import scala.concurrent._
import scala.concurrent.duration._
import java.nio.ByteBuffer
import scala.util.control.Exception.catching
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.altastata.utils.Constants
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import com.altastata.filesystem.securecloud.CloudMsgsHandler
import com.altastata.utils.Account
import java.util.Collections
import java.util.HashMap

class LocalMessagesManager(implicit account: Account) extends CloudMsgsHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  override def sendMsgToUser(userId: String, msg: String): Unit = {
    logger.trace(s"\tsendMsgToUser userId: ${userId} msg: ${msg}")
  }
  
  override def receiveMsgsForUser(): Boolean = {
    logger.trace(s"\treceiveMsgsForUser userId: ${account.MY_USER}")

    true
  }
  
  override def clearMsgs(): Unit = {
    logger.trace(s"\tclearMsgs() userId: ${account.MY_USER}")
  }
}
