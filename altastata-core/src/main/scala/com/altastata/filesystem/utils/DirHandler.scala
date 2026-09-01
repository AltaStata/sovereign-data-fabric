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

package com.altastata.filesystem.utils

import java.io.{File, FileFilter}
import org.apache.commons.io.IOCase
import org.apache.commons.io.filefilter.PrefixFileFilter

object DirHandler {

  /**
   * Traverses and resolves multiple files/directories recursively into a flat stream of Files.
   *
   * @param files the list of starting files/directories
   * @return a flattened Stream of Files
   */
  def getFilesTree(files: List[File]): Stream[File] =
    files.map { f => getFileTree(f, false) }.reduce(_ ++: _)

  /**
   * Traverses a single file or directory to construct a Stream representation.
   *
   * @param f the starting File reference
   * @param useFlatBlobListing true to traverse recursively; false to list immediate children only
   * @return a Stream representing the matched File or children files
   */
  def getFileTree(f: File, useFlatBlobListing: Boolean): Stream[File] =
    if (f.isDirectory) {
      if (useFlatBlobListing) {
        f.listFiles().toStream.flatMap(son => getFileTree(son, true))
      }
      else {
        f.listFiles().toStream
      }
    }
    else if (f.exists) {
      f #:: Stream.empty
    }
    else { // only file prefix is provided
      val dir: File = f.getParentFile

      if (dir != null && dir.isDirectory) {

        val name = f.getName
        val filter: PrefixFileFilter = new PrefixFileFilter(name, IOCase.SENSITIVE)

        dir.listFiles(filter.asInstanceOf[FileFilter]).toStream
      }
      else {
        Stream.empty
      }
    }
}
