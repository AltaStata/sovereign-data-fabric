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

package com.altastata.cloud.trustee

import java.util.Properties
import java.net.{HttpURLConnection, URL}
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}
import scala.collection.mutable.ListBuffer
import org.slf4j.LoggerFactory

/**
 * Trustee Property Resolver for Confidential Containers
 * 
 * Resolves properties with "Trustee.*" prefix by fetching secrets from 
 * Confidential Containers Trustee CDH (Confidential Data Hub) API.
 * 
 * Based on the RATS (Remote ATtestation procedureS) model, secrets are 
 * gated by Trustee based on attestation. When a secret is requested via 
 * CDH API, automatic attestation occurs - if attestation fails, the 
 * request will fail.
 * 
 * Usage:
 *   val resolver = new TrusteePropertyResolver(userProps)
 *   resolver.resolveTrusteeProperties()
 *   val password = resolver.extractPassword()
 * 
 * @see https://www.redhat.com/en/blog/introducing-confidential-containers-trustee-attestation-services-solution-overview-and-use-cases
 */
object TrusteePropertyResolver {
  /** Only these account properties may be filled from Trustee.* URLs. */
  val AllowedPropertyNames: Set[String] = Set(
    "AWSAccessKeyId",
    "AWSSecretKey",
    "Password",
    "password"
  )

  /** Local CDH endpoints only (Confidential Containers sidecar). */
  val AllowedHosts: Set[String] = Set("127.0.0.1", "localhost", "[::1]")

  val MaxSecretBytes: Int = 64 * 1024

  private[trustee] def validateTrusteeUrl(trusteeUrl: String): URL = {
    val url = new URL(trusteeUrl)
    if (url.getProtocol != "http") {
      throw new IllegalArgumentException(s"Trustee URL must use http: ${url.getProtocol}")
    }
    if (url.getUserInfo != null) {
      throw new IllegalArgumentException("Trustee URL must not contain userinfo")
    }
    val host = Option(url.getHost).getOrElse("")
    if (!AllowedHosts.contains(host)) {
      throw new IllegalArgumentException(s"Trustee URL host not allowlisted: $host")
    }
    val path = Option(url.getPath).getOrElse("")
    if (!path.startsWith("/cdh/")) {
      throw new IllegalArgumentException(s"Trustee URL path must start with /cdh/: $path")
    }
    url
  }
}

class TrusteePropertyResolver(private val userProps: Properties) {
  
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Resolve Trustee properties by fetching secrets from CDH API.
   * Properties with prefix "Trustee.*" are replaced with actual values fetched from Trustee.
   * 
   * @throws RuntimeException if Trustee resolution fails (e.g., attestation failure)
   */
  def resolveTrusteeProperties(): Unit = {
    val trusteeEnabled = userProps.getProperty("CoCo_Trustee_Enabled")
    
    if (trusteeEnabled == null || !trusteeEnabled.equalsIgnoreCase("true")) {
      logger.debug("Trustee resolution is disabled (CoCo_Trustee_Enabled != true)")
      return
    }

    logger.info("Trustee resolution enabled - resolving Trustee.* properties")

    val keys = userProps.propertyNames()
    val trusteeProperties = new ListBuffer[(String, String)]() // (propertyName, trusteeUrl)

    // Collect all Trustee.* properties
    while (keys.hasMoreElements) {
      val key = keys.nextElement().toString
      if (key.startsWith("Trustee.")) {
        val propertyName = key.substring("Trustee.".length) // Remove "Trustee." prefix
        val trusteeUrl = userProps.getProperty(key)
        if (trusteeUrl != null && trusteeUrl.nonEmpty) {
          trusteeProperties += ((propertyName, trusteeUrl))
        } else {
          logger.warn(s"Trustee property ${key} has empty URL")
        }
      }
    }

    if (trusteeProperties.isEmpty) {
      logger.debug("No Trustee.* properties found to resolve")
      return
    }

    logger.info(s"Found ${trusteeProperties.size} Trustee properties to resolve")

    // Resolve each Trustee property
    trusteeProperties.foreach { case (propertyName, trusteeUrl) =>
      Try {
        if (!TrusteePropertyResolver.AllowedPropertyNames.contains(propertyName)) {
          throw new IllegalArgumentException(s"Trustee property name not allowlisted: $propertyName")
        }
        val secretValue = fetchSecretFromTrustee(trusteeUrl)
        
        // Remove the Trustee.* property
        userProps.remove(s"Trustee.${propertyName}")
        
        // Set the resolved value
        userProps.setProperty(propertyName, secretValue)
        
        logger.info(s"Resolved Trustee.${propertyName}")
      } match {
        case Success(_) => // Successfully resolved
        case Failure(e) => 
          logger.error(s"Failed to resolve Trustee.${propertyName}: ${e.getMessage}", e)
          throw new RuntimeException(s"Trustee attestation failed for ${propertyName}: ${e.getMessage}", e)
      }
    }

    logger.info(s"Successfully resolved ${trusteeProperties.size} Trustee properties")
  }

