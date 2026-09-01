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

package com.altastata.utils

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

class AltaStataConfig {

  val ALTASTATA_HOME = {
    var userHome = System.getProperty("user.home")

    println(s"User Home: ${userHome}")

    val absoluteUserHome = Paths.get(userHome).toAbsolutePath.toString

    absoluteUserHome + File.separator + ".altastata"
  }

  /**
   * Generates and writes a default logback.xml configuration file under the hidden `.altastata` home subdirectory.
   *
   * @param appType the application subfolder directory name (e.g. "ui", "s3-gateway")
   */
  def initLogbackConfigPath(appType: String): Unit = {

    val filePath = ALTASTATA_HOME + File.separator + appType + File.separator + "logback.xml"

    val configContent =
      s"""
         |<configuration>
         |  <!-- Console Appender -->
         |  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
         |    <encoder>
         |      <pattern>%d{yyyy/MM/dd HH:mm:ss.S} %-5level [%thread] %logger{36} - %msg%n</pattern>
         |    </encoder>
         |  </appender>
         |
         |  <timestamp key="timestamp" datePattern="yyyyMMdd"/>
         |
         |  <!-- File Appender with Dynamic Filename -->
         |  <appender name="file" class="ch.qos.logback.core.FileAppender">
         |    <file>${"$"}{user.home:-.}/.altastata/$appType/logs/logfile-${"$"}{timestamp}.log</file>
         |    <encoder>
         |      <pattern>%d{yyyy/MM/dd HH:mm:ss.S} %-5level [%thread] %logger{36} - %msg%n</pattern>
         |    </encoder>
         |  </appender>
         |
         |  <!-- Logger Configuration -->
         |  <logger name="org.apache.http" level="ERROR"/>
         |  <logger name="software.amazon" level="ERROR"/>
         |
         |  <!-- Root Logger -->
         |  <root level="INFO">
         |    <appender-ref ref="console"/>
         |    <appender-ref ref="file"/>
         |  </root>
         |</configuration>
       """.stripMargin

    val path = Paths.get(filePath)

    // Check if the file exists
    if (!Files.exists(path)) {
      try {
        // Create the file and write the content
        Files.createDirectories(path.getParent) // Create parent directories if they don't exist
        Files.write(path, configContent.getBytes(StandardCharsets.UTF_8))
        println(s"Configuration file created at: $filePath")

        val accounts = Paths.get(ALTASTATA_HOME + File.separator + "accounts")
        if (!Files.exists(accounts)) {
          Files.createDirectories(accounts)
          println(s"Created: $accounts")
        }

        val logs = Paths.get(path.getParent + File.separator + "logs")
        if (!Files.exists(logs)) {
          Files.createDirectories(logs)
          println(s"Created: $logs")
        }

      } catch {
        case e: IOException => println(s"Error writing file: ${e.getMessage}")
      }
    } else {
      println(s"File already exists: $filePath")
    }

    System.setProperty("logback.configurationFile", filePath)
  }

}

