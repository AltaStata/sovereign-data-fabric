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

import com.altastata.utils.Constants
import org.slf4j.LoggerFactory

import java.security.{Key, PrivateKey}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

// TODO: should be replaced by java.util.Base64 to work on Android (see the other files)
// I did not do it it now, because I do not have a time to check with RSA/None/NoPadding
import org.apache.commons.codec.binary.Base64

import java.security.PublicKey
import scala.collection.mutable.ListBuffer
import com.altastata.utils.Account

trait SecurityUtils extends AsymmetricCryptoHandler with AES256Impl with SHA256MessageDigest {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Get RSA block size (in bytes) from public key.
   * RSA 1024 -> 128 bytes, RSA 4096 -> 512 bytes
   */
  def getRSABlockSize(key: PublicKey): Int = {
    key.asInstanceOf[RSAPublicKey].getModulus.bitLength / 8
  }

  /**
   * Get RSA block size (in bytes) from private key.
   * RSA 1024 -> 128 bytes, RSA 4096 -> 512 bytes
   */
  def getRSABlockSize(key: PrivateKey): Int = {
    key.asInstanceOf[RSAPrivateKey].getModulus.bitLength / 8
  }

  /**
   * Applies envelope encryption to a byte array using RSA.
   *
   * It generates a random 256-bit AES key, encrypts the payload using AES-GCM, and then 
   * encrypts the AES key itself using the provided RSA public key. The resulting byte array 
   * concatenates the AES ciphertext, the initialization vector (IV), and the encrypted AES key.
   *
   * @param array The plaintext data to encrypt.
   * @param rsaKey The public RSA key to encrypt the symmetric key.
   * @param transformation The RSA padding scheme (e.g., "RSA/ECB/PKCS1Padding").
   * @return A consolidated byte array containing [AES Ciphertext | IV | RSA-encrypted AES Key].
   */
  def encryptArrayWithRSA(array: Array[Byte], rsaKey: PublicKey, transformation: String): Array[Byte] = {
    val aesKey = getSecureRandomBytes(256 / 8)
    val encryptedAESKey = encryptRSA(rsaKey, aesKey, transformation)
    val aesGCMEncrypted = encryptAES_GCM(array, aesKey).get
    
    Array.concat(aesGCMEncrypted.ciphertext, aesGCMEncrypted.iv, encryptedAESKey)
  }

  /**
   * Decrypts an envelope-encrypted byte array using RSA.
   *
   * It extracts the encrypted AES key from the end of the array based on the RSA block size 
   * (handling both 1024-bit and 4096-bit keys automatically). It decrypts the AES key using 
   * either the local private key or a Hardware Security Module (IBM HPCS) if `key-protection=HPCS`.
   * It then decrypts the payload using AES-GCM (with a fallback to AES-CBC for backward compatibility).
   *
   * @param array The consolidated byte array [AES Ciphertext | IV | RSA-encrypted AES Key].
   * @param transformation The RSA padding scheme used for decryption.
   * @param account The implicitly passed user account holding the private key or HSM context.
   * @return The decrypted plaintext array.
   */
  def decryptArrayWithRSA(array: Array[Byte], transformation: String)(implicit account: Account): Array[Byte] = {
    val (rsaBlockSize, preloadedKey) = account.getProperty("key-protection") match {
      case "HPCS" =>
        val manager = account.hpcsKeyManager
        if (manager == null) throw new IllegalStateException("HPCS key manager not initialized (key-protection=HPCS but setPassword did not create manager)")
        (manager.getRSABlockSize(), null: PrivateKey)
      case _ =>
        val privateKey = loadRSAPrivateKey()
        (getRSABlockSize(privateKey), privateKey)
    }

    if (array.length < rsaBlockSize + Constants.AES_GCM_IV_SIZE) {
      throw new SecurityException(
        s"Malformed RSA envelope: ${array.length} bytes, need at least ${rsaBlockSize + Constants.AES_GCM_IV_SIZE}")
    }

    val encryptedAesKey = array.takeRight(rsaBlockSize)
    val aesKey = decryptRSA(encryptedAesKey, transformation, preloadedKey)

    val data = array.take(array.length - Constants.AES_GCM_IV_SIZE - rsaBlockSize)
    val iv = array.slice(array.length - Constants.AES_GCM_IV_SIZE - rsaBlockSize, array.length - rsaBlockSize)

    // Only GCM is supported. CBC fallback has been removed for security reasons (CWE-327).
    decryptAES_GCM(data, aesKey, iv).get
  }

