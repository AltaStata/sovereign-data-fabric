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

/**  
 *  Copyright 2015 White Label Personal Clouds Pty Ltd
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.altastata.crypto

import com.altastata.filesystem.DataDecryptionException
import com.altastata.utils.Constants

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import java.nio.ByteBuffer
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

import javax.crypto.{Cipher, KeyGenerator}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import org.slf4j.LoggerFactory

/**
 * Provides core symmetric encryption capabilities using AES-256.
 *
 * This trait is mixed into the `SecurityUtils` and forms the foundation for data chunk encryption.
 * It supports two modes:
 * - **AES-GCM (Galois/Counter Mode)**: The primary, modern mode offering both confidentiality and authenticated data (AEAD).
 * - **AES-CBC (Cipher Block Chaining)**: A legacy mode retained primarily for backward compatibility with older data chunks.
 *
 * Both modes utilize 256-bit symmetric keys.
 */
trait AES256Impl {

  private val logger = LoggerFactory.getLogger(getClass)

  // CBC case classes and functions
  case class AESEncrypted(ciphertext: Array[Byte], iv: Array[Byte])

  /**
   * Generate secure random bytes using [[java.security.SecureRandom]]
   *
   * @param size Number of bytes to generate
   */
  def getSecureRandomBytes(size: Int): Array[Byte] = {
    val iv = new Array[Byte](size)
    new java.security.SecureRandom().nextBytes(iv)

    iv
  }

  /**
   * Encrypt the plaintext using AES.
   *
   * By  for the initialisation vector.
   *
   * @param plaintext The plaintext to be encrypted
   * @param key The key to use for encryption
   * @param iv The initialisation vector to use for encrypting. Must be 16 bytes. (Default: uses [[getSecureRandomBytes]])
   *
   * @return Future containing the encrypted ciphertext and initialisation vector used
   */
  def encryptAES_CBC(plaintext: Array[Byte], key: Array[Byte], iv: Array[Byte] = getSecureRandomBytes(Constants.AES_CBC_IV_SIZE)): Try[AESEncrypted] = {
    //      assert key.length == 32; // 32 bytes == 256 bits
    getCipherAES_CBC(key, iv, true) match {
      case Success(cipher) => applyCipher_CBC(plaintext, cipher) map { AESEncrypted(_, iv) }
      case Failure(e)      => throw e
    }
  }

  /**
   * Decrypt the ciphertext using AES.
   *
   * Note: Key size of 32 bytes/256 bits == AES256
   *
   * @param ciphertext The ciphertext to be decrypted
   * @param key The key to use for decryption
   * @param iv The initialisation vector used when encrypting
   *
   * @return Future containing the encrypted ciphertext and initialisation vector used
   */
  def decryptAES_CBC(ciphertext: Array[Byte], key: Array[Byte], iv: Array[Byte]): Try[Array[Byte]] = {
    for {
      cipher <- Try(getCipherAES_CBC(key, iv, false)).flatten
      result <- applyCipher_CBC(ciphertext, cipher)
    } yield result
  }.recoverWith {
    case e: Throwable => Failure(DataDecryptionException(e))
  }

