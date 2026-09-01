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

package org.apache.hadoop.fs.altastata

import java.net.URI
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.hadoop.util.Progressable
import org.apache.hadoop.fs.FSDataInputStream
import org.apache.hadoop.fs.FSDataOutputStream
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.conf.Configuration
import com.altastata.utils.Account
import com.altastata.utils.Constants

import java.io.FileNotFoundException
import com.altastata.filesystem.common.CloudFile
import com.altastata.filesystem.common.FileSystemHandler
import com.altastata.filesystem.securecloud.SecureCloudStream
import org.apache.hadoop.fs.FSInputStream

import java.net.URL
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream

import scala.util.control.Exception.Catch
import org.slf4j.LoggerFactory

import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import org.apache.hadoop.fs.permission.AclEntry
import com.altastata.api.AltaStataFileSystem.OperationState

import java.io.StringWriter
import scala.collection.JavaConverters._ 

/**
 * Custom Apache Hadoop {@link FileSystem} implementation for AltaStata secure cloud storage.
 * 
 * Allows big data processing frameworks like Apache Hadoop, Apache Spark, and Databricks to 
 * seamlessly read from and write to AltaStata secure storage as a native file system. Coordinates 
 * transparent end-to-end client-side encryption/decryption, high-throughput multi-part chunking, 
 * and access control policies across heterogeneous cloud providers.
 * 
 * Configured using properties prefixed with "altastata.account.*" in Hadoop XML/Spark configuration.
 */
class AltaStataHadoopFileSystem extends FileSystem {

  private val logger = LoggerFactory.getLogger(getClass)

  val account = new Account()

  /** filesystem prefix: {@value} */
  val ALTASTATA = "altastata"
  
  /**
   * path to user work directory for storing temporary files
   */
  var workingDir: Path = null

  /**
   * AltaStata URI
   */
  var uri: URI = null

  /**
   * We need a persistent cache for directories because AltaStata physical object storage 
   * doesn't create 0-byte objects for directories (it's flat). 
   * HBase relies on directory existence for initialization and operation. 
   * A JVM-level cache means we don't need a metadata database for now.
   * Caching helps significantly with speed and prevents FileNotFoundExceptions 
   * that HBase cannot handle.
   */
  private val createdDirectories = new ConcurrentHashMap[String, java.lang.Long]()

  /**
   * Returns the URI scheme for this custom file system implementation.
   * 
   * @return The string scheme, which is consistently {@code "altastata"}.
   */
  override def getScheme(): String = ALTASTATA

  /**
   * Initializes the AltaStata Hadoop FileSystem instance using the specified URI and 
   * Hadoop XML / Spark environment configurations.
   * 
   * This method performs initial parameter resolution, extraction, and validation of user properties:
   * - Sets up the base {@link Account} context.
   * - Retrieves user configuration paths from {@code altastata.account.properties}.
   * - Sets the plain-text password for certificate/private key decryption from {@code altastata.account.password}.
   * - Loads the PEM-encoded encrypted RSA/PQC private key from {@code altastata.account.encryptedprivatekey}.
   * - Configures the underlying absolute working directory.
   *
   * @param fsuri The filesystem URI used to address this instance.
   * @param conf  The Hadoop configuration map containing environment properties.
   */
  override def initialize(fsuri: URI, conf: Configuration) = {
    super.initialize(fsuri, conf)

    logger.info(s"initialize: " + fsuri)

    /*
    val outputWriter = new StringWriter()
    Configuration.dumpConfiguration(conf, outputWriter)

    logger.info(s"All properties: " + outputWriter.toString())
    */

    if (conf.get("altastata.account.home") != null) {
      account.loadAccountProperties(conf.get("altastata.account.home"))
    } else if (conf.get("altastata.account.properties") != null) {
      //logger.info(s"loadUserProperties: " + conf.get("altastata.account.properties"))
      account.loadUserProperties(conf.get("altastata.account.properties"))

      //logger.info(s"accounttype: ${account.userProps.getProperty("accounttype")}")
    }

    // PEM must be installed before setPassword so RSA/PQC unlock can load the key.
    if (conf.get("altastata.account.encryptedprivatekey") != null) {
      account.encryptedRSAPrivateKeyPEM = conf.get("altastata.account.encryptedprivatekey")
    }

    if (conf.get("altastata.account.password") != null) {
      account.setPassword(conf.get("altastata.account.password").toCharArray)
    } else if (account.requiresLocalPassword) {
      throw new IllegalArgumentException(
        "altastata.account.password is required for RSA/PQC accounts")
    }
    // HSM/HPCS: event loop already started during loadUserProperties.

    setConf(conf)
    
    this.uri = new URI("altastata:/")
    this.workingDir = new Path(uri)

    logger.info(s"Initializing АltaStataHadoopFileSystem URI " + uri + " and working dir " + workingDir)
  }

