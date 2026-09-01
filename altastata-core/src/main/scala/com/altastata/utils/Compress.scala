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
import org.slf4j.LoggerFactory
import scala.util.{ Try, Success, Failure }
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.{ Inflater, Deflater }
import java.nio.file.{ Files, Paths }
import java.io.{ File, FileOutputStream }
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Trait providing utility functions for GZIP compression and decompression of byte arrays.
 */
trait Compress {
  
  /**
   * Compresses a byte array using GZIP compression.
   *
   * @param txt The raw uncompressed byte array.
   * @return A Try containing the GZIP-compressed byte array.
   */
  def deflate(txt: Array[Byte]): Try[Array[Byte]] = Try {
    val arrOutputStream = new ByteArrayOutputStream()
    val zipOutputStream = new GZIPOutputStream(arrOutputStream)
    zipOutputStream.write(txt)
    zipOutputStream.close()
    arrOutputStream.toByteArray
  }

  /**
   * Decompresses a GZIP-compressed byte array.
   *
   * Output is capped at [[Constants.PLAIN_CHUNK_MAX_SIZE]]: compression is only ever applied
   * per chunk, so any blob inflating beyond that is corrupted or a zip bomb and must fail
   * instead of exhausting the heap.
   *
   * @param deflated The GZIP-compressed byte array.
   * @return A Try containing the decompressed raw byte array.
   */
  def inflate(deflated: Array[Byte]): Try[Array[Byte]] = Try {
    val maxSize = Constants.PLAIN_CHUNK_MAX_SIZE
    val zipInputStream = new GZIPInputStream(new ByteArrayInputStream(deflated))
    val out = new ByteArrayOutputStream()
    val buffer = new Array[Byte](64 * 1024)
    var total = 0
    var read = zipInputStream.read(buffer)
    while (read != -1) {
      total += read
      if (total > maxSize) {
        throw new java.io.IOException(
          s"Refusing to inflate more than ${maxSize} bytes (possible zip bomb or corrupted chunk)")
      }
      out.write(buffer, 0, read)
      read = zipInputStream.read(buffer)
    }
    out.toByteArray
  }
}

//object CompressTest extends Compress {
//  def main(args: Array[String]): Unit = {
//    val str = "Alphabet Inc. (commonly known as Alphabet) is an American multinational conglomerate created in 2015 as the parent company of Google and several other companies previously owned by or tied to Google.[1][2][3][4][5] The company is based in California and headed by Google's co-founders, Larry Page and Sergey Brin, with Page serving as CEO and Brin as President.[6] The reorganization of Google into Alphabet was completed on October 2, 2015.[7] Alphabet's portfolio encompasses several industries, including technology, life sciences, investment capital, and research. Some of its subsidiaries include Google, Calico, Google Ventures, Google Capital, Google X, and Nest Labs. Following the restructuring Page became CEO of Alphabet while Sundar Pichai took his position as CEO of Google.[1][2] Shares of Google's stock have been converted into Alphabet stock, which trade under Google's former ticker symbols of "
//    val buf = str.getBytes
//    val deflated = deflate(buf).get
//    info("deflated.size: " + str.length + " -> " + deflated.length)
//    val inflated = inflate(deflated).get
//
//    info(new String(inflated))
//  }
//}
