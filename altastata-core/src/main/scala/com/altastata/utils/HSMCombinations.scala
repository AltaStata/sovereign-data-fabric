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

import scala.collection.mutable._
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayInputStream
import java.util.Date
import com.altastata.filesystem.securecloud.StorageObjectMetadata
import org.slf4j.LoggerFactory

/**
 * https://www.hackmath.net/en/calculator/combinations-and-permutations?n=100&k=4&order=0&repeat=0
 * https://rosettacode.org/wiki/Combinations#Scala
 */
/**
 * Trait providing utility methods to calculate mathematical combinations (K-combinations out of N).
 * Useful for M-of-N threshold sharing / HSM key combination recovery scenarios.
 */
trait HSMCombinations {

  // V1
  implicit def toComb(m: Int) = new AnyRef {
    /**
     * Calculates combinations of size m from n elements.
     */
    def comb(n: Int) = recurse(m, List.range(0, n))

    private def recurse(m: Int, l: List[Int]): List[List[Int]] = (m, l) match {
      case (0, _) => List(Nil)
      case (_, Nil) => Nil
      case _ => (recurse(m - 1, l.tail) map (l.head :: _)) ::: recurse(m, l.tail)
    }
  }

  /**
   * Lazily calculates mathematical combinations of size n from list l.
   *
   * @param n The size of each combination.
   * @param l The original list of elements.
   * @return An iterator of list combinations.
   */
  def combsV2[A](n: Int, l: List[A]): Iterator[List[A]] = n match {
    case _ if n < 0 || l.lengthCompare(n) < 0 => Iterator.empty
    case 0 => Iterator(List.empty)
    case n => l.tails.flatMap({
      case Nil => Nil
      case x :: xs => combsV2(n - 1, xs).map(x :: _)
    })
  }

  /**
   * Stream-based version to calculate combinations.
   */
  def combsV3[A](n: Int, xs: List[A]): Stream[List[A]] =
    combsBySize(xs)(n)

  /**
   * Generates streams of combinations grouped by size.
   */
  def combsBySize[A](xs: List[A]): Stream[Stream[List[A]]] = {
    val z: Stream[Stream[List[A]]] = Stream(Stream(List())) ++ Stream.continually(Stream.empty)
    xs.toStream.foldRight(z)((a, b) => zipWith[Stream[List[A]]](_ ++ _, f(a, b), b))
  }

  /**
   * Helper utility to zip streams with a combining function.
   */
  def zipWith[A](f: (A, A) => A, as: Stream[A], bs: Stream[A]): Stream[A] = (as, bs) match {
    case (Stream.Empty, _) => Stream.Empty
    case (_, Stream.Empty) => Stream.Empty
    case (a #:: as, b #:: bs) => f(a, b) #:: zipWith(f, as, bs)
  }

  /**
   * Stream mapping helper function.
   */
  def f[A](x: A, xsss: Stream[Stream[List[A]]]): Stream[Stream[List[A]]] =
    Stream.empty #:: xsss.map(_.map(x :: _))

}

object HSMCombinationsTest extends HSMCombinations {
  /**
   * Main entry point to run local performance and mathematical combinations verification tests.
   *
   * @param args command line arguments
   */
  def main(args: Array[String]) {

    val n = 6
    val numberOfKeys = 10

    val start0 = System.currentTimeMillis()
    val v0 = (0 to numberOfKeys).combinations(n).toList
    println(v0.take(30))
    val end0 = System.currentTimeMillis()
    println("V0: " + (end0 - start0) + " size: " + v0.size)

    val start2 = System.currentTimeMillis()
    val v2 = combsV2(n, (0 to numberOfKeys).toList).toList
    println(v2.take(30))
    val end2 = System.currentTimeMillis()
    println("V2: " + (end2 - start2) + " size: " + v2.size)

    /*
    val start1 = System.currentTimeMillis()
    val v1 = n comb numberOfKeys
    println(v1.take(30))
    val end1 = System.currentTimeMillis()
    println("V1: " + (end1 - start1) + " size: " + v1.size)

    val start3 = System.currentTimeMillis()
    val v3 = combsV3(n, (0 to numberOfKeys).toList).toList
    println(v3.take(30))
    val end3 = System.currentTimeMillis()
    println("V3: " + (end3 - start3) + " size: " + v3.size)
     */

  }
}
