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

import java.io.{File, FileNotFoundException}
import java.net.URI
import java.nio.charset.StandardCharsets

import com.altastata.utils.Account
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.junit.{Assume, Test}
import org.junit.Assert._

/**
 * Live Hadoop {@code FileSystem.rename} checks against a real account.
 *
 * Env:
 *   RUN_HADOOP_FS_IT=1
 *   ALTASTATA_IT_ACCOUNT=google.rsa.bob123
 *   ALTASTATA_IT_PASSWORD=123
 *
 *   RUN_HADOOP_FS_IT=1 ./gradlew :altastata-hadoop:test \
 *     --tests org.apache.hadoop.fs.altastata.AltaStataHadoopRenameTest
 */
class AltaStataHadoopRenameTest {

  private def env(k: String, default: String): String =
    Option(System.getenv(k)).filter(_.nonEmpty).getOrElse(default)

  private def openFs(accountDir: String, password: String): FileSystem = {
    val conf = new Configuration()
    conf.set("fs.altastata.impl", "org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem")
    conf.set("altastata.account.home", accountDir)
    conf.set("altastata.account.password", password)
    val fs = new AltaStataHadoopFileSystem()
    fs.initialize(new URI("altastata:///"), conf)
    fs
  }

  private def writeUtf8(fs: FileSystem, path: Path, text: String): Unit = {
    val out = fs.create(path, true)
    try out.write(text.getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  private def readUtf8(fs: FileSystem, path: Path): String = {
    val in = fs.open(path)
    try {
      val buf = new Array[Byte](fs.getFileStatus(path).getLen.toInt)
      in.readFully(buf)
      new String(buf, StandardCharsets.UTF_8)
    } finally in.close()
  }

  @Test
  def renameSameDirFile(): Unit = {
    Assume.assumeTrue("set RUN_HADOOP_FS_IT=1", env("RUN_HADOOP_FS_IT", "") == "1")

    val accountName = env("ALTASTATA_IT_ACCOUNT", "google.rsa.bob123")
    val password = env("ALTASTATA_IT_PASSWORD", "123")
    val accountDir = Account.ALTASTATA_ACCOUNTS_HOME + File.separator + accountName
    Assume.assumeTrue("account dir not found: " + accountDir, new File(accountDir).isDirectory)

    val prefix = "HADOOPIT/rename_" + System.currentTimeMillis()
    val fs = openFs(accountDir, password)
    try {
      assertTrue("Hadoop root is a directory", fs.getFileStatus(new Path("altastata:///")).isDirectory)

      val sameSrc = new Path("altastata:///" + prefix + "/old.bin")
      val sameDst = new Path("altastata:///" + prefix + "/new.bin")
      writeUtf8(fs, sameSrc, "same-dir")
      assertTrue("same-dir rename", fs.rename(sameSrc, sameDst))
      assertEquals("same-dir", readUtf8(fs, sameDst))
      try {
        fs.getFileStatus(sameSrc)
        fail("source should be gone after same-dir rename")
      } catch {
        case _: FileNotFoundException =>
      }

      // startsWith guard: dst name is a string prefix-extension of src (`file` → `file2`)
      val prefixSrc = new Path("altastata:///" + prefix + "/file")
      val prefixDst = new Path("altastata:///" + prefix + "/file2")
      writeUtf8(fs, prefixSrc, "prefix-ok")
      assertTrue("file → file2 must not be rejected as nested", fs.rename(prefixSrc, prefixDst))
      assertEquals("prefix-ok", readUtf8(fs, prefixDst))
      try {
        fs.getFileStatus(prefixSrc)
        fail("source should be gone after file → file2, not a fake directory")
      } catch {
        case _: FileNotFoundException =>
      }

      // Prefix listing: sibling `sib2` must not move when `sib` is renamed or deleted.
      val sib = new Path("altastata:///" + prefix + "/sib")
      val sib2 = new Path("altastata:///" + prefix + "/sib2")
      val sib3 = new Path("altastata:///" + prefix + "/sib3")
      writeUtf8(fs, sib, "keep-sib")
      writeUtf8(fs, sib2, "keep-sib2")
      assertTrue("rename sib must not take sib2", fs.rename(sib, sib3))
      assertEquals("keep-sib", readUtf8(fs, sib3))
      assertEquals("keep-sib2", readUtf8(fs, sib2))
      try {
        fs.getFileStatus(sib)
        fail("sib should be gone")
      } catch {
        case _: FileNotFoundException =>
      }
      assertTrue(fs.delete(sib3, false))
      assertEquals("keep-sib2", readUtf8(fs, sib2))

      val dirA = new Path("altastata:///" + prefix + "/d/x")
      val dirB = new Path("altastata:///" + prefix + "/d2/y")
      writeUtf8(fs, dirA, "in-d")
      writeUtf8(fs, dirB, "in-d2")
      assertTrue("rename dir d must not take d2",
        fs.rename(new Path("altastata:///" + prefix + "/d"), new Path("altastata:///" + prefix + "/moved")))
      assertEquals("in-d", readUtf8(fs, new Path("altastata:///" + prefix + "/moved/x")))
      assertEquals("in-d2", readUtf8(fs, dirB))
    } finally {
      try fs.delete(new Path("altastata:///" + prefix), true)
      catch { case t: Throwable => System.err.println("cleanup failed: " + t.getMessage) }
      fs.close()
    }
  }
}
