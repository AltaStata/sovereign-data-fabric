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

import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.api.CloudFileOperationStatus
import com.altastata.filesystem.{DeleteFileException, FileSystemModel, RetrieveFileException, StoreFileAndMetadataException}
import com.altastata.filesystem.common.{CloudFile, FileSystemHandler}
import com.altastata.utils.Account
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}


class AzureCloudFileSystemModel(containerName: String)(implicit account: Account) extends FileSystemModel {

  private val logger = LoggerFactory.getLogger(getClass)

  val azureManager = new AzureManager()

  azureManager.init()
  
  val azureCloud = {
    // create SAS and update properties file if does not exist
    val containerName = account.userProps.getProperty("containername")
    if (account.userProps.getProperty("sas-" + containerName) == null) {
      val sas = azureManager.generateContainerSAS(containerName)
            
      account.userProps.setProperty("sas-" + containerName, sas)
      account.saveCurrentProprtiesFile("Simple Account Azure")
    }
    
    // create SAS based AzureManager
    azureManager
  }
  
  override def listCloudFiles(prefix: String, useFlatBlobListing: Boolean, startAfter: String = null, endBefore: String = null, mergeVersions: Boolean = true): java.util.Iterator[CloudFile] = {
    try {
      azureCloud.getAzureListWithDetails(containerName, prefix, useFlatBlobListing).map (t => account.getFileSystemHandler().parseStorageFilePath(t)).iterator.asJava
    }
    catch {
      case t: Throwable => logger.error("listCloudFiles", t) // TODO: handle error
      null
    }
  }

  override def uploadLocalFilesToCloud(files: java.util.List[(File, CloudFile)], waitUntilDone: Boolean = true): Array[CloudFileOperationStatus] = {
    val filesStream: Stream[Tuple2[File, CloudFile]] = files.asScala.toStream
      .map(ff => (ff._1, account.getFileSystemHandler().addCloudFileInUploadingProcess(ff._2)))
      .map(ff => (ff._1, ff._2.setOperationStateValue(OperationState.UPLOADING)))
      .filter(ff => ff._2.isDirectory == false)

    val storeFuture = Future.traverse(filesStream)(storeLocalFileFuture())
    val result = Await.result(storeFuture, Duration.Inf)

    files.asScala.toStream.foreach(ff => ff._2.setOperationStateValue(OperationState.NONE))

    result.toArray
  }
  
  override def retrieveCloudFilesToLocalDirectory(toRetrieve: Array[CloudFile], outputDir: String, timestampsFilter: java.util.List[java.lang.Long], isStreaming: Boolean = false, waitUntilDone: Boolean = true, isPreview: Boolean = false): Array[CloudFileOperationStatus] = {
    val baseDir: String = toRetrieve(0).getParent
    val toRetrieveSubtree = account.getFileSystemHandler().getWithSubtrees(toRetrieve, OperationState.DOWNLOADING, null)

    val retrieveFuture = Future.traverse(toRetrieveSubtree.toStream)(retrieveCloudFileFuture(baseDir, outputDir))
    val result = Await.result(retrieveFuture, Duration.Inf)

    account.getFileSystemHandler().getWithSubtrees(toRetrieve, OperationState.NONE, null)
    
    result.toArray
  }
  
  override def shareCloudFiles(toShare: Array[CloudFile], userIds: Array[String], timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    val toShareSubtree = account.getFileSystemHandler().getWithSubtrees(toShare, OperationState.SHARING, null)
    
    val allPairs: List[(String, CloudFile)] = 
      for(toUserId <- userIds.toList; cloudFile <- toShareSubtree.toStream) yield (toUserId, cloudFile)

    val shareFuture = Future.traverse(allPairs.toStream)(shareCloudFileFuture)
    val result = Await.result(shareFuture, Duration.Inf)

    account.getFileSystemHandler().getWithSubtrees(toShare, OperationState.NONE, null)

    // TODO:    sendMsgToUser(toUserId, "SHARE_" + MY_USER + "_" + toShare.size)

    result.toArray
  }

  override def deleteCloudFiles(toDelete: Array[CloudFile], timestampsFilter: java.util.List[java.lang.Long]): Array[CloudFileOperationStatus] = {
    val toDeleteSubtree = account.getFileSystemHandler().getWithSubtrees(toDelete, OperationState.DELETING, timestampsFilter)

    val deleteFuture = Future.traverse(toDeleteSubtree.toStream)(deleteCloudFileFuture)
    val result = Await.result(deleteFuture, Duration.Inf)
    
    result.toArray
  }  
  
