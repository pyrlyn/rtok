@file:Suppress("MagicNumber")

// Port of the src/tasks/disk.rs tests.

package rtok.tasks

import rtok.store.TaskId
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.listDir
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun new(title: String) = NewTask(title)

private fun tmp(name: String): String {
    val dir = "${tempDir()}/rtok-kt-disk-$name-${pid()}"
    runCatching { removeDirAll(dir) }
    return dir
}

class DiskTest {
    @Test fun createGetAndListThePlan() {
        val dir = tmp("plan")
        val disk = DiskAdapter(dir)
        val task = NewTask("Ship the: allocator!", "Why and what done means.\n\nSecond paragraph.")
        disk.create(task, id("R1"))
        disk.create(new("Second"), id("R2"))
        disk.create(new("Sub"), id("R2.1"))
        assertTrue(isFile("$dir/R1 - ship-the-allocator.md"))
        val got = disk.get(id("r1"))!!
        assertEquals("Ship the: allocator!", got.title)
        assertEquals(task.description, got.description)
        assertEquals(Status.Open, got.status)
        assertEquals(id("R2"), disk.get(id("R2.1"))!!.parent)
        assertNull(disk.get(id("R9")))
        assertEquals(listOf("R1", "R2", "R2.1"), disk.list(Filter()).map { it.id.toString() })
        assertEquals(1, disk.list(Filter(parent = id("R2"))).size)
        assertFailsWith<Taken> { disk.create(new("Again"), id("R1")) }
        assertFailsWith<TaskException> { disk.create(new("  "), id("R3")) }
        removeDirAll(dir)
    }

    @Test fun doneTasksLeaveThePlanAndCanComeBack() {
        val dir = tmp("done")
        val disk = DiskAdapter(dir)
        disk.create(new("Parent"), id("R1"))
        disk.create(new("Child"), id("R1.1"))
        val err = assertFailsWith<TaskException> { setStatus(disk, id("R1"), Status.Done, false) }
        assertTrue("R1.1" in err.message!!)
        setStatus(disk, id("R1.1"), Status.Closed, false)
        setStatus(disk, id("R1"), Status.Done, false)
        assertTrue(isFile("$dir/done/R1 - parent.md"))
        assertTrue(!exists("$dir/R1 - parent.md"))
        assertTrue(disk.list(Filter()).isEmpty())
        assertEquals(2, disk.list(Filter(all = true)).size)
        assertEquals(id("R1.1"), disk.list(Filter(statuses = listOf(Status.Closed)))[0].id)
        setStatus(disk, id("R1"), Status.InProgress, false)
        assertTrue(isFile("$dir/R1 - parent.md"))
        assertEquals(id("R1.1"), disk.maxId("r"))
        assertNull(disk.maxId("Q"))
        removeDirAll(dir)
    }

    @Test fun aForcedParentClosesOverOpenSubtasks() {
        val dir = tmp("force")
        val disk = DiskAdapter(dir)
        disk.create(new("Parent"), id("R1"))
        disk.create(new("Child"), id("R1.1"))
        setStatus(disk, id("R1"), Status.Done, true)
        assertFailsWith<TaskException> { setStatus(disk, id("R7"), Status.Done, false) }
        removeDirAll(dir)
    }

    @Test fun foreignFilesAreIgnoredAndBrokenOnesAreSkippedButKeepTheirId() {
        val dir = tmp("foreign")
        val disk = DiskAdapter(dir)
        assertTrue(disk.list(Filter()).isEmpty(), "no dir yet")
        disk.create(new("Real"), id("R1"))
        writeString("$dir/README.md", "# tasks\n")
        writeString("$dir/R2 - notes.txt", "x")
        assertEquals(1, disk.list(Filter()).size)
        val broken = "$dir/R3 - broken.md"
        writeString(broken, "no front matter\n")
        disk.create(new("Next"), id("R4"))
        assertEquals(2, disk.list(Filter()).size, "the broken file is left out")
        assertEquals("Next", disk.get(id("R4"))!!.title)
        assertEquals(id("R4"), disk.maxId("r"))
        removeFile("$dir/R4 - next.md")
        assertEquals(id("R3"), disk.maxId("r"), "ids are not reused")
        val err = assertFailsWith<TaskException> { disk.get(id("R3")) }
        assertTrue("R3 - broken.md" in err.message!!, err.message)
        assertFailsWith<TaskException> { disk.create(new("Again"), id("R3")) }
        assertFailsWith<TaskException> { disk.writeStatus(id("R3"), Status.Done) }
        assertFailsWith<TaskException> { disk.save(Task.open(id("R3"), "x", "", 0)) }
        assertEquals("no front matter\n", readToString(broken))
        // README, notes, R1, the broken file and the claim lock: nothing new.
        assertEquals(5, listDir(dir).size)
        removeDirAll(dir)
    }