  /**
   * Applies envelope encryption using the Post-Quantum Cryptographic (PQC) Kyber algorithm.
   *
   * It generates a random AES key, encrypts the payload via AES-GCM, and encapsulates the AES key 
   * using the provided Kyber public key. 
   *
   * @param array The plaintext data to encrypt.
   * @param kyberPublicKey The PQC public key.
   * @return A consolidated byte array containing [AES Ciphertext | IV | AES Key | Kyber Encapsulation].
   */
  def encryptArrayWithKyber(array: Array[Byte], kyberPublicKey: PublicKey): Array[Byte] = {
    val aesKey = getSecureRandomBytes(256 / 8)
    val (encryptedAESKey, kyberEncapsulation) = encryptKyber(kyberPublicKey, aesKey)
    val aesGCMEncrypted = encryptAES_GCM(array, aesKey).get

    Array.concat(aesGCMEncrypted.ciphertext, aesGCMEncrypted.iv, encryptedAESKey, kyberEncapsulation)
  }

  /**
   * Decrypts an envelope-encrypted byte array using Kyber PQC.
   *
   * It splits out the Kyber encapsulation and the encrypted AES key from the end of the array, 
   * unwraps the AES key using the local Kyber private key, and then decrypts the payload via AES-GCM.
   *
   * @param encryptedArray The consolidated byte array.
   * @param privateKey An explicitly provided Kyber private key (optional).
   * @param account The implicitly passed user account holding the private key.
   * @return The decrypted plaintext array.
   */
  def decryptArrayWithKyber(encryptedArray: Array[Byte], privateKey: PrivateKey = null)(implicit account: Account): Array[Byte] = {
    val minLength = Constants.AES_GCM_IV_SIZE + 48 + 1568
    if (encryptedArray.length < minLength) {
      throw new SecurityException(
        s"Malformed Kyber envelope: ${encryptedArray.length} bytes, need at least ${minLength}")
    }

    val encryptedAesKey = encryptedArray.slice(encryptedArray.length - 48 - 1568, encryptedArray.length - 1568)
    val kyberEncapsulation = encryptedArray.takeRight(1568)

    val aesKey = decryptKyber(encryptedAesKey, kyberEncapsulation, privateKey)

    val ciphertext = encryptedArray.take(encryptedArray.length - Constants.AES_GCM_IV_SIZE - 48 - 1568)
    val iv = encryptedArray.slice(encryptedArray.length - Constants.AES_GCM_IV_SIZE - 48 - 1568, encryptedArray.length - 48 - 1568)

    // Only GCM is supported. CBC fallback has been removed for security reasons (CWE-327).
    decryptAES_GCM(ciphertext, aesKey, iv).get
  }

  /**
   * Applies envelope encryption to a byte array using a Hardware Security Module (HSM).
   *
   * It generates a random 256-bit AES key, encrypts the data payload locally using AES-GCM, 
   * and delegates the encryption of the AES key to the HSM (e.g., IBM HPCS).
   *
   * @param array The plaintext data to encrypt.
   * @param keys The identifier or label for the HSM key wrapping key.
   * @param account The implicitly passed user account holding the HSM handler.
   * @return A consolidated byte array containing [Key Length (4 bytes) | IV | HSM-encrypted AES Key | AES Ciphertext].
   */
  def encryptArrayWithHSM(array: Array[Byte], keys: String)(implicit account: Account): Array[Byte] = {
    // Generate a 256-bit AES key
    val aesKey = getSecureRandomBytes(256 / 8)

    // Encrypt the AES key using the HSM
    val encryptedAESKey = account.cloudHSMHandler.encryptObjectWithHSM(aesKey, keys)

    // Combine both into a single Array[Byte]
    val keyLengthBytes = BigInt(encryptedAESKey.length).toByteArray
    val keyLengthPadded = Array.fill(4 - keyLengthBytes.length)(0.toByte) ++ keyLengthBytes

    // Encrypt the data with AES-CBC mode
    val aesGCMEncrypted = encryptAES_GCM(array, aesKey).get

    // Combine key length [4 bytes], IV, encrypted AES key, and the encrypted data
    Array.concat(keyLengthPadded, aesGCMEncrypted.iv, encryptedAESKey, aesGCMEncrypted.ciphertext)
  }