  /**
   * Retrieves the current URI representing this AltaStata filesystem instance.
   *
   * @return The active {@link java.net.URI} of the filesystem.
   */
  override def getUri(): URI = uri

  /**
   * Retrieves the current user-defined working directory path on AltaStata.
   *
   * @return The current {@link org.apache.hadoop.fs.Path} representing the working directory.
   */
  override def getWorkingDirectory(): Path = workingDir

  /**
   * Sets the user's current working directory path for relative path calculations.
   *
   * @param new_dir The new target working {@link org.apache.hadoop.fs.Path}.
   */
  override def setWorkingDirectory(new_dir: Path) = {
    workingDir = new_dir
  }
  
  /**
   * Returns the default block size for the AltaStata Hadoop FileSystem.
   * 
   * This is dynamically computed based on the maximum payload size of a single plain block chunk 
   * multiplied by the configured number of prefetch chunks requested together for performance tuning.
   *
   * @return The computed default block size in bytes.
   */
  override def getDefaultBlockSize(): Long = Constants.PLAIN_CHUNK_MAX_SIZE * Constants.CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM

  /**
   * Retrieves the block size for a specific file path.
   * 
   * For the AltaStata cloud storage driver, this returns the uniform default block size 
   * regardless of the specific path provided.
   *
   * @param path The targeted file {@link org.apache.hadoop.fs.Path}.
   * @return The block size in bytes.
   */
  override def getBlockSize(path: Path): Long = getDefaultBlockSize()
  
  /**
   * Determines whether the specified path represents an existing file in the cloud storage.
   * 
   * It locates the metadata corresponding to the path and verifies that the file name matches 
   * the requested path, confirming it is not a virtual directory prefix.
   *
   * @param f The {@link org.apache.hadoop.fs.Path} to check.
   * @return {@code true} if the path is an existing file, {@code false} otherwise.
   */
  override def isFile(f: Path): Boolean = {
    try {
      !getFileStatus(f).isDirectory
    } catch {
      case _: FileNotFoundException => false
    }
  }
  
  override def isDirectory(f: Path): Boolean = {
    try {
      getFileStatus(f).isDirectory
    } catch {
      case _: FileNotFoundException => false
    }
  }
  
  /**
   * Retrieves the canonical service name for security token purposes.
   * 
   * AltaStata does not use a central Hadoop security token service, so this returns {@code null}
   * to bypass service-token token checks.
   *
   * @return Always returns {@code null}.
   */
  override def getCanonicalServiceName(): String = null