  /**
   * Initializes and returns a BouncyCastle PaddedBufferedBlockCipher for AES-CBC.
   *
   * @param key           The 256-bit AES secret key.
   * @param iv            The 16-byte initialization vector.
   * @param forEncryption True to configure the cipher for encryption, false for decryption.
   * @return A Try wrapping the initialized PaddedBufferedBlockCipher.
   */
  private def getCipherAES_CBC(key: Array[Byte], iv: Array[Byte], forEncryption: Boolean): Try[PaddedBufferedBlockCipher] = {
    Try {
      val cipher: PaddedBufferedBlockCipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()))
      cipher.init(forEncryption, new ParametersWithIV(new KeyParameter(key), iv))
      cipher
    }
  }

  /**
   * Helper that does the body of the cipher/uncipher process, depending on how the passed in cipher is configured.
   *
   * Note: If you want something that "Just Works" then look at something like [[encrypt_AES_CBC]]/[[decrypt_AES_CBC]]
   *
   * @param bb ByteBuffer containing your plaintext/ciphertext
   * @param cipher A preconfigured and init'd PaddedBufferedBlockCipher
   *
   * @return Future containing the ciphered/unciphered bytes of bb
   */
  private def applyCipher_CBC(inputBytes: Array[Byte], cipher: PaddedBufferedBlockCipher): Try[Array[Byte]] = {
    Try {
      val outputBytes = new Array[Byte](cipher.getOutputSize(inputBytes.length))

      val outputLen1 = cipher.processBytes(inputBytes, 0, inputBytes.length, outputBytes, 0)
      val outputLen2 = cipher.doFinal(outputBytes, outputLen1)
      val actualOutputLen = outputLen1 + outputLen2

      val finalBytes = new Array[Byte](actualOutputLen)
      Array.copy(outputBytes, 0, finalBytes, 0, finalBytes.length)

      finalBytes
    }
  }

  /**
   * Encrypts the plaintext using AES-GCM (Galois/Counter Mode).
   *
   * GCM is the preferred symmetric encryption mode in AltaStata because it provides both 
   * confidentiality and data authenticity. It uses no padding (`NoPadding`).
   *
   * @param plaintext The raw byte array of data to encrypt.
   * @param key The 256-bit (32 bytes) AES secret key.
   * @param iv The initialization vector (nonce). Default is generated securely via [[getSecureRandomBytes]].
   * @return A `Try` containing the `AESEncrypted` wrapper with the resulting ciphertext and IV.
   */
  def encryptAES_GCM(plaintext: Array[Byte], key: Array[Byte], iv: Array[Byte] = getSecureRandomBytes(Constants.AES_GCM_IV_SIZE)): Try[AESEncrypted] = {
    Try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val keySpec = new SecretKeySpec(key, "AES")
      val gcmSpec = new GCMParameterSpec(Constants.GCM_TAG_LENGTH_IN_BITS, iv)
      
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
      val ciphertext = cipher.doFinal(plaintext)

      AESEncrypted(ciphertext, iv)
    }
  }

  /**
   * Decrypts the ciphertext using AES-GCM (Galois/Counter Mode).
   *
   * In addition to decrypting the data, GCM inherently verifies the authentication tag.
   * If the ciphertext or IV has been tampered with, the cipher will throw an AEADBadTagException,
   * which is caught and wrapped in a `DataDecryptionException`.
   *
   * @param ciphertext The encrypted data to decrypt.
   * @param key The 256-bit (32 bytes) AES secret key used for encryption.
   * @param iv The initialization vector (nonce) used during encryption.
   * @return A `Try` containing the decrypted plaintext byte array.
   */
  def decryptAES_GCM(ciphertext: Array[Byte], key: Array[Byte], iv: Array[Byte]): Try[Array[Byte]] = {
    Try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val keySpec = new SecretKeySpec(key, "AES")
      val gcmSpec = new GCMParameterSpec(Constants.GCM_TAG_LENGTH_IN_BITS, iv)
      
      cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
      cipher.doFinal(ciphertext)
    }.recoverWith {
      case e: Throwable => Failure(DataDecryptionException(e))
    }
  }
}

object AES256Object extends AES256Impl {
    import scala.concurrent.ExecutionContext.Implicits.global

    /**
     * Main entry point to run local verification and benchmark tests on AES-CBC and AES-GCM
     * implementations within this object.
     *
     * @param args command line arguments
     */
    def main(args: Array[String]): Unit = {
      val key = getSecureRandomBytes(256 / 8)

      val initialBuffer = "Hello, world! My name is Дофига? Когда луна наденет свой ночной колпак! Hi, I'm again.".getBytes

      System.out.println("\n=== Testing AES-CBC ===")
      System.out.println("initialBuffer.length: " + initialBuffer.length)

      val encryptedCBC = encryptAES_CBC(initialBuffer, key).get
      System.out.println("CBC encrypted.length: " + encryptedCBC.ciphertext.length)

      val base32 = new org.apache.commons.codec.binary.Base32()
      val base32EncodeCypherText = base32.encodeAsString(encryptedCBC.ciphertext)
      System.out.println("CBC base32 encoded: " + base32EncodeCypherText)
      System.out.println("CBC base32 encoded size: " + base32EncodeCypherText.length)
      
      val decoded = base32.decode(base32EncodeCypherText.getBytes)
      val decryptedCBC = decryptAES_CBC(decoded, key, encryptedCBC.iv).get
      System.out.println("CBC decrypted: " + new String(decryptedCBC))

      System.out.println("\n=== Testing AES-GCM ===")
      System.out.println("initialBuffer.length: " + initialBuffer.length)

      val encryptedGCM = encryptAES_GCM(initialBuffer, key).get
      System.out.println("GCM encrypted.length: " + encryptedGCM.ciphertext.length)

      val base32EncodeGCM = base32.encodeAsString(encryptedGCM.ciphertext)
      System.out.println("GCM base32 encoded: " + base32EncodeGCM)
      System.out.println("GCM base32 encoded size: " + base32EncodeGCM.length)
      
      val decodedGCM = base32.decode(base32EncodeGCM.getBytes)
      val decryptedGCM = decryptAES_GCM(decodedGCM, key, encryptedGCM.iv).get
      System.out.println("GCM decrypted: " + new String(decryptedGCM))

      // Compare results
      System.out.println("\n=== Comparison ===")
      System.out.println("Original and CBC decrypted match: " + 
        java.util.Arrays.equals(initialBuffer, decryptedCBC))
      System.out.println("Original and GCM decrypted match: " + 
        java.util.Arrays.equals(initialBuffer, decryptedGCM))
      System.out.println("CBC and GCM encrypted lengths: " + 
        encryptedCBC.ciphertext.length + " vs " + encryptedGCM.ciphertext.length)
    }
}
