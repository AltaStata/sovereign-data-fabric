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

package com.altastata.cloud.ibm

import com.altastata.utils.Account
import org.slf4j.LoggerFactory

import java.io.{ByteArrayInputStream, File}
import java.security._
import java.util.{Base64, Properties}
import javax.crypto.Cipher
import scala.util.{Failure, Success, Try}

/**
 * SoftHSM2 Test for AltaStata HSM Integration
 * 
 * This test validates the IBMHPCSKeyManager functionality using SoftHSM2,
 * which provides a software-based PKCS#11 implementation for development.
 * 
 * Prerequisites:
 * 1. Install SoftHSM2:
 *    - macOS: brew install softhsm
 *    - Linux: apt-get install softhsm2
 * 
 * 2. Initialize token (run once):
 *    softhsm2-util --init-token --slot 0 --label "GREP11 Token" --pin 1234 --so-pin 1234
 * 
 * Usage:
 *    cd altastata-core
 *    ./gradlew run -PmainClass=com.altastata.cloud.ibm.SoftHSM2Test
 * 
 * Or from sbt/IDE:
 *    run com.altastata.cloud.ibm.SoftHSM2Test
 */
object SoftHSM2Test {

  private val logger = LoggerFactory.getLogger(getClass)
  
  // Test configuration
  private val TOKEN_LABEL = "GREP11 Token"
  private val USER_PIN = "1234"
  private val KEY_LABEL = "testuser"
  
  def main(args: Array[String]): Unit = {
    println("=" * 70)
    println("SoftHSM2 Test for AltaStata HSM Integration")
    println("=" * 70)
    println()
    
    // Find SoftHSM2 library
    val softHSMLibrary = findSoftHSMLibrary()
    
    if (softHSMLibrary.isEmpty) {
      println("❌ SoftHSM2 not found!")
      println()
      println("Please install SoftHSM2:")
      println("  macOS:  brew install softhsm")
      println("  Linux:  apt-get install softhsm2 (Debian/Ubuntu)")
      println("          yum install softhsm (RHEL/CentOS)")
      println()
      println("Then initialize a token:")
      println(s"""  softhsm2-util --init-token --slot 0 --label "$TOKEN_LABEL" --pin $USER_PIN --so-pin $USER_PIN""")
      System.exit(1)
    }
    
    println(s"✅ Found SoftHSM2 library: ${softHSMLibrary.get}")
    println()
    
    // Create mock account with SoftHSM2 configuration
    implicit val account: Account = createMockAccount(softHSMLibrary.get)
    
    // Run tests
    var passed = 0
    var failed = 0
    
    val tests = List(
      ("Check HPCS Configuration", () => testCheckConfiguration()),
      ("Initialize PKCS#11", () => testInitializePKCS11()),
      ("Generate Key Pair", () => testGenerateKeyPair()),
      ("Encrypt/Decrypt Cycle", () => testEncryptDecrypt()),
      ("Sign/Verify Cycle", () => testSignVerify()),
      ("Cleanup", () => testCleanup())
    )
    
    for ((name, testFn) <- tests) {
      print(s"Testing: $name... ")
      Try(testFn()) match {
        case Success(_) =>
          println("✅ PASSED")
          passed += 1
        case Failure(e) =>
          println(s"❌ FAILED: ${e.getMessage}")
          if (logger.isDebugEnabled) {
            e.printStackTrace()
          }
          failed += 1
      }
    }
    
    println()
    println("=" * 70)
    println(s"Results: $passed passed, $failed failed")
    println("=" * 70)
    
    if (failed > 0) {
      System.exit(1)
    }
  }
  
  /**
   * Find SoftHSM2 library on the system
   */
  private def findSoftHSMLibrary(): Option[String] = {
    // Check system property first (for Docker/container usage)
    val propPath = Option(System.getProperty("hpcs.pkcs11.library"))
    
    val candidates = List(
      // Linux (Debian/Ubuntu) - Docker container
      "/usr/lib/softhsm/libsofthsm2.so",
      "/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so",
      // Linux (RHEL/CentOS)
      "/usr/lib64/softhsm/libsofthsm2.so",
      // macOS Homebrew (Apple Silicon)
      "/opt/homebrew/lib/softhsm/libsofthsm2.so",
      "/opt/homebrew/Cellar/softhsm/2.6.1/lib/softhsm/libsofthsm2.so",
      // macOS Homebrew (Intel)
      "/usr/local/lib/softhsm/libsofthsm2.so",
      "/usr/local/Cellar/softhsm/2.6.1/lib/softhsm/libsofthsm2.so",
      // Custom installation
      "/opt/softhsm2/lib/softhsm/libsofthsm2.so"
    )
    
    // Also check environment variable
    val envPath = Option(System.getenv("SOFTHSM2_LIBRARY"))
    
    // Priority: system property > env var > auto-detect
    (propPath.toList ++ envPath.toList ++ candidates).find(path => new File(path).exists())
  }
  
