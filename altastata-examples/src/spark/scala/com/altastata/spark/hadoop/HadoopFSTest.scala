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

package com.altastata.spark.hadoop

import org.apache.spark.sql.SparkSession
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import java.io.BufferedOutputStream
import java.net.URI
import com.altastata.utils.Account
import org.apache.hadoop.fs.permission.AclEntry

object HadoopFSTest extends App {

  val userProperties: String =
    """AWSSecretKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
      |myuser=catrina777
      |accounttype=amazon-s3-secure
      |AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE
      |region=us-east-1
      |kms-region=us-east-2
      |metadata-encryption=HSM
      |acccontainer-prefix=altastata-myorg321-
      |""".stripMargin
  
  val sparkBuilder = SparkSession.builder
    .appName("app_name")
    .master("local")
    // Various Params
    .getOrCreate()

  val hadoopConfig: Configuration = sparkBuilder.sparkContext.hadoopConfiguration

  hadoopConfig.set("fs.hdfs.impl", classOf[org.apache.hadoop.hdfs.DistributedFileSystem].getName)
  hadoopConfig.set("fs.file.impl", classOf[org.apache.hadoop.fs.LocalFileSystem].getName)
  hadoopConfig.set("fs.altastata.impl", classOf[org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem].getName)
  hadoopConfig.set("altastata.account.properties", userProperties)

  /**
   *  write to the file
   */
  
  val fs = FileSystem.get(new URI("altastata:///"), hadoopConfig)
    
  // Output file can be created from file system.
  val writeFilePath = new Path("altastata:///Applications/write_test.txt")
  
  if (fs.exists(writeFilePath))
    fs.delete(writeFilePath, true)
    
  val output = fs.create(writeFilePath)
  val os = new BufferedOutputStream(output)
  os.write("Hello World! ".getBytes("UTF-8"))
  os.close()

  /**
   *  append to the file
   */

  val outputAppend = fs.append(writeFilePath)
  val osAppend = new BufferedOutputStream(output)
  osAppend.write("\nPS. That was Alice".getBytes("UTF-8"))
  osAppend.close()
  
  fs.close()
  
  /**
   * Share with alice222
   */
    
  val aclList = AclEntry.parseAclSpec("user:alice222,user:catrina777", false)
  fs.setAcl(writeFilePath, aclList)
}
