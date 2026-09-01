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
import java.net.{ServerSocket, Socket}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import scala.util.Try
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

/**
 * Simulation tests for TrusteePropertyResolver
 * 
 * These tests simulate the CDH API using a simple HTTP server, allowing
 * full integration testing without requiring actual CoCo pods.
 * 
 * The mock CDH server simulates:
 * - Successful secret retrieval (HTTP 200)
 * - Attestation failure (HTTP 403)
 * - Secret not found (HTTP 404)
 * - Server errors (HTTP 500)
 * - Connection timeouts
 * 
 * Usage:
 *   gradle test --tests "*TrusteePropertyResolverSimulationTest"
 */
@RunWith(classOf[JUnitRunner])
class TrusteePropertyResolverSimulationTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  
  private var mockServer: MockCDHServer = _
  private var serverPort: Int = _
  
  override def beforeEach(): Unit = {
    // Start mock CDH server on a random port
    mockServer = new MockCDHServer()
    serverPort = mockServer.start()
  }
  
  override def afterEach(): Unit = {
    // Stop mock server
    if (mockServer != null) {
      mockServer.stop()
    }
  }
  
  "TrusteePropertyResolver" should "resolve properties from mock CDH API successfully" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.AWSAccessKeyId", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/AWSAccessKeyId")
    props.setProperty("Trustee.AWSSecretKey", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/AWSSecretKey")
    
    // Configure mock server to return success
    mockServer.setResponse("/cdh/resource/default/credentials/AWSAccessKeyId", 200, "AKIAIOSFODNN7EXAMPLE")
    mockServer.setResponse("/cdh/resource/default/credentials/AWSSecretKey", 200, "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    
    val resolver = new TrusteePropertyResolver(props)
    resolver.resolveTrusteeProperties()
    
    // Verify Trustee.* properties are removed
    props.getProperty("Trustee.AWSAccessKeyId") should be(null)
    props.getProperty("Trustee.AWSSecretKey") should be(null)
    
    // Verify resolved properties are set
    props.getProperty("AWSAccessKeyId") should be("AKIAIOSFODNN7EXAMPLE")
    props.getProperty("AWSSecretKey") should be("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
  }
  
  it should "extract password from resolved Trustee properties" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/Password")
    
    mockServer.setResponse("/cdh/resource/default/credentials/Password", 200, "my-secret-password-123")
    
    val resolver = new TrusteePropertyResolver(props)
    resolver.resolveTrusteeProperties()
    
    val password = resolver.extractPassword()
    password should be("my-secret-password-123")
  }
  
  it should "fail when CDH API returns attestation failure (403)" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/invalid/Password")
    
    mockServer.setResponse("/cdh/resource/default/invalid/Password", 403, "Attestation failed")
    
    val resolver = new TrusteePropertyResolver(props)
    
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    
    exception.getMessage should include("Trustee CDH API returned HTTP 403")
  }
  
  it should "fail when secret is not found (404)" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/missing/Password")
    
    mockServer.setResponse("/cdh/resource/default/missing/Password", 404, "Secret not found")
    
    val resolver = new TrusteePropertyResolver(props)
    
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    
    exception.getMessage should include("Trustee CDH API returned HTTP 404")
  }
  
  it should "fail when CDH API returns server error (500)" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/error/Password")
    
    mockServer.setResponse("/cdh/resource/default/error/Password", 500, "Internal server error")
    
    val resolver = new TrusteePropertyResolver(props)
    
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    
    exception.getMessage should include("Trustee CDH API returned HTTP 500")
  }

  it should "reject non-allowlisted property names" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.EvilProp", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/EvilProp")

    val resolver = new TrusteePropertyResolver(props)
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    exception.getMessage should include("not allowlisted")
  }

  it should "reject non-loopback Trustee hosts" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", "http://169.254.169.254/cdh/resource/default/credentials/Password")

    val resolver = new TrusteePropertyResolver(props)
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    exception.getMessage should include("not allowlisted")
  }
  
  it should "handle multiple Trustee properties correctly" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.AWSAccessKeyId", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/AWSAccessKeyId")
    props.setProperty("Trustee.AWSSecretKey", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/AWSSecretKey")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/credentials/Password")
    props.setProperty("region", "us-east-1") // Non-Trustee property
    
    mockServer.setResponse("/cdh/resource/default/credentials/AWSAccessKeyId", 200, "AKIAIOSFODNN7EXAMPLE")
    mockServer.setResponse("/cdh/resource/default/credentials/AWSSecretKey", 200, "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    mockServer.setResponse("/cdh/resource/default/credentials/Password", 200, "my-password")
    
    val resolver = new TrusteePropertyResolver(props)
    resolver.resolveTrusteeProperties()
    
    // All Trustee properties should be resolved
    props.getProperty("AWSAccessKeyId") should be("AKIAIOSFODNN7EXAMPLE")
    props.getProperty("AWSSecretKey") should be("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    props.getProperty("Password") should be("my-password")
    
    // Non-Trustee property should remain unchanged
    props.getProperty("region") should be("us-east-1")
    
    // Trustee.* properties should be removed
    props.getProperty("Trustee.AWSAccessKeyId") should be(null)
    props.getProperty("Trustee.AWSSecretKey") should be(null)
    props.getProperty("Trustee.Password") should be(null)
  }
  
  it should "check attestation status successfully" in {
    val attestationStatusUrl = s"http://127.0.0.1:${serverPort}/cdh/resource/default/attestation-status/status"
    mockServer.setResponse("/cdh/resource/default/attestation-status/status", 200, """{"status":"success"}""")
    
    val resolver = new TrusteePropertyResolver(new Properties())
    val status = resolver.checkAttestationStatus(attestationStatusUrl)
    
    status should be(true)
  }
  
  it should "return false when attestation status check fails" in {
    val attestationStatusUrl = s"http://127.0.0.1:${serverPort}/cdh/resource/default/attestation-status/status"
    mockServer.setResponse("/cdh/resource/default/attestation-status/status", 403, """{"status":"failed"}""")
    
    val resolver = new TrusteePropertyResolver(new Properties())
    val status = resolver.checkAttestationStatus(attestationStatusUrl)
    
    status should be(false)
  }
  
  it should "fail immediately when a property fails (stops processing)" in {
    val props = new Properties()
    props.setProperty("CoCo_Trustee_Enabled", "true")
    props.setProperty("Trustee.Password", s"http://127.0.0.1:${serverPort}/cdh/resource/default/invalid/Password")
    
    mockServer.setResponse("/cdh/resource/default/invalid/Password", 403, "Attestation failed")
    
    val resolver = new TrusteePropertyResolver(props)
    
    // Should throw exception on failure
    val exception = intercept[RuntimeException] {
      resolver.resolveTrusteeProperties()
    }
    
    exception.getMessage should include("Trustee attestation failed")
    exception.getMessage should include("Password")
  }
}