  /**
   * Fetch secret from Trustee CDH API.
   * This triggers automatic attestation - if attestation fails, the request will fail.
   * 
   * @param trusteeUrl Full CDH resource URL (e.g., http://127.0.0.1:8006/cdh/resource/default/credentials/AWSAccessKeyId)
   * @return Secret value as string
   * @throws RuntimeException if attestation fails or connection error occurs
   */
  def fetchSecretFromTrustee(trusteeUrl: String): String = {
    var connection: HttpURLConnection = null
    var reader: BufferedReader = null

    try {
      val url = TrusteePropertyResolver.validateTrusteeUrl(trusteeUrl)
      logger.debug(s"Fetching secret from Trustee endpoint: ${url.getProtocol}://${url.getHost}:${url.getPort}")
      connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setInstanceFollowRedirects(false)
      connection.setRequestMethod("GET")
      connection.setConnectTimeout(10000) // 10 seconds
      connection.setReadTimeout(30000)    // 30 seconds

      val responseCode = connection.getResponseCode

      if (responseCode == HttpURLConnection.HTTP_OK) {
        reader = new BufferedReader(new InputStreamReader(connection.getInputStream, StandardCharsets.UTF_8))
        val response = new StringBuilder()
        var line: String = null
        var total = 0
        while ({ line = reader.readLine(); line != null }) {
          total += line.length
          if (total > TrusteePropertyResolver.MaxSecretBytes) {
            throw new RuntimeException("Trustee CDH response exceeds size limit")
          }
          response.append(line)
        }
        val secretValue = response.toString().trim
        logger.debug(s"Successfully fetched secret from Trustee (length: ${secretValue.length})")
        secretValue
      } else {
        val errorMessage = s"Trustee CDH API returned HTTP ${responseCode}"
        logger.error(s"${errorMessage} for host ${url.getHost}")
        throw new RuntimeException(s"${errorMessage}. This may indicate attestation failure.")
      }
    } catch {
      case e: IllegalArgumentException =>
        throw e
      case e: java.net.ConnectException =>
        logger.error(s"Cannot connect to Trustee CDH API: ${e.getMessage}")
        throw new RuntimeException(s"Cannot connect to Trustee CDH API. Is CDH running in the CoCo pod?", e)
      case e: java.net.SocketTimeoutException =>
        logger.error(s"Timeout connecting to Trustee CDH API: ${e.getMessage}")
        throw new RuntimeException(s"Timeout connecting to Trustee CDH API. Attestation may be taking too long.", e)
      case e: Exception =>
        logger.error(s"Error fetching secret from Trustee (${e.getClass.getSimpleName})")
        throw new RuntimeException(s"Failed to fetch secret from Trustee: ${e.getMessage}", e)
    } finally {
      if (reader != null) Try { reader.close() }
      if (connection != null) Try { connection.disconnect() }
    }
  }

  /**
   * Extract password from resolved Trustee properties if Trustee.Password was present.
   * Checks for both "Password" and "password" (case-insensitive).
   * 
   * @return Password as string, or null if not found
   */
  def extractPassword(): String = {
    // Check for Password property (case-insensitive)
    val passwordKeys = List("Password", "password")
    var password: String = null
    
    for (key <- passwordKeys if password == null) {
      val value = userProps.getProperty(key)
      if (value != null && value.nonEmpty) {
        password = value
      }
    }
    
    if (password != null && password.nonEmpty) {
      logger.info("Password found in properties (from Trustee or direct)")
      password
    } else {
      logger.debug("No Password property found")
      null
    }
  }

  /**
   * Check attestation status from Trustee CDH API.
   * 
   * @param attestationStatusUrl URL for attestation status (default: http://127.0.0.1:8006/cdh/resource/default/attestation-status/status)
   * @return true if attestation status is "success", false otherwise
   */
  def checkAttestationStatus(attestationStatusUrl: String = "http://127.0.0.1:8006/cdh/resource/default/attestation-status/status"): Boolean = {
    Try {
      val statusJson = fetchSecretFromTrustee(attestationStatusUrl)
      // Simple check - in production you might want to parse JSON properly
      statusJson.contains("\"status\"") && statusJson.contains("success")
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.warn(s"Failed to check attestation status: ${e.getMessage}")
        false
    }
  }
}

