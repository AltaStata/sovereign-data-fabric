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

import org.slf4j.LoggerFactory
import org.bouncycastle.crypto.digests.MD5Digest
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.bouncycastle.crypto.digests.SHA256Digest
import scala.collection.mutable.ListBuffer
import com.altastata.utils.Account
import java.security.Key


/**
 * Trait providing utility methods for calculating SHA-256 message digests.
 * 
 * Offers synchronized thread-safe calculation on byte arrays and string inputs.
 */
trait SHA256MessageDigest {

  private val logger = LoggerFactory.getLogger(getClass)

  val digest = new SHA256Digest

  /**
   * Calculates the SHA-256 message digest for a given string input (UTF-8 bytes).
   *
   * @param src The source string to hash.
   * @return A 32-byte SHA-256 hash.
   */
  def calculateDigest (src: String): Array[Byte] = {
    calculateDigest (src.getBytes("UTF-8"))
  }

  /**
   * Calculates the SHA-256 message digest for a given raw byte array.
   * Internally synchronizes on the SHA256Digest engine to ensure thread safety.
   *
   * @param buffer The input byte array to hash.
   * @return A 32-byte SHA-256 hash.
   */
  def calculateDigest (buffer: Array[Byte]): Array[Byte] = {
    digest.synchronized {

      digest.update(buffer, 0, buffer.length)
  	  val hashBytes = new Array[Byte](digest.getDigestSize())
      digest.doFinal(hashBytes, 0)
  
      hashBytes
    }
  }
}