/**
 * Simple mock HTTP server that simulates CDH API responses
 */
class MockCDHServer {
  private var serverSocket: ServerSocket = _
  private var serverThread: Thread = _
  private var responses: Map[String, (Int, String)] = Map.empty
  private var running: Boolean = false
  
  def start(): Int = {
    serverSocket = new ServerSocket(0) // Use port 0 to get a random available port
    val port = serverSocket.getLocalPort
    running = true
    
    serverThread = new Thread(() => {
      while (running) {
        try {
          val clientSocket = serverSocket.accept()
          new Thread(() => handleRequest(clientSocket)).start()
        } catch {
          case _: Exception if !running => // Server stopped
        }
      }
    })
    serverThread.setDaemon(true)
    serverThread.start()
    
    port
  }
  
  def stop(): Unit = {
    running = false
    if (serverSocket != null && !serverSocket.isClosed) {
      serverSocket.close()
    }
    if (serverThread != null) {
      serverThread.interrupt()
    }
  }
  
  def setResponse(path: String, statusCode: Int, body: String): Unit = {
    responses = responses + (path -> (statusCode, body))
  }
  
  private def handleRequest(clientSocket: Socket): Unit = {
    try {
      val in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream))
      val out = new PrintWriter(clientSocket.getOutputStream, true)
      
      // Read request line
      val requestLine = in.readLine()
      if (requestLine != null) {
        val path = extractPath(requestLine)
        val (statusCode, body) = responses.getOrElse(path, (404, "Not found"))
        
        // Send HTTP response
        out.println(s"HTTP/1.1 $statusCode ${statusText(statusCode)}")
        out.println("Content-Type: text/plain")
        out.println(s"Content-Length: ${body.length}")
        out.println("Connection: close")
        out.println()
        out.println(body)
        out.flush()
      }
    } catch {
      case e: Exception => // Ignore errors
    } finally {
      try { clientSocket.close() } catch { case _: Exception => }
    }
  }
  
  private def extractPath(requestLine: String): String = {
    val parts = requestLine.split(" ")
    if (parts.length >= 2) {
      val fullPath = parts(1)
      // Remove query string if present
      val path = if (fullPath.contains("?")) fullPath.substring(0, fullPath.indexOf("?")) else fullPath
      path
    } else {
      "/"
    }
  }
  
  private def statusText(code: Int): String = code match {
    case 200 => "OK"
    case 403 => "Forbidden"
    case 404 => "Not Found"
    case 500 => "Internal Server Error"
    case _ => "Unknown"
  }
}

