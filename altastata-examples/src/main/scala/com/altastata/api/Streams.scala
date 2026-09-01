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

import com.altastata.filesystem.common.FileSystemHandler
import com.altastata.filesystem.securecloud.SecureCloudFileSystemModel
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream
import com.altastata.utils.Account

import java.io.{File, FileInputStream}
import java.nio.ByteBuffer

object Streams extends App {
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

    /**
     * Demonstrates opening an encrypted AltaStataChunkedOutputStream and appending content to it
     * from a local subtitle file.
     */
    def appendToFile = {
      println("Start appending to the text file")

      val os = new AltaStataChunkedOutputStream("Applications/test.txt")(Streams.account)
      val input = new FileInputStream(System.getProperty("user.home") + "/Desktop/presentation_subtitles.txt")
    
      var read: Int = -1
      while ({read = input.read; read != -1}) {
        os.write(read)
      }
        
      os.close
      
      println("Done appending to the text file")
    }
        
    /**
     * Demonstrates creating a video file version, storing a placeholder buffer, and then
     * streaming/writing a local MP4 file to the newly created video file on AltaStata.
     */
    def streamVideoToCloud = {
      println("Create video file")
      
      val videoFilePath = "Applications/altastata-demo-english-lena.mp4"
      
      // create the cloud file in multimap
      val cloudFile = account.getFileSystemHandler().createCloudFileVersion(videoFilePath, false, System.currentTimeMillis)
      
      // store it on the cloud
      val storeResult = account.secureCloudFileSystemModel.storeByteBufferToCloudFile(ByteBuffer.allocate(0), cloudFile)
      
      // stream video onto the file
      val os = new AltaStataChunkedOutputStream(videoFilePath)(Streams.account)
      val input = new FileInputStream(System.getProperty("user.home") + "/Desktop/altastata-demo.mp4")
      
      var read: Int = -1
      while ({read = input.read; read != -1}) {
        os.write(read)
      }
          
      os.close    
  
      println("Done video file creation")
    }
    
    appendToFile

    streamVideoToCloud
}
