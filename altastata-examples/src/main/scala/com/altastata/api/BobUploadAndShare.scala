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

package com.altastata.api

import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.filesystem.common.{CloudFile, FileSystemHandler}
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.io.File
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object BobUploadAndShare extends App {

  private val logger = LoggerFactory.getLogger(getClass)
  val account = new Account()

  account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123")
	
	val console = System.console
  if (console == null) {
    account.setPassword(args(0).toCharArray)
  }
  else {
    val passwordArray = console.readPassword("Enter your secret password: ")
    account.setPassword(passwordArray)
  }

  val DIRECTORY_NAME = "testdirectory";
  val CREATE_TIME = System.currentTimeMillis
    
  val filesList = List(
      new File("altastata-examples/files/video_streaming.png"),
      new File("altastata-examples/files/README.txt")
    )

  // create the list of files that we are going to upload  
  val listForSubTree =
    account.getFileSystemHandler().mapFilesTreeToCloudFileList(filesList.asJava, filesList(0).getAbsoluteFile.getParent, DIRECTORY_NAME, CREATE_TIME);

  
  val storeResults = account.fileSystemModel.uploadLocalFilesToCloud(listForSubTree)

  println("storedResults size: " + storeResults.size)

  Thread.sleep(5000)
  
  // list uploaded files
  val list = account.fileSystemModel.listCloudFiles(DIRECTORY_NAME, true)
  list.asScala.foreach(file => println(s"\tList: $file"))

  // prepare the list to share
  var toShare = ListBuffer[CloudFile]()

  for (storeResult <- storeResults) {
    storeResult.getOperationState match {
      case OperationState.DONE => { println("\tstoreResult filtered in: " + storeResult.getCloudFileVersionPath); toShare += account.getFileSystemHandler().parseObjectPathIncludingVersion(storeResult.getCloudFileVersionPath) }
      case OperationState.ERROR => println("\tstoreResult filtered out: " + storeResult)
    }
  }

  println("toShare: " + toShare)
  println("toShare size: " + toShare.length)
    
  // share the files with users alice222 and catrina777
  val timestampFilter = List[java.lang.Long](CREATE_TIME)

  val shareResults = 
    account.fileSystemModel.shareCloudFiles(toShare.toArray, 
                                       Array("alice222", "catrina777"), timestampFilter.asJava)

  for (shareResult <- shareResults) {
    shareResult.getOperationState match {
      case OperationState.DONE => println("\tshareResult filtered in: " + shareResult.getCloudFileVersionPath)
      case OperationState.ERROR => println("\tshareResult filtered out: " + shareResult.getCloudFileVersionPath + " dues to: " + shareResult.getError)
    }
  }

  println("shareResult size: " + shareResults.length)
}