  /**
   * Decrypts an envelope-encrypted byte array using a Hardware Security Module (HSM).
   *
   * It parses the array to extract the HSM-encrypted AES key and delegates its decryption to 
   * the HSM handler. Once the raw AES key is retrieved, the data payload is decrypted locally using AES-GCM.
   *
   * @param encryptedArray The consolidated byte array.
   * @param keys The identifier or label for the HSM key wrapping key.
   * @param account The implicitly passed user account holding the HSM handler.
   * @return The decrypted plaintext array.
   */
  def decryptArrayWithHSM(encryptedArray: Array[Byte], keys: String)(implicit account: Account): Array[Byte] = {
    if (encryptedArray.length < 4 + Constants.AES_GCM_IV_SIZE) {
      throw new SecurityException(
        s"Malformed HSM envelope: ${encryptedArray.length} bytes, need at least ${4 + Constants.AES_GCM_IV_SIZE}")
    }

    // Step 1: Extract the AES key length (first 4 bytes)
    val keyLengthBytes = encryptedArray.take(4)
    val encryptedAESKeyLength = BigInt(keyLengthBytes).toInt

    // The length field comes from the (attacker-visible) envelope: reject values that do not
    // fit the actual buffer instead of letting slice/drop silently misparse the layout.
    if (encryptedAESKeyLength <= 0 ||
        encryptedAESKeyLength > encryptedArray.length - 4 - Constants.AES_GCM_IV_SIZE) {
      throw new SecurityException(
        s"Malformed HSM envelope: declared key length ${encryptedAESKeyLength} does not fit ${encryptedArray.length} bytes")
    }

    // Step 2: Extract the IV, encrypted AES key, and the encrypted data
    val iv = encryptedArray.slice(4, 4 + Constants.AES_GCM_IV_SIZE)
    val encryptedAESKey = encryptedArray.slice(4 + Constants.AES_GCM_IV_SIZE,
                                               4 + Constants.AES_GCM_IV_SIZE + encryptedAESKeyLength)
    val ciphertext = encryptedArray.drop(4 + Constants.AES_GCM_IV_SIZE + encryptedAESKeyLength)

    // Step 3: Decrypt the AES key using HSM
    val aesKey = account.cloudHSMHandler.decryptObjectWithHSM(encryptedAESKey, keys)

    // Step 4: Decrypt the data using AES-CBC mode
    decryptAES_GCM(ciphertext, aesKey, iv).get
  }

  /**
   * Encrypts and obfuscates a logical file path to prevent leaking directory structures to the cloud provider.
   *
   * Algorithm:
   * It splits the path by `/`. For each part `i`, it calculates a new AES key:
   * `aesKeyI = aesKeyI-1 XOR sha256(dirI)`
   * Then it encrypts `dirI` using AES-CBC with `aesKeyI`.
   * Finally, if `withSuffix` is true, the last computed `aesKeyN` is encrypted with the user's public RSA key 
   * and appended to the path as a suffix.
   *
   * @param path The plaintext logical path (e.g., "folder/file.txt").
   * @param withSuffix If true, appends the RSA-encrypted final AES key to allow decryption.
   * @param rsaKey An optional explicit RSA public key. If null, the account's loaded key is used.
   * @return The encrypted and Base64URL-safe string representing the obfuscated path.
   */
  def encryptObjectPathIfNeeded(path: String, withSuffix: Boolean = true, rsaKey: PublicKey = null)(implicit account: Account): String = {
    if (path == "") {
      return path
    }
    
    account.isEncryptNames match {
      case false => return path
      case true => {
        try {
    
          val parts = path.split("/")
          var encryptedParts = ListBuffer[Array[Byte]]()
          var aesKey: Array[Byte] = Array.fill(32){0}
              
          for (part <- parts) {
            // prevAesKey ^ calculateDigest(part)
            aesKey = (aesKey.toList zip calculateDigest(part).toList).map(elements => (elements._1 ^ elements._2).toByte).toArray
                
            // add an encrypted part to the path
            // INTENTIONAL: Static IV (Array.fill) is used here by design to make path encryption deterministic.
            // This is required so we can look up exact paths later without keeping a database of IVs.
            // Path obfuscation is not as cryptographically critical as the actual data payload.
            // Must match decryptObjectPathIfNeeded (UTF-8). Bare getBytes() breaks non-ASCII path segments on Windows.
            encryptedParts += encryptAES_CBC(part.getBytes("UTF-8"), aesKey, Array.fill(16){0}).get.ciphertext
          }
              
          // reduce encryptedParts and add the last calculated aesKey to the end encrypted with a public RSA key
          val prefix = encryptedParts.map { Base64.encodeBase64URLSafeString(_) }.reduce((part1, part2) => part1 + "/" + part2)
          
          withSuffix match {
            case true => {
              val (myPublicRsaKey, _) =
                decodeRSAPublicKeyFromCertForUser(
                  account.fileSystemModel.retrieveUserdata(account.MY_USER).publicKeyCert.get.getBytes,
                  account.MY_USER,
                  checkEndDate = false,
                  account
                )

              return prefix + "/" + Base64.encodeBase64URLSafeString(encryptRSA(if (rsaKey == null) myPublicRsaKey else rsaKey, aesKey, "RSA/None/NoPadding"))
            }
            case false => return prefix
          }
        }
        catch {
          case t: Throwable => logger.error("cannot encrypt path: " + path, t); return null
        }
      }
    }
  }

