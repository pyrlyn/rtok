@file:Suppress("MagicNumber")

// Port of the src/tasks/plan_dir.rs tests.

package rtok.tasks

import rtok.store.TaskId
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.listDir
import rtok.sys.parent
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun new(
    title: String,
    description: String,
) = NewTask(title, description)

private fun tmp(name: String): String {
    val root = "${tempDir()}/rtok-kt-plandir-$name-${pid()}"
    runCatching { removeDirAll(root) }
    return "$root/plan"
}

private fun cleanup(dir: String) = removeDirAll(parent(dir)!!)

private const val HAND_MADE = "---\nstatus: todo\npriority: P1\ncomplexity: 4\nreadiness: 30%\nagent:\n---\n\n# T7. Hand made\n\nWhy.\n\nCheck: run it.\n"

private fun planErrors(dir: String) = listDir(dir).map { "$dir/$it" }.filter(::isDir).flatMap(::folderErrors)

class PlanDirTest {
    @Test fun createClaimAndCloseLeaveAPlanThatPassesThePlanRules() {
        val dir = tmp("flow")
        val disk = PlanDirAdapter(dir)
        val made = disk.create(new("Ship it", "Why it exists.\n\nCheck: run it."), id("T1"))
        assertEquals("Ship it" to Status.Open, made.title to made.status)
        assertEquals("Why it exists.\n\nCheck: run it.", made.description)
        disk.create(new("Sub", ""), id("T1.1"))
        assertEquals(emptyList(), planErrors(dir), "created")
        assertEquals(id("T1"), disk.get(id("T1.1"))!!.parent)
        assertFailsWith<Taken> { disk.create(new("Again", ""), id("T1")) }
        assertFailsWith<TaskException> { disk.create(new("two\nlines", ""), id("T2")) }

        val task = disk.get(id("T1"))!!.copy(status = Status.InProgress, assignee = "Claude Code / opus")
        assertEquals("Claude Code / opus", disk.save(task).assignee)
        assertEquals(emptyList(), planErrors(dir), "claimed")
        val text = readToString("$dir/T1/task.md")
        assertTrue(text.startsWith("---\nstatus: in progress\npriority: P2\ncomplexity: 3\nreadiness: 0%\nagent: Claude Code / opus\n---\n"), text)
        assertTrue("# T1. Ship it\n" in text, text)

        setStatus(disk, id("T1.1"), Status.Closed, false)
        disk.writeReport(id("T1.1"), "not needed")
        setStatus(disk, id("T1"), Status.Done, false)
        disk.writeReport(id("T1"), "Shipped in a PR.")
        assertEquals("Dropped: not needed\n", readToString("$dir/T1.1/done.md"))
        assertEquals("Shipped in a PR.\n", readToString("$dir/T1/done.md"))
        assertEquals(text, readToString("$dir/T1/task.md"), "closing leaves task.md as it was")
        assertEquals(emptyList(), planErrors(dir), "closed")
        assertTrue(disk.list(Filter()).isEmpty())
        assertEquals(listOf("T1" to Status.Done, "T1.1" to Status.Closed), disk.list(Filter(all = true)).map { it.id.toString() to it.status })
        assertEquals(id("T1.1"), disk.maxId("t"))
        cleanup(dir)
    }

    @Test fun aCreatedTaskHasTheShapeThePlanCheckWants() {
        val dir = tmp("shape")
        PlanDirAdapter(dir).create(new("Bare", ""), id("T3"))
        val (head, body) = splitFront(readToString("$dir/T3/task.md"))
        assertEquals(listOf("status", "priority", "complexity", "readiness", "agent"), head.split("\n").map { it.substringBefore(':') })
        assertEquals(1, body.split("\n").count { it.startsWith("Check:") })
        cleanup(dir)
    }

    @Test fun aSaveChangesThreeLinesAndNothingElse() {
        val dir = tmp("bytes")
        createDirAll("$dir/T7")
        writeString("$dir/T7/task.md", HAND_MADE)
        val disk = PlanDirAdapter(dir)
        val task = disk.get(id("T7"))!!
        assertEquals(1 to "Hand made", task.priority to task.title)
        disk.save(task.copy(status = Status.InProgress, assignee = "A / b", priority = 0))
        val want = HAND_MADE.replace("status: todo", "status: in progress").replace("priority: P1", "priority: P0").replace("agent:\n", "agent: A / b\n")
        assertEquals(want, readToString("$dir/T7/task.md"))
        cleanup(dir)
    }