  /**
   * Retrieves status metadata (size, replication, block size, modification time, owner) for a path.
   *
   * @param f The target Path.
   * @return A standard Hadoop FileStatus object.
   * @throws FileNotFoundException if the path does not exist.
   */
  /**
   * Retrieves status metadata (including file size, directory status, block size, ownership,
   * permissions, and modification timestamp) for the specified target Hadoop Path.
   *
   * It fetches the corresponding metadata object {@link CloudFile} from the cloud backend,
   * extracts attributes for the latest active file version, and builds a standard Hadoop
   * {@link org.apache.hadoop.fs.FileStatus} object.
   *
   * @param f The target {@link org.apache.hadoop.fs.Path} to query.
   * @return A standard {@link org.apache.hadoop.fs.FileStatus} containing resolved metadata.
   * @throws java.io.FileNotFoundException if the path does not exist in the backend.
   */
  override def getFileStatus(f: Path): FileStatus = {
    logger.info(s"getFileStatus: " + f + " accounttype: " + account.userProps.getProperty("accounttype"))

    val absolutePathString = getAltaStataCloudFileName(f)

    // Hadoop root is always a directory. Empty catalog key would not match `path/` children.
    if (absolutePathString.isEmpty) {
      return new FileStatus(0,
                            true,
                            0,
                            getDefaultBlockSize(),
                            0L, System.currentTimeMillis(),
                            FsPermission.getDefault,
                            account.MY_USER,
                            null,
                            f)
    }

    val listed = listCloudFilesUnder(absolutePathString)

    listed.find(_.getPath == absolutePathString).foreach { cloudFile =>
      val dataSizeAttribute = cloudFile.getVersions.last.getVersionDataAttribute("size")
      return new FileStatus(dataSizeAttribute.toLong,
                            false,
                            0,
                            getDefaultBlockSize(),
                            cloudFile.getVersions.last.getCreateTime, System.currentTimeMillis(),
                            FsPermission.getDefault,
                            account.MY_USER,
                            null,
                            f)
    }

    listed.find(_.getPath.startsWith(absolutePathString + "/")).foreach { child =>
      return new FileStatus(0,
                            true,
                            0,
                            getDefaultBlockSize(),
                            child.getVersions.last.getCreateTime, System.currentTimeMillis(),
                            FsPermission.getDefault,
                            account.MY_USER,
                            null,
                            f)
    }

    // Check if it's a known empty directory created in this JVM session
    if (createdDirectories.containsKey(absolutePathString)) {
      logger.info(s"getFileStatus: returning cached empty directory status for " + absolutePathString)
      return new FileStatus(0, 
                            true, // isDirectory
                            0,
                            getDefaultBlockSize(), 
                            createdDirectories.get(absolutePathString), System.currentTimeMillis(),
                            FsPermission.getDefault, 
                            account.MY_USER, 
                            null, 
                            f)
    }

    // Standard HDFS throws FileNotFoundException which is caught by Hadoop's FilterFileSystem/FileSystem methods.
    throw new FileNotFoundException("File " + absolutePathString + " does not exist.")
  }
  
  /**
   * Lists status metadata for a path, matching HDFS / {@code RawLocalFileSystem}:
   * <ul>
   *   <li>file — one-element array with that file (HBase {@code FSUtils.getVersion}
   *       calls {@code listStatus(rootdir/hbase.version)} and treats {@code []} as missing)</li>
   *   <li>directory — immediate children</li>
   *   <li>missing — {@link FileNotFoundException}</li>
   * </ul>
   *
   * @param f The file or directory {@link org.apache.hadoop.fs.Path} to list.
   * @return File status for the file itself, or one status per directory child.
   * @throws FileNotFoundException if the path does not exist.
   */
  override def listStatus(f: Path): Array[FileStatus] = {
    logger.info(s"listStatus: " + f)

    val status = getFileStatus(f)
    if (!status.isDirectory) {
      return Array(status)
    }

    val absolutePathString = getAltaStataCloudFileName(f)
    val allChildrenIncludingFilesAndDirectories = account.getFileSystemHandler().listDirectory(absolutePathString)

    if (allChildrenIncludingFilesAndDirectories != null) {
      allChildrenIncludingFilesAndDirectories.asScala.toList.toArray
        .map(cf => {
          val path = new Path(cf.getPath).makeQualified(this.uri, this.workingDir)
          val childStatus = getFileStatus(path)

          logger.info(s"\tlistStatus path: " + path + " status: " + childStatus)
          childStatus
        })
    } else {
      Array()
    }
  }

  /**
   * Opens an {@link org.apache.hadoop.fs.FSDataOutputStream} for appending data to an existing file.
   * 
   * Due to immutable chunk write designs in cloud storage backends, this maps to an output
   * stream that creates a new version containing appended data.
   *
   * @param f          The target file {@link org.apache.hadoop.fs.Path}.
   * @param bufferSize The buffer size for stream allocation.
   * @param progress   The progress reporter callback.
   * @return An active {@link org.apache.hadoop.fs.FSDataOutputStream} positioned at the append boundary.
   */
  override def append(f: Path, bufferSize: Int, progress: Progressable): FSDataOutputStream = new FSDataOutputStream(
          new AltaStataHadoopOutputStream(new AltaStataChunkedOutputStream(getAltaStataCloudFileName(f), System.currentTimeMillis)(account)), new FileSystem.Statistics(ALTASTATA))

