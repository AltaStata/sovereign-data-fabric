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

package com.altastata.cloud.amazon_java2

import com.altastata.filesystem.securecloud.CloudUserCreatingHandler
import com.altastata.utils.Account

import java.security.PublicKey
import java.util.{Base64, Properties}

class AmazonUserCreatingManager(var properties: Properties)(implicit account: Account) extends CloudUserCreatingHandler {

  /**
   * Validates if a given string matches the standard AWS Access Key ID format constraint.
   *
   * @param key the AWS Access Key ID string to validate
   * @return true if the key is structurally valid; false otherwise
   */
  def isValidAWSAccessKey(key: String): Boolean = {
    // AWS Access Key ID is typically 20 alphanumeric characters, starting with "AKIA" or "ASIA"
    key != null && key.matches("^(AKIA|ASIA)[A-Z0-9]{16}$")
  }

  override def enhanceUserPropertiesIfNeeded(publicKey: PublicKey): Properties = {
    val encryption = properties.getProperty("metadata-encryption")
    if (encryption != null &&
      (encryption.equalsIgnoreCase("RSA") || encryption.equalsIgnoreCase("PQC"))) {

      val accessKeyId = properties.getProperty("AWSAccessKeyId")
      val secretKey = properties.getProperty("AWSSecretKey")
      if (accessKeyId != null && secretKey != null &&
        accessKeyId.nonEmpty && secretKey.nonEmpty) {

        if (isValidAWSAccessKey(accessKeyId)) {
          properties.setProperty("AWSSecretKey", account.encryptProperty(secretKey, publicKey, encryption))
          properties.setProperty("AWSAccessKeyId", account.encryptProperty(accessKeyId, publicKey, encryption))
        } else if (!isLikelyEncryptedProperty(accessKeyId)) {
          // Do not persist a plaintext secret when the access key failed format validation.
          throw new IllegalArgumentException(
            "AWSAccessKeyId is not a valid AWS access key and does not look encrypted; " +
              "refusing to leave AWSSecretKey in plaintext"
          )
        }
      }
    }

    properties
  }

  /** encryptProperty stores Base64 ciphertext; plaintext AWS keys match isValidAWSAccessKey instead. */
  private[amazon_java2] def isLikelyEncryptedProperty(value: String): Boolean = {
    try {
      val decoded = Base64.getDecoder.decode(value)
      decoded != null && decoded.length > 32
    } catch {
      case _: IllegalArgumentException => false
    }
  }
}