    @Test fun whatTheLayoutCannotStoreIsRefused() {
        val dir = tmp("refuse")
        val disk = PlanDirAdapter(dir)
        disk.create(new("A", ""), id("T1"))
        val task = disk.get(id("T1"))!!
        for (bad in listOf(
            task.copy(blockedBy = listOf(id("T2"))),
            task.copy(parallel = true),
            task.copy(priority = 4),
            task.copy(status = Status.InProgress),
            task.copy(assignee = "x"),
            task.copy(status = Status.InProgress, assignee = "x\ny: 1"),
        )) {
            assertFailsWith<TaskException>(bad.toString()) { disk.save(bad) }
        }
        assertFalse(disk.storesMarkers())
        assertEquals(task, disk.get(id("T1")))
        cleanup(dir)
    }

    @Test fun aClosedTaskIsNotReopenedByACommand() {
        val dir = tmp("reopen")
        val disk = PlanDirAdapter(dir)
        disk.create(new("A", ""), id("T1"))
        setStatus(disk, id("T1"), Status.Done, false)
        val err = assertFailsWith<TaskException> { disk.writeStatus(id("T1"), Status.Open) }
        assertTrue("done.md" in err.message!!, err.message)
        assertFailsWith<TaskException> { disk.writeReport(id("T9"), "x") }
        disk.create(new("B", ""), id("T2"))
        assertFailsWith<TaskException>("not finished") { disk.writeReport(id("T2"), "x") }
        cleanup(dir)
    }

    @Test fun foldersThatAreNotTasksAreLeftAlone() {
        val dir = tmp("foreign")
        val disk = PlanDirAdapter(dir)
        assertTrue(disk.list(Filter()).isEmpty(), "no dir yet")
        disk.create(new("Real", ""), id("T1"))
        writeString("$dir/README.md", "# plan\n")
        for (name in listOf("P8b", "draft", "t5", "T0.1")) {
            createDirAll("$dir/$name")
            writeString("$dir/$name/task.md", "no front matter\n")
        }
        assertEquals(1, disk.list(Filter()).size)
        cleanup(dir)
    }

    @Test fun aBrokenTaskIsSkippedByReadsAndFailsByItsId() {
        val dir = tmp("broken")
        val disk = PlanDirAdapter(dir)
        disk.create(new("Real", ""), id("T1"))
        createDirAll("$dir/T4")
        // Valid front matter, but no `# T4. Title` heading.
        writeString("$dir/T4/task.md", HAND_MADE)
        createDirAll("$dir/T9")
        writeString("$dir/T9/task.md", "no front matter\n")
        disk.create(new("Also real", ""), id("T2"))
        assertEquals(setOf("T1", "T2"), disk.list(Filter()).map { it.id.toString() }.toSet())
        assertEquals("Real", disk.get(id("T1"))!!.title)
        val msg = assertFailsWith<TaskException> { disk.get(id("T4")) }.message!!
        assertTrue("T4/task.md" in msg && "heading" in msg, msg)
        assertEquals(id("T9"), disk.maxId("T"))
        assertFailsWith<Taken> { disk.create(new("Again", ""), id("T9")) }
        val before = readBytes("$dir/T9/task.md")
        assertFailsWith<TaskException> { disk.writeStatus(id("T9"), Status.InProgress) }
        assertFailsWith<TaskException> { disk.writeStatus(id("T9"), Status.Done) }
        assertFailsWith<TaskException> { disk.writeReport(id("T9"), "x") }
        assertFailsWith<TaskException> { disk.save(Task.open(id("T9"), "x", "", 0)) }
        assertContentEquals(before, readBytes("$dir/T9/task.md"))
        assertFalse(exists("$dir/T9/done.md"))
        cleanup(dir)
    }

    @Test fun aFinishedTaskWithOldFrontMatterStillLists() {
        val dir = tmp("old-done")
        createDirAll("$dir/T2")
        writeString("$dir/T2/task.md", "---\nstatus: done\nodd: 1\n---\n\n# T2. Old\n")
        writeString("$dir/T2/done.md", "Done in a PR.\n")
        val disk = PlanDirAdapter(dir)
        val got = disk.get(id("T2"))!!
        assertEquals("Old" to Status.Done, got.title to got.status)
        createDirAll("$dir/T3")
        writeString("$dir/T3/task.md", "no front matter at all\n")
        writeString("$dir/T3/done.md", "Dropped: old.\n")
        val bare = disk.get(id("T3"))!!
        assertEquals("T3" to Status.Closed, bare.title to bare.status)
        assertTrue(PlanDirAdapter.holdsFolders(dir))
        assertFalse(PlanDirAdapter.holdsFolders("$dir/nope"))
        cleanup(dir)
    }
}