  /**
   * Create a mock Account with SoftHSM2 configuration
   */
  private def createMockAccount(libraryPath: String): Account = {
    val account = new Account()
    
    // Set up properties for SoftHSM2
    val props = new Properties()
    props.setProperty("hpcs-pkcs11-library", libraryPath)
    props.setProperty("hpcs-user-pin", USER_PIN)
    props.setProperty("hpcs-token-space", TOKEN_LABEL)
    props.setProperty("hpcs-key-label", KEY_LABEL)
    props.setProperty("key-protection", "HPCS")
    props.setProperty("myuser", "testuser")
    
    // Use reflection to set userProps since it's private
    val field = classOf[Account].getDeclaredField("userProps")
    field.setAccessible(true)
    field.set(account, props)
    
    account
  }
  
  // ============ Test Methods ============
  
  private var keyManager: IBMHPCSKeyManager = _
  private var generatedPublicKey: PublicKey = _
  
  private def testCheckConfiguration()(implicit account: Account): Unit = {
    keyManager = new IBMHPCSKeyManager()
    
    // Should be configured
    assert(keyManager.isHPCSConfigured, "HPCS should be configured with SoftHSM2")
    
    // Validation should not throw
    keyManager.validateConfiguration()
  }
  
  private def testInitializePKCS11()(implicit account: Account): Unit = {
    // hasPrivateKey will trigger initialization
    // This may or may not find a key depending on prior runs
    val hasKey = keyManager.hasPrivateKey
    println(s" (existing key: $hasKey)")
  }
  
  private def testGenerateKeyPair()(implicit account: Account): Unit = {
    // Delete existing key if present (for clean test)
    if (keyManager.hasPrivateKey) {
      println(" (using existing key)")
      // Get existing public key
      generatedPublicKey = keyManager.getPublicKey().getOrElse {
        throw new RuntimeException("Could not retrieve existing public key")
      }
    } else {
      // Generate new key pair
      val (publicKeyPEM, tokenBytes) = keyManager.generateKeyPairInHPCS()
      
      assert(publicKeyPEM.contains("-----BEGIN PUBLIC KEY-----"), "Should return PEM-formatted public key")
      assert(tokenBytes.nonEmpty, "Should return token bytes")
      
      // Parse public key from PEM
      generatedPublicKey = pemToPublicKey(publicKeyPEM)
      
      println(s" (new key generated: ${new String(tokenBytes)})")
    }
    
    assert(generatedPublicKey != null, "Should have a public key")
  }
  
  private def testEncryptDecrypt()(implicit account: Account): Unit = {
    if (generatedPublicKey == null) {
      throw new RuntimeException("No public key available - run testGenerateKeyPair first")
    }
    
    // Test data
    val originalData = "Hello, SoftHSM2! This is a test of HSM encryption.".getBytes("UTF-8")
    
    // Encrypt with public key (local operation)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, generatedPublicKey)
    val encrypted = cipher.doFinal(originalData)
    
    assert(encrypted.length > 0, "Encrypted data should not be empty")
    assert(!java.util.Arrays.equals(encrypted, originalData), "Encrypted should differ from original")
    
    // Decrypt with HSM private key
    val decrypted = keyManager.unwrap(encrypted)
    
    assert(java.util.Arrays.equals(decrypted, originalData), 
      s"Decrypted should match original. Got: '${new String(decrypted)}', expected: '${new String(originalData)}'")
  }
  
  private def testSignVerify()(implicit account: Account): Unit = {
    if (generatedPublicKey == null) {
      throw new RuntimeException("No public key available - run testGenerateKeyPair first")
    }
    
    // Test data
    val dataToSign = "This data needs to be signed for integrity verification.".getBytes("UTF-8")
    
    // Sign with HSM private key
    val signature = keyManager.sign(dataToSign)
    
    assert(signature.length > 0, "Signature should not be empty")
    
    // Verify with public key (local operation)
    val isValid = keyManager.verify(generatedPublicKey, dataToSign, signature)
    
    assert(isValid, "Signature should be valid")
    
    // Verify that tampering fails
    val tamperedData = (new String(dataToSign) + " TAMPERED").getBytes("UTF-8")
    val isInvalid = !keyManager.verify(generatedPublicKey, tamperedData, signature)
    
    assert(isInvalid, "Tampered data should fail verification")
  }
  
  private def testCleanup()(implicit account: Account): Unit = {
    keyManager.cleanup()
  }
  
  // ============ Helper Methods ============
  
  /**
   * Convert PEM-formatted public key to PublicKey object
   */
  private def pemToPublicKey(pem: String): PublicKey = {
    val publicKeyPEM = pem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")
    
    val encoded = Base64.getDecoder.decode(publicKeyPEM)
    val keySpec = new java.security.spec.X509EncodedKeySpec(encoded)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(keySpec)
  }
}
