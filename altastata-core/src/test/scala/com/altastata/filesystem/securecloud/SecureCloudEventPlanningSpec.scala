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

import com.altastata.utils.Constants._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

/**
 * Tests for the change-batch planning used by SecureCloudEventProcessor.processAllChanges:
 * ADD_USERDATA runs first (phase 1), and every other change is grouped by target object and
 * ordered by timestamp inside its group, so operations on the same object stay in order while
 * different objects can run in parallel.
 */
@RunWith(classOf[JUnitRunner])
class SecureCloudEventPlanningSpec extends AnyFunSuite {

  private object Planner extends SecureCloudOperations {
    def plan(changes: Seq[String]): (Seq[String], Seq[Seq[String]]) = planChanges(changes)
    def targetOf(p: String): String = changeTargetPath(p)
    def typeOf(p: String): String = changeEventType(p)
    def timeOf(p: String): Long = changeEventTime(p)
    def isValid(p: String): Boolean = isValidChangeObjectPath(p)
  }

  /** Build a change object key in the real format: QUEUE/<time>/<EVENT>/<params>/<file...>. */
  private def mkChange(time: Long, event: String, from: String, file: String): String =
    s"changes/${"%017d".format(time)}/$event/from=$from/$file"

  test("empty batch yields empty plan") {
    val (userdata, groups) = Planner.plan(Seq.empty)
    assert(userdata.isEmpty)
    assert(groups.isEmpty)
  }

  test("malformed change object paths are rejected before batch planning") {
    assert(!Planner.isValid("changes/not-a-time/SHARE/from=alice/file"))
    assert(!Planner.isValid("changes/00000000000000001/SHARE/from=alice"))
    assert(!Planner.isValid("changes/00000000000000001/SHARE/reader=bob/file"))
    assert(!Planner.isValid("changes/00000000000000001//from=alice/file"))
    assert(Planner.isValid(mkChange(1, EVENT_SHARE, "alice", "file")))
  }

  test("ADD_USERDATA is planned as phase 1 (time-ordered) and excluded from object groups") {
    val changes = Seq(
      mkChange(30, EVENT_ADD_READER, "alice", "f1"),
      mkChange(10, EVENT_ADD_USERDATA, "admin", "user=bob"),
      mkChange(20, EVENT_ADD_USERDATA, "admin", "user=carol")
    )

    val (userdata, groups) = Planner.plan(changes)

    assert(userdata.map(Planner.typeOf).forall(_ == EVENT_ADD_USERDATA))
    assert(userdata.map(Planner.timeOf) == Seq(10L, 20L))
    assert(groups.flatten.exists(Planner.typeOf(_) == EVENT_ADD_READER))
    assert(!groups.flatten.exists(Planner.typeOf(_) == EVENT_ADD_USERDATA))
  }

  test("same object: events are ordered by time within one group regardless of input order") {
    val changes = Seq(
      mkChange(50, EVENT_REMOVE_READER, "alice", "f1"),
      mkChange(40, EVENT_ADD_READER, "alice", "f1")
    )

    val (_, groups) = Planner.plan(changes)

    assert(groups.size == 1)
    // ADD_READER (t=40) must be applied before REMOVE_READER (t=50).
    assert(groups.head.map(Planner.timeOf) == Seq(40L, 50L))
    assert(groups.head.map(Planner.typeOf) == Seq(EVENT_ADD_READER, EVENT_REMOVE_READER))
  }

  test("different objects go to different (parallelizable) groups") {
    val changes = Seq(
      mkChange(10, EVENT_SHARE, "alice", "f1"),
      mkChange(20, EVENT_DELETE, "alice", "f2"),
      mkChange(30, EVENT_ADD_READER, "alice", "f3")
    )

    val (_, groups) = Planner.plan(changes)

    assert(groups.size == 3)
    assert(groups.forall(_.size == 1))
  }

  test("interleaved types for two objects are partitioned by object, each time-ordered") {
    val changes = Seq(
      mkChange(10, EVENT_ADD_READER, "a", "fA"),
      mkChange(15, EVENT_ADD_READER, "a", "fB"),
      mkChange(20, EVENT_REMOVE_READER, "a", "fA"),
      mkChange(25, EVENT_REMOVE_READER, "a", "fB")
    )

    val (_, groups) = Planner.plan(changes)

    assert(groups.size == 2)
    val byFile = groups.map(g => Planner.targetOf(g.head) -> g.map(Planner.timeOf)).toMap
    assert(byFile("fA") == Seq(10L, 20L))
    assert(byFile("fB") == Seq(15L, 25L))
  }

  test("multi-segment file paths are used whole as the grouping key") {
    val changes = Seq(
      mkChange(10, EVENT_SHARE, "a", "dir/sub/file.binAS_MARK_1_0"),
      mkChange(20, EVENT_DELETE, "a", "dir/sub/file.binAS_MARK_1_0")
    )

    val (_, groups) = Planner.plan(changes)

    assert(groups.size == 1)
    assert(Planner.targetOf(groups.head.head) == "dir/sub/file.binAS_MARK_1_0")
    assert(groups.head.map(Planner.timeOf) == Seq(10L, 20L))
  }

  test("group order is deterministic (sorted by target path)") {
    val changes = Seq(
      mkChange(10, EVENT_SHARE, "a", "zzz"),
      mkChange(10, EVENT_SHARE, "a", "aaa"),
      mkChange(10, EVENT_SHARE, "a", "mmm")
    )

    val (_, groups) = Planner.plan(changes)

    assert(groups.map(g => Planner.targetOf(g.head)) == Seq("aaa", "mmm", "zzz"))
  }
}