  /**
   * Opens an {@link org.apache.hadoop.fs.FSDataOutputStream} for creating a brand-new file or 
   * overwriting an existing file path.
   * 
   * Writes are executed in multi-part chunks. Encrypts all payloads client-side on the fly prior
   * to cloud transport.
   *
   * @param f           The target {@link org.apache.hadoop.fs.Path} to create.
   * @param permission  The file permissions (ignored, defaults are applied).
   * @param overwrite   If {@code true}, allows overwriting an existing path.
   * @param bufferSize  The internal stream buffer size.
   * @param replication The replication factor for HDFS compatibility (ignored).
   * @param blockSize   The block size for file system layout (ignored).
   * @param progress    The progress reporter callback (ignored).
   * @return A new {@link org.apache.hadoop.fs.FSDataOutputStream} for streaming output data.
   */
  override def createNonRecursive(f: Path, permission: FsPermission, overwrite: Boolean, bufferSize: Int, replication: Short, blockSize: Long, progress: Progressable): FSDataOutputStream = {
    create(f, permission, overwrite, bufferSize, replication, blockSize, progress)
  }

  override def create(f: Path, 
      permission: FsPermission, 
      overwrite: Boolean, 
      bufferSize: Int, 
      replication: Short, 
      blockSize: Long, 
      progress: Progressable): FSDataOutputStream = new FSDataOutputStream(
          new AltaStataHadoopOutputStream(new AltaStataChunkedOutputStream(getAltaStataCloudFileName(f), System.currentTimeMillis)(account)), new FileSystem.Statistics(ALTASTATA))

  /**
   * Renames an existing file or directory from {@code src} to {@code dst}.
   * 
   * This handles both individual files and directory hierarchies:
   * - Performs sanity validation on source and destination paths (e.g., source must exist,
   *   destination cannot be inside source).
   * - Scans and retrieves all files matching the source prefix.
   * - Resolves creation and modification timestamps for state integrity.
   * - Renames either a single object or re-keys all files within a prefix hierarchy.
   * - Returns whether the rename operation completed successfully.
   *
   * @param src The source {@link org.apache.hadoop.fs.Path} to be renamed.
   * @param dst The target destination {@link org.apache.hadoop.fs.Path}.
   * @return {@code true} if the rename successfully completed, {@code false} if it failed.
   */
  override def rename(src: Path, dst: Path): Boolean = {
    logger.info(s"rename: " + src + " to " + dst)
        
    val absoluteSrcPathString = getAltaStataCloudFileName(src)
    val absoluteDstPathString = getAltaStataCloudFileName(dst)
    
    if (absoluteDstPathString.equals(absoluteSrcPathString)) {
      return isFile(src)
    }

    // Nested-directory guard only: `dir` → `dir/child`. Do not use a raw startsWith —
    // that also rejects same-dir file renames like `file` → `file2`.
    if (absoluteDstPathString.startsWith(absoluteSrcPathString + "/")) {
      return false
    }
                
    val srcCloudFiles: Array[CloudFile] = listCloudFilesUnder(absoluteSrcPathString)

    if (srcCloudFiles.isEmpty) {
      logger.info(s"rename: source $src does not physically exist (virtual dir), returning true")
      return true
    }

    val ifSrcIsFile = srcCloudFiles.exists(_.getPath == absoluteSrcPathString)
    val filesToRename = if (ifSrcIsFile) srcCloudFiles.filter(_.getPath == absoluteSrcPathString) else srcCloudFiles

    val timestamps = account.getFileSystemHandler().detectTimestamps(filesToRename, false)

    // check if dst path exist
    val dstArray: Array[CloudFile] = listCloudFilesUnder(absoluteDstPathString)

    val ifDstIsFile = dstArray.exists(_.getPath == absoluteDstPathString)

    // return false if dst is an existing file
    if (ifDstIsFile) {
      return false
    }
    
    val results = 
      // File: swap the full path so same-directory rename (`dir/a` → `dir/b`)
      // and cross-directory (`.tmp/file` → `file`) both change the name.
      // Parent-only prefixes are equal in the same-dir case and left the file unmoved.
      if (ifSrcIsFile) {
        account.fileSystemModel.renameCloudFiles(filesToRename, absoluteSrcPathString, absoluteDstPathString, timestamps)
      }
      else {
        // if directory, check if dst is empty (new) or not
        if (dstArray.isEmpty) { // if new, just replace the whole name
          account.fileSystemModel.renameCloudFiles(filesToRename, getAltaStataCloudFileName(src), getAltaStataCloudFileName(dst), timestamps)
        }
        else { // if not new, add all src as subdirectory for dst
          account.fileSystemModel.renameCloudFiles(filesToRename, getAltaStataCloudFileName(src.getParent), getAltaStataCloudFileName(dst), timestamps)
        }
      }
    
    if (results == null || results.isEmpty) {
      return false
    }
    
    results(0).getOperationState match {
      case OperationState.DONE => return true
      case OperationState.ERROR => return false
      case _ => return false
    }
  }

