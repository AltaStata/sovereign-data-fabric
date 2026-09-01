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

package org.apache.hadoop.fs.altastata

import java.io.OutputStream
import org.apache.hadoop.fs.Syncable
import org.apache.hadoop.fs.StreamCapabilities
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream

/**
 * Wrapper for AltaStataChunkedOutputStream that adds Hadoop's Syncable and StreamCapabilities
 * interfaces, satisfying durability checks in HBase WAL and similar systems.
 */
class AltaStataHadoopOutputStream(inner: AltaStataChunkedOutputStream) extends OutputStream with Syncable with StreamCapabilities {

  override def write(b: Int): Unit = {
    inner.write(b)
  }

  override def write(b: Array[Byte]): Unit = {
    inner.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    inner.write(b, off, len)
  }

  override def flush(): Unit = {
    inner.flush()
  }

  override def close(): Unit = {
    inner.close()
  }

  // --- Syncable implementation ---

  override def hflush(): Unit = {
    inner.sync()
  }

  override def hsync(): Unit = {
    inner.sync()
  }

  // --- StreamCapabilities implementation ---

  override def hasCapability(capability: String): Boolean = {
    capability match {
      case StreamCapabilities.HFLUSH => true
      case StreamCapabilities.HSYNC => true
      case _ => false
    }
  }
}
