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

package com.altastata.utils

import java.io.ByteArrayOutputStream

object Constants {

  /** RSA envelope padding used for metadata/key wrapping. */
  val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

  val AES_GCM_IV_SIZE = 12 // 96-bit IV is recommended for GCM
  val AES_CBC_IV_SIZE = 16 // 128-bit IV for CBC

  val AES_IV_LENGTH_MAX = 16 // Maximum allowed IV length

  val GCM_TAG_LENGTH_IN_BITS = 128 // 128-bit authentication tag

  /**
   * Size of plain data chunk for encryption.
   * Optimal size: 8MB to match S3 multipart upload standard.
   * This ensures 1 part = 1 chunk alignment.
   */
  def PLAIN_CHUNK_MAX_SIZE = 8 * 1024 * 1024  // 8MB

  /**
   * Size of encrypted chunk for AES-GCM.
   * Calculated as: plain data + IV + authentication tag
   */
  def ENCRYPTED_CHUNK_MAX_SIZE_AES_GCM = PLAIN_CHUNK_MAX_SIZE + AES_IV_LENGTH_MAX + GCM_TAG_LENGTH_IN_BITS / 8

  /**
   * Size of encrypted chunk for AES-CBC.
   * We do not actually use it
   * Calculated as: plain data + IV
   */
  def ENCRYPTED_CHUNK_MAX_SIZE_AES_CBC = PLAIN_CHUNK_MAX_SIZE + AES_CBC_IV_SIZE

  /** Initial BAOS capacity when Content-Length is missing or zero. */
  val NON_CHUNK_RETRIEVE_BUFFER_SIZE = 20 * 1024

  /**
   * BAOS initial capacity for cloud GET.
   * Known Content-Length (> 0) → exact size; size 0 or unknown → [[NON_CHUNK_RETRIEVE_BUFFER_SIZE]] (BAOS grows if needed).
   */
  def byteArrayOutputStreamForCloudRetrieve(containerOrBucketName: String, contentLengthBytes: Long): ByteArrayOutputStream = {
    val initialCapacity =
      if (contentLengthBytes > 0L && contentLengthBytes <= Int.MaxValue) contentLengthBytes.toInt
      else NON_CHUNK_RETRIEVE_BUFFER_SIZE
    new ByteArrayOutputStream(initialCapacity)
  }

  val CHUNKS_BLOCK_FOR_HADOOP_INPUT_STREAM = 6

  val INPUT_STREAM_GROUP_SIZE = 3
  val INPUT_STREAM_GROUPS_PROCESSED_TOGETHER = 2

  val METADATAHEADER_OBJECTSIZE = "objectsize"

  val EVENT_ADD_READER = "ADDREADER"
  val EVENT_REMOVE_READER = "REMOVEREADER"
  val EVENT_SHARE = "SHARE"
  val EVENT_DELETE = "DELETE"
  val EVENT_ADD_USERDATA = "ADD_USERDATA"
}