  /**
   * Opens an {@link org.apache.hadoop.fs.FSDataInputStream} for reading an existing file.
   * 
   * This instantiates an underlying {@link AltaStataHadoopInputStream} that performs on-the-fly
   * decryption, block buffering, and parallel chunk prefetching.
   *
   * @param f          The target file {@link org.apache.hadoop.fs.Path} to open.
   * @param bufferSize The size of the read buffer to pass (ignored).
   * @return A standard {@link org.apache.hadoop.fs.FSDataInputStream} ready for streaming reads.
   */
  override def open(f: Path, bufferSize: Int): FSDataInputStream = {
    logger.info(s"open: " + f + " conf: " + getConf() + " bufferSize: " + bufferSize)

    new FSDataInputStream(
        new FSDataInputStream(
            new AltaStataHadoopInputStream(getAltaStataCloudFileName(f), 0, Constants.CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM)(account)));
  }

  /**
   * Deletes a file or directory path from the filesystem.
   * 
   * If the target is a virtual directory, all recursive children matching its prefix are deleted 
   * only if {@code recursive} is set to {@code true}. Single files are deleted directly regardless 
   * of the recursive flag.
   *
   * @param f         The target {@link org.apache.hadoop.fs.Path} to delete.
   * @param recursive If {@code true}, recursively deletes all nested items when deleting a directory.
   * @return {@code true} if deletion was fully successful, {@code false} if any errors occurred.
   */
  override def delete(f: Path, recursive: Boolean): Boolean = {
    logger.info(s"delete: " + f + " recursive: " + recursive)

    val absolutePathString = getAltaStataCloudFileName(f)
    
    val list: Array[CloudFile] = listCloudFilesUnder(absolutePathString)

    // if empty list
    if (list.isEmpty) {
      return true
    }

    val exact = list.filter(_.getPath == absolutePathString)
    val cloudFilesToDelete = if (recursive) {
      list
    } else if (exact.nonEmpty) {
      exact
    } else {
      return false
    }
        
    val timestamps = account.getFileSystemHandler().detectTimestamps(cloudFilesToDelete, false)
    
    val deleteResults = account.fileSystemModel.deleteCloudFiles(cloudFilesToDelete, timestamps)
      
    var status = true
    for (deleteResult <- deleteResults) {
      deleteResult.getOperationState match {
        case OperationState.DONE => logger.info(s"deleted: " + deleteResult.getCloudFileVersionPath)
        case OperationState.ERROR => status = false
      }
    }
      
    return status
  }

  /**
   * Creates directory hierarchies in the filesystem.
   * 
   * Since AltaStata uses a flat key-value namespace structure, directories are implicitly 
   * managed via path prefixes. Thus, creating directories is a non-blocking no-op.
   * We cache the creation in memory so subsequent getFileStatus calls return a valid directory.
   *
   * @param f          The directory {@link org.apache.hadoop.fs.Path} to create.
   * @param permission The file permissions to assign (ignored).
   * @return Always returns {@code true} indicating success.
   */
  override def mkdirs(f: Path, permission: FsPermission): Boolean = {
    createdDirectories.put(getAltaStataCloudFileName(f), System.currentTimeMillis())
    // Also cache parent directories just in case
    var parent = f.getParent
    while (parent != null) {
      createdDirectories.putIfAbsent(getAltaStataCloudFileName(parent), System.currentTimeMillis())
      parent = parent.getParent
    }
    true
  }
  
