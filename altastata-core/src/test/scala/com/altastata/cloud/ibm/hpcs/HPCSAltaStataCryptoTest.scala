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

/*
 * Standalone test: uses AltaStata crypto (Account.encryptProperty, getAndDecryptProperty,
 * encryptArrayWithRSA, decryptArrayWithRSA, sign, verify) with HPCS.
 * Exercises the modified SecurityUtils/AsymmetricCryptoHandler/IBMHPCSKeyManager code.
 *
 * Usage: [accountDir]
 *   Default: ~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev
 *
 * Requires: GREP11_YAML or hpcs-yaml-path in account's .user.properties
 */

package com.altastata.cloud.ibm.hpcs

import com.altastata.crypto.AsymmetricKeysGenerator
import com.altastata.filesystem.securecloud.SecureCloudFileSystemModel
import com.altastata.utils.{Account, Constants}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Base64

object HPCSAltaStataCryptoTest {

  private val SEP = "=" * 70 + "\n"
  private val DEFAULT_ACCOUNT_DIR =
    System.getProperty("user.home") + "/.altastata/accounts/amazon.rsa.hpcs.hpcsdev"

  def main(args: Array[String]): Unit = {
    val accountDir = if (args.nonEmpty && !args(0).contains("path/to")) args(0) else DEFAULT_ACCOUNT_DIR

    if (!Files.exists(Paths.get(accountDir, "public.key")) ||
        !Files.exists(Paths.get(accountDir, "hpcs-privkey.blob"))) {
      System.err.println(s"Account dir must have public.key and hpcs-privkey.blob: $accountDir")
      System.err.println("Create keys with: ./gradlew :altastata-core:runHPCSCreateKey -PaccountDir=<dir>")
      System.exit(1)
    }

    val yamlPath = resolveGrep11Yaml(accountDir)
    if (yamlPath == null) {
      System.err.println("Set GREP11_YAML or hpcs-yaml-path in account properties.")
      System.exit(1)
    }

    println(SEP + "HPCS AltaStata Crypto Test (encrypt/decrypt string, array, sign/verify)\n" + SEP)
    println("Account dir: " + accountDir)
    println("GREP11 YAML: " + yamlPath + "\n")

    val account = new Account()
    val errors = account.loadAccountProperties(accountDir)
    if (errors.nonEmpty) {
      errors.foreach(e => System.err.println("  " + e))
    }

    if (account.userProps.getProperty("key-protection") != "HPCS") {
      System.err.println("Account must have key-protection=HPCS")
      System.exit(1)
    }

    // Ensure HPCS is initialized (setPassword triggers it)
    account.setPassword(Array.emptyCharArray)

    implicit val acc: Account = account
    val model = account.secureCloudFileSystemModel

    // --- 1. String encrypt/decrypt via Account.encryptProperty / getAndDecryptProperty ---
    println("--- 1. String encrypt/decrypt (encryptProperty / getAndDecryptProperty) ---")
    val plainStr = "Test credential value for HPCS " + System.currentTimeMillis
    val publicKey = AsymmetricKeysGenerator.extractRSAPublicKeyFromPEM(
      new String(Files.readAllBytes(Paths.get(accountDir, "public.key")), StandardCharsets.UTF_8)
    )
    val encryptedStr = account.encryptProperty(plainStr, publicKey, "RSA")
    println(s"  Encrypted (base64 length): ${encryptedStr.length}")

    account.userProps.setProperty("test-cred", encryptedStr)
    val decryptedStr = account.getAndDecryptProperty("test-cred")
    if (plainStr != decryptedStr) {
      throw new AssertionError(s"String decrypt mismatch: '$decryptedStr'")
    }
    println("  Decrypted: OK")
    println(s"  Plaintext: $plainStr\n")

    // --- 2. Array encrypt/decrypt via encryptArrayWithRSA / decryptArrayWithRSA ---
    println("--- 2. Array encrypt/decrypt (encryptArrayWithRSA / decryptArrayWithRSA) ---")
    val plainArray = "Array test data for HPCS.".getBytes(StandardCharsets.UTF_8)
    val encryptedArray = model.encryptArrayWithRSA(plainArray, publicKey, Constants.RSA_OAEP)
    println(s"  Encrypted array length: ${encryptedArray.length} (hybrid: ciphertext+IV+encryptedAESKey)")

    val decryptedArray = model.decryptArrayWithRSA(encryptedArray, Constants.RSA_OAEP)
    val decryptedStr2 = new String(decryptedArray, StandardCharsets.UTF_8)
    if (!java.util.Arrays.equals(plainArray, decryptedArray)) {
      throw new AssertionError(s"Array decrypt mismatch: '$decryptedStr2'")
    }
    println("  Decrypted: OK")
    println(s"  Plaintext: $decryptedStr2\n")

    // --- 3. Sign with HPCS / verify with public key ---
    println("--- 3. Sign (HPCS) / Verify (public key) ---")
    val dataToSign = "Data to sign with HPCS.".getBytes(StandardCharsets.UTF_8)
    val signature = model.signStringWithRSA(dataToSign)
    println(s"  Signature length: ${signature.length} bytes")

    val verified = AsymmetricKeysGenerator.verifySignatureWithRSA(publicKey, dataToSign, signature)
    if (!verified) {
      throw new AssertionError("Signature verification failed.")
    }
    println("  Verified: OK\n")

    println(SEP + "SUCCESS: All HPCS crypto operations passed.\n" + SEP)
  }

  private def resolveGrep11Yaml(accountDir: String): String = {
    var path = System.getenv("GREP11_YAML")
    if (path != null && !path.isEmpty && Files.exists(Paths.get(path))) {
      return Paths.get(path).toAbsolutePath.normalize.toString
    }
    val propsFile = Paths.get(accountDir).toFile.listFiles()
      .filter(f => f.getName.endsWith("user.properties"))
      .headOption
    if (propsFile.isDefined) {
      val p = new java.util.Properties()
      p.load(new java.io.FileReader(propsFile.get))
      path = p.getProperty("hpcs-yaml-path")
      if (path != null && !path.isEmpty && Files.exists(Paths.get(path))) {
        return Paths.get(path).toAbsolutePath.normalize.toString
      }
    }
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize
    var dir = cwd
    while (dir != null) {
      val children = Option(dir.toFile.listFiles()).getOrElse(Array.empty)
      children.find { child =>
        child.isDirectory &&
          child.getName != "altastata-core" &&
          new java.io.File(child, "grep11client.yaml").isFile
      }.foreach { child =>
        return new java.io.File(child, "grep11client.yaml").toPath.toAbsolutePath.normalize.toString
      }
      dir = dir.getParent
    }
    null
  }
}
