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

package com.altastata.python

import com.altastata.filesystem.common.FileSystemHandler
import java.io.File
import com.altastata.utils.Account
import collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import com.altastata.filesystem.common.CloudFile
import com.altastata.api.CloudFileOperationStatus

class FileSystemPythonHelper(implicit account: Account) {
  
  /**
   * Initializes the python-bridge filesystem context by loading account properties and setting the password.
   *
   * @param accountDirPath the local path to the account directory containing configuration files
   * @param accountPassword the password to decrypt the user's private key materials
   */
  def init(accountDirPath: String, accountPassword: String): Unit = {
    account.loadAccountProperties(accountDirPath)
    account.setPassword(accountPassword.toCharArray)
  }
  
  /**
   * Uploads a list of local files to the designated target cloud folder.
   *
   * @param fileNames a list of local file paths to upload
   * @param baseLocalDir the local base folder directory prefix
   * @param targetCloudDir the target destination folder directory prefix in the cloud
   * @return a List of operational status results for each file
   */
  def uploadFiles(fileNames: java.util.List[String], baseLocalDir: String, targetCloudDir: String): List[CloudFileOperationStatus] = {    
    val filesList = fileNames.asScala.map(new File(_)).asJava
    
    // create the list of files that we are going to upload  
    val listForSubTree =
      account.getFileSystemHandler().mapFilesTreeToCloudFileList(filesList, baseLocalDir, targetCloudDir, System.currentTimeMillis())
      
    account.fileSystemModel.uploadLocalFilesToCloud(listForSubTree).toList
  }
 
  /**
   * Lists secure cloud files matching the specified prefix.
   *
   * @param prefix the folder prefix path
   * @param useFlatBlobListing true to search recursively; false to list immediate children only
   * @return an Iterator of CloudFile instances
   */
  def listFiles(prefix: String, useFlatBlobListing: Boolean): java.util.Iterator[CloudFile] = {
    account.fileSystemModel.listCloudFiles(prefix, useFlatBlobListing)
  }

  /**
   * Downloads secure cloud files to a designated local output directory.
   *
   * @param cloudFiles the list of CloudFile instances to download
   * @param outputDir the local destination folder path
   * @param timestampFilter a list of specific version timestamps to filter downloads
   * @return a List of operational status results
   */
  def downloadFiles(cloudFiles: java.util.List[CloudFile], outputDir: String, timestampFilter: java.util.List[java.lang.Long]): List[CloudFileOperationStatus] = {    
    account.fileSystemModel.retrieveCloudFilesToLocalDirectory(cloudFiles.asScala.toArray, outputDir, timestampFilter).toList
  }

  /**
   * Shares secure cloud files with designated recipient user IDs.
   *
   * @param cloudFiles the list of CloudFile instances to share
   * @param userIds the list of target recipient user IDs
   * @param timestampFilter version resolution filters
   * @return a List of operational status results
   */
  def shareFiles(cloudFiles: java.util.List[CloudFile], userIds: java.util.List[String], timestampFilter: java.util.List[java.lang.Long]): List[CloudFileOperationStatus] = {    
    account.fileSystemModel.shareCloudFiles(cloudFiles.asScala.toArray, userIds.asScala.toArray, timestampFilter).toList
  }

  /**
   * Deletes secure cloud files from both the catalog and active cloud storage.
   *
   * @param cloudFiles the list of CloudFile instances to delete
   * @param timestampFilter version resolution filters
   * @return a List of operational status results
   */
  def deleteFiles(cloudFiles: java.util.List[CloudFile], timestampFilter: java.util.List[java.lang.Long]): List[CloudFileOperationStatus] = {    
    account.fileSystemModel.deleteCloudFiles(cloudFiles.asScala.toArray, timestampFilter).toList
  }

}