  /**
   * Configures Access Control Lists (ACLs) on the specified file or directory path.
   * 
   * Resolves target objects and grants permissions recursively by sharing the corresponding 
   * cloud metadata with targeted usernames/user handles extracted from the ACL entries list.
   *
   * @param path    The targeted file or directory {@link org.apache.hadoop.fs.Path}.
   * @param aclSpec A list of {@link org.apache.hadoop.fs.permission.AclEntry} objects containing permission specifiers.
   */
  override def setAcl(path: Path, aclSpec: java.util.List[AclEntry]) = {    
    val absolutePathString = getAltaStataCloudFileName(path)
  
    val cloudFilesToShare = listCloudFilesUnder(absolutePathString).toList
  
    val timeStampsFilterToShare = scala.collection.mutable.Set[java.lang.Long]()

    // filter in only the last versions
    for (cloudFile <- cloudFilesToShare) {
      var lastVersion = cloudFile.getBestMatchingVersionAttributes(System.currentTimeMillis)
      timeStampsFilterToShare += lastVersion.getCreateTime
    }
      
    val users = aclSpec.asScala.map(acl => acl.getName).toArray
    
    val shareResults = 
        account.fileSystemModel.shareCloudFiles(cloudFilesToShare.toArray, 
                                              users,
                                              timeStampsFilterToShare.toList.asJava)

    for (shareResult <- shareResults) {
      shareResult.getOperationState match {
        case OperationState.DONE => logger.info(s"\tsetAcl filtered in: " + shareResult.getCloudFileVersionPath)
        case OperationState.ERROR => logger.info(s"\tsetAcl filtered out: " + shareResult.getCloudFileVersionPath + " dues to: " + shareResult.getError)
      }
    }
  }

  /**
   * Renders a human-readable string representation of this FileSystem instance.
   *
   * @return The formatted identification string.
   */
  override def toString(): String = s"AltaStata FileSystem for ${account.MY_USER}";
  
  /**
   * Helper utility to locate and retrieve a {@link CloudFile} instance by Hadoop Path.
   *
   * @param f The {@link org.apache.hadoop.fs.Path} to query.
   * @return The corresponding {@link CloudFile} metadata object.
   * @throws java.io.FileNotFoundException If the path cannot be resolved in cloud metadata.
   */
  private def findCloudFile(f: Path): CloudFile = {
   val absolutePathString = getAltaStataCloudFileName(f)
   listCloudFilesUnder(absolutePathString).find(_.getPath == absolutePathString) match {
     case Some(cloudFile) => cloudFile
     case None => throw new FileNotFoundException(absolutePathString)
   }
  }

  /**
   * {@code listCloudFiles} is a key prefix listing, so {@code file} also returns {@code file2}.
   * Keep the exact path and true children ({@code path/...}) only.
   */
  private def listCloudFilesUnder(absolutePath: String): Array[CloudFile] = {
    val listIt = account.fileSystemModel.listCloudFiles(absolutePath, true)
    if (listIt == null) {
      return Array.empty
    }
    // Root catalog key is empty: every object is a child, and `startsWith("/")` matches none.
    if (absolutePath.isEmpty) {
      return listIt.asScala.toArray
    }
    listIt.asScala.filter { cf =>
      val p = cf.getPath
      p == absolutePath || p.startsWith(absolutePath + "/")
    }.toArray
  }

  /**
   * Helper utility to convert a standard Hadoop Path into a clean absolute object key
   * representation used by AltaStata backend systems (removing schemes, authorities, and leading slashes).
   *
   * @param f The source {@link org.apache.hadoop.fs.Path}.
   * @return The clean, relative-style absolute object key name string.
   */
  private def getAltaStataCloudFileName(f: Path) = {
    Path.getPathWithoutSchemeAndAuthority(makeAbsolute(f)).toString().substring(1)
  }
  
  /**
   * Helper utility to resolve relative Hadoop Paths into qualified absolute Paths
   * relative to the filesystem's current active working directory.
   *
   * @param path The {@link org.apache.hadoop.fs.Path} to resolve.
   * @return A qualified absolute {@link org.apache.hadoop.fs.Path}.
   */
  private def makeAbsolute(path: Path): Path = {
    if (path.isAbsolute()) {
      return path
    }
    return new Path(workingDir, path)
  }
}
