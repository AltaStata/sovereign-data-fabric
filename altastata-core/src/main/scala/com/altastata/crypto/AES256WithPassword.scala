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

package com.altastata.crypto

import org.bouncycastle.util.encoders.Base64

import java.security.SecureRandom
import javax.crypto.spec.{IvParameterSpec, PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKeyFactory}

object AES256WithPassword {
  val AES_KEY_SIZE = 256 // Key size in bits
  val ITERATIONS = 65536
  val SALT_SIZE = 16
  val IV_SIZE = 16

  /**
   * Generates a random salt for cryptographic operations.
   *
   * @return A random byte array of SALT_SIZE
   */
  def generateSalt(): Array[Byte] = {
    val salt = new Array[Byte](SALT_SIZE)
    new SecureRandom().nextBytes(salt)
    salt
  }

  /**
   * Derives a key from the password and salt using PBKDF2.
   *
   * @param password The user-provided password
   * @param salt The salt used in the key derivation
   * @return A SecretKeySpec instance containing the derived key
   */
  def deriveKey(password: String, salt: Array[Byte]): SecretKeySpec = {
    val keySpec = new PBEKeySpec(password.toCharArray, salt, ITERATIONS, AES_KEY_SIZE)
    val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val secretKey = keyFactory.generateSecret(keySpec)
    new SecretKeySpec(secretKey.getEncoded, "AES")
  }

  /**
   * Encrypts data and includes salt and IV in the output.
   *
   * @param data The data to be encrypted
   * @param password The password used for encryption
   * @return The encrypted byte array containing the salt, IV, and ciphertext
   */
  def encrypt(data: Array[Byte], password: String): Array[Byte] = {
    val salt = generateSalt()
    val key = deriveKey(password, salt)

    val iv = new Array[Byte](IV_SIZE)
    new SecureRandom().nextBytes(iv)
    val ivSpec = new IvParameterSpec(iv)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)

    val ciphertext = cipher.doFinal(data)

    // Combine salt, IV, and ciphertext into a single Base64 string
    salt ++ iv ++ ciphertext
  }

  /**
   * Decrypts data by parsing salt and IV from the input byte array.
   *
   * @param encryptedData The byte array containing the salt, IV, and ciphertext
   * @param password The password used for decryption
   * @return The decrypted byte array
   */
  def decrypt(encryptedData: Array[Byte], password: String): Array[Byte] = {

    // Extract salt, IV, and ciphertext
    val salt = encryptedData.slice(0, SALT_SIZE)
    val iv = encryptedData.slice(SALT_SIZE, SALT_SIZE + IV_SIZE)
    val ciphertext = encryptedData.slice(SALT_SIZE + IV_SIZE, encryptedData.length)

    val key = deriveKey(password, salt)
    val ivSpec = new IvParameterSpec(iv)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)

    cipher.doFinal(ciphertext)
  }

  /**
   * Main entry point to demonstrate encryption and decryption of sensitive data using PBKDF2
   * derived AES keys from a user-provided password.
   *
   * @param args command line arguments
   */
  def main(args: Array[String]): Unit = {
    val password = "securepassword"
    val data = "Sensitive data to encrypt"

    // Encrypt
    val encryptedData = encrypt(data.getBytes, password)
    println(s"Encrypted Data: " + Base64.toBase64String(encryptedData))

    // Decrypt
    val decryptedData = new String(decrypt(encryptedData, password), "UTF-8")
    println(s"Decrypted Data: $decryptedData")
  }
}
