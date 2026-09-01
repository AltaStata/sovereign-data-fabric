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

package com.altastata.filesystem.securecloud

/**
 * Interface defining operations for integration with hardware security modules (HSMs).
 * 
 * Provides key generation, object encryption, and decryption using HSM keys.
 */
trait CloudHSMHandler {

  /**
   * Generates or retrieves secure HSM keys for a designated user.
   *
   * @param userName the target user name
   * @param userType the type of the user
   * @return a tuple of (PublicKeyPEM, HSMKeyId)
   */
  def createHSMKeysForUser(userName: String, userType: String): (String, String) = ???

  /**
   * Encrypts a serialized byte array using HSM-stored keys.
   *
   * @param serialized the raw byte array data to encrypt
   * @param keys the HSM-specific key reference or configuration string
   * @return the encrypted byte array payload
   */
  def encryptObjectWithHSM(serialized: Array[Byte], keys: String): Array[Byte]
  
  /**
   * Decrypts an encrypted payload using the designated HSM-stored keys.
   *
   * @param encrypted the encrypted byte array data to decrypt
   * @param keys the HSM-specific key reference or configuration string
   * @return the decrypted raw byte array payload
   */
  def decryptObjectWithHSM(encrypted: Array[Byte], keys: String): Array[Byte]
}
