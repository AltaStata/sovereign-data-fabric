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

import java.nio.ByteBuffer
import scala.util.Try
import scala.util.Success

/**
 * Trait providing high-performance byte buffer splitting and merging utilities.
 * 
 * Supports Secret Sharing/split-channel schemes where a buffer is split into three shares
 * (Share 1, Share 2, and Share 1 XOR Share 2) to facilitate distributed multi-cloud storage.
 * Any two shares are sufficient to fully reconstruct the original data, ensuring high
 * availability and data confidentiality (collusion-resistant storage).
 */
trait ByteBufferUtils {

  /**
   * Splits an input ByteBuffer into three separate buffers (buffer1, buffer2, and buffer1 XOR buffer2).
   *
   * @param inputBuffer The original source ByteBuffer to split.
   * @return A Try containing a tuple of three ByteBuffers.
   */
  def splitByteBuffer(inputBuffer: ByteBuffer): Try[(ByteBuffer, ByteBuffer, ByteBuffer)] = Try {
    val size = if (inputBuffer.limit() % 2 == 0) inputBuffer.limit() / 2 else inputBuffer.limit() / 2 + 1

    val buffer1: ByteBuffer = ByteBuffer.allocate(size)
    val buffer2: ByteBuffer = ByteBuffer.allocate(size)
    val buffer3: ByteBuffer = ByteBuffer.allocate(size)

    while (inputBuffer.position() < inputBuffer.limit()) {
      val firstByte = inputBuffer.get
      val secondByte = if (inputBuffer.position() < inputBuffer.limit()) inputBuffer.get else 0.toByte
      val xorByte = (firstByte ^ secondByte).toByte

      buffer1.put(firstByte)
      buffer2.put(secondByte)
      buffer3.put(xorByte)
    }

    buffer1.flip
    buffer2.flip
    buffer3.flip

    inputBuffer.flip

    (buffer1, buffer2, buffer3)
  }

  /**
   * Merges any two of the three split buffers to reconstruct the original ByteBuffer.
   *
   * @param buffers A tuple of three options representing the split buffers. Exactly two must be defined.
   * @param size    The expected size of the reconstructed original buffer.
   * @return A Try containing the fully reconstructed original ByteBuffer.
   */
  def mergeByteBuffers(buffers: (Option[ByteBuffer], Option[ByteBuffer], Option[ByteBuffer]), size: Int): Try[ByteBuffer] = Try {
    val outputBuffer: ByteBuffer = ByteBuffer.allocate(size)
    
    while (outputBuffer.position() < outputBuffer.limit()) {
      
      buffers match {
        case (Some(buffer1), Some(buffer2), _) => {
          outputBuffer.put(buffer1.get)
          if (outputBuffer.position() < outputBuffer.limit()) outputBuffer.put(buffer2.get)
        }
        
        case (Some(buffer1), None, Some(xorBuffer)) => {
          val byte1 = buffer1.get

          outputBuffer.put(byte1)
          if (outputBuffer.position() < outputBuffer.limit()) outputBuffer.put((byte1 ^ xorBuffer.get).toByte)
        }

        case (None, Some(buffer2), Some(xorBuffer)) => {
          val byte2 = buffer2.get

          outputBuffer.put((byte2 ^ xorBuffer.get).toByte)
          if (outputBuffer.position() < outputBuffer.limit()) outputBuffer.put(byte2)
        }
        
        case _ => throw new AssertionError(s"mergeByteBuffers: two or more buffers out of three are not defined")
      }
    }

    outputBuffer.flip

    outputBuffer
  }
}