  /**
   * Algorithm:
   * aesKeyN = privateKey(suffix)
   * ...
   * dirI = aesDecr(encryptedDirI, aesKeyI); aesKeyI-1 = aesKeyI XOR sha256(dirI)
   */
  def decryptObjectPathIfNeeded(encryptedPath: String)(implicit account: Account): String = {
    account.isEncryptNames match {
      case false => return encryptedPath
      case true => {
        try {
          val encryptedParts = encryptedPath.split("/").map { Base64.decodeBase64(_) }
            
          var parts = ListBuffer[String]()
          var aesKey: Array[Byte] = decryptRSA(encryptedParts.last, "RSA/None/NoPadding")
          
          // add 0's if the size of the array less than 32
          while (aesKey.length < 32) {
            aesKey = 0.toByte +: aesKey
          }

          for (encryptedPart <- encryptedParts.reverse.slice(1, encryptedParts.length)) {
            
            // insert a decrypted part to the beginning of the path
            val part = new String(decryptAES_CBC(encryptedPart, aesKey, Array.fill(Constants.AES_CBC_IV_SIZE){0}).get, "UTF-8")
            parts.insert(0, part)
    
            // prevAesKey ^ calculateDigest(part)
            aesKey = (aesKey.toList zip calculateDigest(part).toList).map(elements => (elements._1 ^ elements._2).toByte).toArray
          }
                    
          // reduce parts
          return parts.reduce((part1, part2) => part1 + "/" + part2)
        }
        catch {
          case t: Throwable => logger.error("bad encryptedPath: " + encryptedPath, t); return null
        }
      }
    }
  }
    
  /**
   * Re-encrypts an encrypted object path (derived symmetrically via hierarchical key derivation)
   * specifically for another user by decrypting the leaf envelope key using our private key
   * and wrapping it under the recipient's RSA public key.
   *
   * @param encryptedPath the encrypted hierarchically nested path
   * @param rsaPublicKey the public key of the target recipient user
   * @param account the implicit account context for decrypting the path envelope
   * @return the re-encrypted path suitable for the recipient
   */
  def reencryptObjectPathForOtherUser(encryptedPath: String, rsaPublicKey: PublicKey)(implicit account: Account): String = {
    var encryptedParts = encryptedPath.split("/").map { Base64.decodeBase64(_) }
    val encryptedAesKey = encryptedParts.last
    
    val aesKey = decryptRSA(encryptedAesKey, "RSA/None/NoPadding")
    val reencryptedAesKey = encryptRSA(rsaPublicKey, aesKey, "RSA/None/NoPadding")
    
    encryptedParts(encryptedParts.length - 1) = reencryptedAesKey
    
    encryptedParts.map { Base64.encodeBase64URLSafeString(_) }.reduce((part1, part2) => part1 + "/" + part2)
  }

}