  /**
   * Input file contains the base directory and actual file
   */
  def storeLocalFileFuture()(inputFiles: Tuple2[File, CloudFile]): Future[CloudFileOperationStatus] = Future {    
    var storageFilePath: String = inputFiles._2.getPath
    
    val result: Try[String] = 
      azureCloud.storeInAzure(containerName, inputFiles._1.getAbsolutePath, storageFilePath)
        
    result match {
      case Success(t) => {
        inputFiles._2.setOperationStateValue(OperationState.NONE)
        
        // TODO Billing.sendBillingMsg("STS", inputFiles._1.length)

        new CloudFileOperationStatus(s"${inputFiles._2.getPath}", OperationState.UPLOADING)
            .setOperationState(OperationState.DONE)
      }
      case Failure(e) => new CloudFileOperationStatus(s"${inputFiles._2}", OperationState.ERROR)
                            .setError(new StoreFileAndMetadataException(s"${inputFiles._2}", e))
    }    
  } 


  /**
   * Output dir can be unified as its per all the files
   */
  def retrieveCloudFileFuture(baseDir: String, outputDir: String)(cloudFile: CloudFile): Future[CloudFileOperationStatus] = Future {    
    val relativePath = cloudFile.getPath.replace(baseDir, "").stripPrefix("/").stripPrefix("\\")
    val outputFilePath = FileSystemHandler.resolveDownloadPathInsideOutputDir(outputDir, relativePath)

    logger.debug(s"retrieveLocalFileFuture: ${outputFilePath}")
    
    cloudFile.setOperationStateValue(OperationState.DOWNLOADING)

    val result: Try[String] = 
      azureCloud.retrieveFromAzure(containerName, outputFilePath, cloudFile.getPath)

    result match {
      case Success(t) => {
        cloudFile.setOperationStateValue(OperationState.NONE)
        
        //Billing.sendBillingMsg("RTS", cloudFile.getVersions.last().getFileSize())

        new CloudFileOperationStatus(s"${outputFilePath}", OperationState.DOWNLOADING)
            .setOperationState(OperationState.DONE)
      }
      case Failure(e) => new CloudFileOperationStatus(s"${outputFilePath}", OperationState.ERROR).setError(new RetrieveFileException(s"$outputFilePath", e))
    }
  }
 
  /**
   * Output dir can be unified as its per all the files
   */
  def shareCloudFileFuture(pair: (String, CloudFile)): Future[CloudFileOperationStatus] = Future {    
//    cloudFile.setOperationStateValue(OperationState.SHARING)
//
//    val storageFilePathIncludingVersion: String = cloudFile.getLaststorageFilePathIncludingVersion()
//    val storageObjectMetadata = await(retrieveFileMetadata(MY_USER, storageFilePathIncludingVersion))
//
//    logger.debug(s"shareFileFuture: ${storageObjectMetadata.getObjectPath}")
//
//    val result: Try[Unit] = 
//      Await.ready(shareFileMetadataRequest(storageFilePathIncludingVersion, toUserId, false), Duration.Inf).value.get
//
//    result match {
//      case Success(t) => {
//        cloudFile.setOperationStateValue(OperationState.NONE)
//        Right(storageFilePathIncludingVersion)
//      }
//      case Failure(e) => Left(new CloudExplainedException(s"${storageFilePathIncludingVersion}", e))
//    }
    
    new CloudFileOperationStatus("", OperationState.ERROR).setError(new NotImplementedError)
  }
  
  /**
   * Output dir can be unified as its per all the files
   */
  def deleteCloudFileFuture(cloudFile: CloudFile): Future[CloudFileOperationStatus] = Future {
    val cloudFileOperationStatus = new CloudFileOperationStatus(s"{cloudFile.getAbsolutePath}", OperationState.DELETING)

    try {
      logger.debug(s"deleteCloudFileFuture: ${cloudFile.getPath}")
      
      cloudFile.setOperationStateValue(OperationState.DELETING)
  
      val result: Unit = 
        azureCloud.deleteObjectFromAzure(containerName, cloudFile.getPath)
  
      // delete the only version
      cloudFile.removeVersion(cloudFile.getVersions.last)

      account.getFileSystemHandler().tryToDeleteFile(cloudFile)

      cloudFileOperationStatus.setOperationState(OperationState.DONE)
    }
    catch {
      case t: Throwable => logger.error("deleteCloudFileFuture", t)
                           cloudFileOperationStatus.setError(new DeleteFileException(s"Delete file: ${cloudFile.getPath}", t))
    }
  }
}