    @Test fun aFileWhoseFrontMatterIdDiffersFromItsNameIsSkippedUntouched() {
        val dir = tmp("identity")
        val disk = DiskAdapter(dir)
        disk.create(new("target"), id("R2"))
        val copy = "$dir/R1 - copy.md"
        writeString(copy, "---\nid: R2\ntitle: target\nstatus: open\ncreated_at: 1\nupdated_at: 2\n---\n\nCopied body\n")
        val target = "$dir/R2 - target.md"
        val before = readBytes(copy) to readBytes(target)
        assertEquals(1, disk.list(Filter()).size)
        assertEquals("", disk.get(id("R2"))!!.description)
        assertEquals(id("R2"), disk.maxId("r"))
        disk.writeStatus(id("R2"), Status.InProgress)
        disk.writeStatus(id("R2"), Status.Open)
        val errs =
            listOf(
                runCatching { disk.get(id("R1")) },
                runCatching { disk.writeStatus(id("R1"), Status.InProgress) },
                runCatching { disk.save(Task.open(id("R1"), "x", "", 0)) },
                runCatching { disk.create(new("x"), id("R1")) },
            ).map { it.exceptionOrNull()!!.message!! }
        for (msg in errs) assertTrue("R1 - copy.md" in msg && "R1" in msg && "R2" in msg, msg)
        assertContentEquals(before.first, readBytes(copy))
        assertEquals(3, listDir(dir).size)
        removeDirAll(dir)
    }

    @Test fun aMismatchInDoneIsSkippedAndALowerCaseNameStillMatches() {
        val dir = tmp("identity-done")
        createDirAll("$dir/done")
        writeString("$dir/done/R3 - a.md", "---\nid: R4\ntitle: a\nstatus: done\ncreated_at: 1\nupdated_at: 2\n---\n")
        val disk = DiskAdapter(dir)
        assertTrue(disk.list(Filter(all = true)).isEmpty())
        val err = assertFailsWith<TaskException> { disk.get(id("R3")) }
        assertTrue("R3 - a.md" in err.message!!, err.message)
        removeFile("$dir/done/R3 - a.md")
        writeString("$dir/r5 - b.md", "---\nid: R5\ntitle: b\nstatus: open\ncreated_at: 1\nupdated_at: 2\n---\n")
        assertTrue(disk.get(id("R5")) != null)
        removeDirAll(dir)
    }

    @Test fun theFileRoundTripsThroughRenderAndParse() {
        val task =
            Task.open(id("R4"), "", "", 0).copy(
                title = "Title: with \"quotes\" and # hash",
                description = "Body\n---\nafter a rule",
                status = Status.InProgress,
                createdAt = 10,
                updatedAt = 20,
                conflictsWith = listOf(id("R2"), id("R3")),
                parallel = true,
            )
        val text = renderDisk(task)
        assertTrue(text.startsWith("---\nid: R4\n"), text)
        assertTrue("conflicts_with:" in text && "parallel: true" in text, text)
        assertEquals(task, parseDisk(text))
        assertEquals(task, parseDisk(text.replace("\n", "\r\n")))
        val bare = task.copy(description = "")
        assertEquals(bare, parseDisk(renderDisk(bare)))
    }

    @Test fun aFileWithoutClaimKeysStillListsAndCompletes() {
        val dir = tmp("old")
        createDirAll(dir)
        writeString("$dir/R4 - old.md", "---\nid: R4\ntitle: Old\nstatus: open\ncreated_at: 1\nupdated_at: 2\n---\n\nBody\n")
        val disk = DiskAdapter(dir)
        val got = disk.get(id("R4"))!!
        assertEquals(DEFAULT_PRIORITY, got.priority)
        assertNull(got.assignee)
        assertTrue(got.blockedBy.isEmpty())
        assertEquals("Body", got.description)
        setStatus(disk, id("R4"), Status.Done, false)
        assertTrue(isFile("$dir/done/R4 - old.md"))
        removeDirAll(dir)
    }

    @Test fun slugsAreShortAndSafe() {
        assertEquals("fix-etc-passwd", slug("Fix ../../etc/passwd"))
        assertEquals("task", slug("Привет"))
        assertTrue(slug("word ".repeat(40)).length <= SLUG_MAX)
    }
}
