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
import com.altastata.filesystem.securecloud.{SecureCloudFileSystemModel, ChannelEncryptionService}
import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object SimpleTest extends App {

  private val logger = LoggerFactory.getLogger(getClass)
  val account = new Account()

  //  buffersSplitMergeTest
  
  account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123")

  val console = System.console
  if (console == null) {
    account.setPassword(args(0).toCharArray)
  }
  else {
    val passwordArray = console.readPassword("Enter your secret password: ")
    account.setPassword(passwordArray)
  }
  
  val outputDir = "tmp/"
  
  val filesList = List(
      new File("altastata-examples/files/video_streaming.png"),
      new File("altastata-examples/files/README.txt")
    )
    
  // create the list of files that we are going to upload  
  val listForSubTree =
    account.getFileSystemHandler().mapFilesTreeToCloudFileList(filesList.asJava, filesList(0).getAbsoluteFile.getParent, "", System.currentTimeMillis())
  
  val storeResults = account.fileSystemModel.uploadLocalFilesToCloud(listForSubTree)

  println("storedResults size: " + storeResults.size)

  Thread.sleep(3000)
  
  val list = account.fileSystemModel.listCloudFiles("", true)
  list.asScala.foreach(file => println(s"\tList: $file"))

  // print store result and create the list of files to share and retrieve
  var toRetrieve = ListBuffer[CloudFile]()

  for (storeResult <- storeResults) {
    storeResult.getOperationState match {
      case OperationState.DONE => { println("\tstoreResult filtered in: " + storeResult.getCloudFileVersionPath); toRetrieve += account.getFileSystemHandler().parseObjectPathIncludingVersion(storeResult.getCloudFileVersionPath) }
      case OperationState.ERROR => println("\tstoreResult filtered out: " + storeResult)
    }
  }

  println("toShare: " + toRetrieve)
  println("toShare size: " + toRetrieve.length)
    
  val timestampFilter = List[java.lang.Long](System.currentTimeMillis)

  val shareResults = account.fileSystemModel.shareCloudFiles(toRetrieve.toArray, Array("alice222"), timestampFilter.asJava)

  for (shareResult <- shareResults) {
    shareResult.getOperationState match {
      case OperationState.DONE => println("\tshareResult filtered in: " + shareResult.getCloudFileVersionPath)
      case OperationState.ERROR => println("\tshareResult filtered out: " + shareResult.getCloudFileVersionPath + " dues to: " + shareResult.getError)
    }
  }

  println("shareResult size: " + shareResults.size)

  println("toRetrieve: " + toRetrieve)
  println("toRetrieve size: " + toRetrieve.length)

  val retrieveResults = account.fileSystemModel.retrieveCloudFilesToLocalDirectory(toRetrieve.toArray, outputDir, timestampFilter.asJava)

  for (retrieveResult <- retrieveResults) {
    println("\tretrieveResult to: " + retrieveResult)
  }

  println("retrieveResults size: " + retrieveResults.size)
  
  
  /**
   * store buffer
   */
  val array = Files.readAllBytes(Paths.get("altastata-examples/files/README.txt"))

  val cloudFile = account.getFileSystemHandler().createCloudFileVersion("program/test.bak", false, System.currentTimeMillis)
    
  val storeResult = account.secureCloudFileSystemModel.storeByteBufferToCloudFile(ByteBuffer.wrap(array), cloudFile)
  
  println(s"stored ${cloudFile.getPath}")
   
  /**
   *  retrieve to the buffer
   */
  val lastFile = toRetrieve(1)
  
  // allocate the buffer
  val buffer = ByteBuffer.allocate(lastFile.getVersions.last.getVersionDataAttribute("size").toLong.toInt)
  
  // read the file to the buffer
  val status = account.secureCloudFileSystemModel.retrieveCloudFileToByteBuffer(buffer, lastFile, List(lastFile.getVersions.last.getCreateTime).map(java.lang.Long.valueOf).asJava, 0L, null)

  // status.left.get.printStackTrace
  println(s"retrieved ${lastFile} ${status}")
  
  println("Buffer: " + new String(buffer.array()))

  // write buffer to the file to compare
  Files.write(Paths.get("tmp/README.txt"), buffer.array(), StandardOpenOption.CREATE)
  
  Thread.sleep(20000)
  
  toRetrieve.asJava.add(cloudFile)
  
  /**
   * Use the AES key related to the cloudFile to encrypt & decrypt the byte array
   */
  val cloudFileBasedEncryptionService = ChannelEncryptionService(cloudFile.getPath)(account)
  
  val ciphertext = cloudFileBasedEncryptionService.encryptByteArray("#My Test$".getBytes)
  val orig = cloudFileBasedEncryptionService.decryptByteArray(ciphertext)
  
  println("Use the key to encrypt/decrypt text: ciphertext: " + new String(ciphertext) + "orig: " + new String(orig))


  // use same list that retrieve did
  println("toDelete: " + toRetrieve)
  println("toDelete size: " + toRetrieve.length)

  val timestampFilter2 = List[java.lang.Long](System.currentTimeMillis)

  val deleteResults = account.fileSystemModel.deleteCloudFiles(toRetrieve.toArray, timestampFilter2.asJava)

  for (deleteResult <- deleteResults) {
    println("\tdeleteResult: " + deleteResult)
  }
}
