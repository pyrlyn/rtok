@file:Suppress("MagicNumber")

package rtok.tasks

import rtok.store.TaskId
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.readDir
import rtok.sys.readToString
import rtok.sys.removeFile
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun new(
    title: String,
    description: String = "",
) = NewTask(title, description)

private class Clock(
    var now: Long = 1_783_594_440L,
) {
    operator fun invoke() = now
}

private fun board(
    tag: String,
    clock: Clock = Clock(),
    warnings: MutableList<String> = mutableListOf(),
): Triple<String, BoardAdapter, Clock> {
    val dir = "${tmpDir(tag)}/tasks"
    return Triple(dir, BoardAdapter(dir, clock::invoke) { warnings += it }, clock)
}

@Suppress("TooManyFunctions")
class BoardTest {
    @Test fun aNewTaskIsAFileInTodoAndTheBoardIgnoresItsLock() {
        val (dir, board, _) = board("board-new")
        board.create(new("Ship the: allocator!", "Why and what done means."), id("R1"))
        assertTrue(isFile("$dir/todo/R1-ship-the-allocator.md"))
        assertEquals(".lock\n.sync/\n", readToString("$dir/.gitignore"))
        val text = readToString("$dir/todo/R1-ship-the-allocator.md")
        assertTrue(text.startsWith("---\nid: R1\ntitle: \"Ship the: allocator!\"\ncreated: 2026-"), text)
        assertTrue("status" !in text.substringBefore("\n---\n"), text)
        assertEquals("Why and what done means.", board.get(id("R1"))!!.description)
    }

    @Test fun frontMatterIsInTheDesignOrderWithOnlySetKeys() {
        val (dir, board, _) = board("board-order")
        board.create(new("Child"), id("R1"))
        board.create(new("Blocker"), id("R2"))
        board.save(board.get(id("R1"))!!.copy(priority = 0, assignee = "agent-1", blockedBy = listOf(id("R2")), conflictsWith = listOf(id("R3")), parallel = true))
        val head = readToString("$dir/todo/R1-child.md").substringBefore("\n---\n")
        val keys = head.lines().filter { it.isNotEmpty() && !it.startsWith(" ") }.map { it.substringBefore(':') }
        assertEquals(listOf("---", "id", "title", "created", "updated", "priority", "assignee", "blocked_by", "conflicts_with", "parallel"), keys)
        board.create(new("Sub"), id("R1.1"))
        val sub = readToString("$dir/todo/R1.1-sub.md")
        assertTrue("parent: R1\n" in sub && sub.indexOf("parent:") > sub.indexOf("updated:"), sub)
        assertTrue("priority" !in sub, "the default priority is not written")
    }

    @Test fun everyMoveRelocatesTheFileAndKeepsOnlyOne() {
        val (dir, board, clock) = board("board-moves")
        board.create(new("Move me"), id("R1"))
        for ((status, folder) in listOf(Status.InProgress to "in-progress", Status.Done to "done", Status.Closed to "closed", Status.Todo to "todo")) {
            clock.now += 60
            assertEquals(status, board.writeStatus(id("R1"), status, null).status)
            assertTrue(isFile("$dir/$folder/R1-move-me.md"), folder)
            val all = listOf("todo", "in-progress", "done", "closed").flatMap { c -> runCatching { readDir("$dir/$c") }.getOrDefault(emptyList()).map { "$c/$it" } }
            assertEquals(listOf("$folder/R1-move-me.md"), all)
            assertEquals(status, board.get(id("R1"))!!.status)
        }
    }

    @Test fun slugsKeepUnicodeLettersAndCapAt48() {
        val (dir, board, _) = board("board-slug")
        board.create(new("Починить кэш прокси!"), id("R1"))
        assertTrue(isFile("$dir/todo/R1-починить-кэш-прокси.md"))
        board.create(new("  !!! "), id("R2"))
        assertTrue(isFile("$dir/todo/R2-task.md"))
        board.create(new("x".repeat(80)), id("R3"))
        assertEquals("R3-${"x".repeat(48)}.md", readDir("$dir/todo").first { it.startsWith("R3") })
        assertEquals("починить-кэш-прокси", unicodeSlug("Починить кэш прокси!"))
    }

    @Test fun aHandMoveIsAStatusChangeAndAStatusKeyIsIgnored() {
        val (dir, board, _) = board("board-hand")
        board.create(new("Dragged"), id("R1"))
        createDirAll("$dir/done")
        val text = readToString("$dir/todo/R1-dragged.md").replace("title:", "status: todo\ntitle:")
        writeString("$dir/done/R1-dragged.md", text)
        removeFile("$dir/todo/R1-dragged.md")
        assertEquals(Status.Done, board.get(id("R1"))!!.status)
        assertEquals(listOf(id("R1")), board.list(Filter(all = true)).map { it.id })
        assertTrue(board.list(Filter()).isEmpty(), "done is not in the plan")
    }

    @Test fun aWriteThatChangesNothingKeepsTheFileAndUpdated() {
        val (dir, board, clock) = board("board-noop")
        val made = board.create(new("Same"), id("R1"))
        val before = readToString("$dir/todo/R1-same.md")
        clock.now += 3600
        assertEquals(made, board.save(board.get(id("R1"))!!))
        assertEquals(made.updatedAt, board.writeStatus(id("R1"), Status.Todo, null).updatedAt)
        assertEquals(before, readToString("$dir/todo/R1-same.md"))
        clock.now += 3600
        val changed = board.save(board.get(id("R1"))!!.copy(priority = 1))
        assertEquals(clock.now, changed.updatedAt)
    }

    @Test fun anInterruptedMoveLeavesTwoCopiesAndTheNextWriteHealsIt() {
        val (dir, board, clock) = board("board-twice")
        board.create(new("Twice"), id("R1"))
        createDirAll("$dir/in-progress")
        clock.now += 60
        writeString("$dir/in-progress/R1-twice.md", readToString("$dir/todo/R1-twice.md").replace(Regex("updated: .*"), "updated: 2030-01-01T00:00:00Z"))
        assertEquals(Status.InProgress, board.get(id("R1"))!!.status, "the newer copy wins")
        board.writeStatus(id("R1"), Status.Done, null)
        assertTrue(!exists("$dir/todo/R1-twice.md") && !exists("$dir/in-progress/R1-twice.md") && isFile("$dir/done/R1-twice.md"))
    }

    @Test fun theCaseOnlyRenameGuardNeverDeletesTheFileJustWritten() {
        val (dir, board, _) = board("board-case")
        board.create(new("Cache"), id("R1"))
        // On APFS `R1-Cache.md` and `R1-cache.md` are one file: a "move" between the two spellings must keep it.
        val upper = "$dir/todo/R1-CACHE.md"
        val lower = board.pathOf(board.get(id("R1"))!!)
        assertTrue(upper.equals(lower.replace("cache", "CACHE"), ignoreCase = true))
        board.save(board.get(id("R1"))!!.copy(title = "CACHE", priority = 3))
        assertEquals("CACHE", board.get(id("R1"))!!.title)
        assertEquals(1, readDir("$dir/todo").size)
    }

    @Test fun aClosingReportIsWrittenWithTheMoveAndReadBack() {
        val (dir, board, _) = board("board-report")
        board.create(new("Report", "The why."), id("R1"))
        val done = board.writeStatus(id("R1"), Status.Done, "  Fixed in #12.\n\nChecked by hand. ")
        assertEquals("Fixed in #12.\n\nChecked by hand.", done.report)
        val text = readToString("$dir/done/R1-report.md")
        assertTrue(text.endsWith("The why.\n\n## Report\n\nFixed in #12.\n\nChecked by hand.\n"), text)
        val back = board.get(id("R1"))!!
        assertEquals("The why.", back.description)
        assertEquals(done.report, back.report)
        assertTrue(board.movesWithReport())
        board.writeReport(id("R1"), "Second thoughts.")
        assertEquals("Second thoughts.", board.get(id("R1"))!!.report)
        board.create(new("Active"), id("R2"))
        assertFailsWith<TaskException> { board.writeReport(id("R2"), "no") }
        assertNull(board.get(id("R2"))!!.report)
    }

    @Test fun unknownKeysSurviveEveryRewrite() {
        val (dir, board, _) = board("board-extra")
        board.create(new("Linked"), id("R1"))
        val path = "$dir/todo/R1-linked.md"
        writeString(path, readToString(path).replace("\n---\n", "\nremote: https://github.com/o/r/issues/4\nlabels:\n  - a\n  - b\n---\n"))
        board.save(board.get(id("R1"))!!.copy(priority = 0, assignee = "x"))
        board.writeStatus(id("R1"), Status.Done, "ok")
        val text = readToString("$dir/done/R1-linked.md")
        assertTrue("remote: https://github.com/o/r/issues/4\nlabels:\n  - a\n  - b\n---\n" in text, text)
    }

    @Test fun isoAndUnixTimestampsAreBothRead() {
        val (dir, board, _) = board("board-time")
        createDirAll("$dir/todo")
        writeString("$dir/todo/R1-iso.md", "---\nid: R1\ntitle: Iso\ncreated: 2026-11-09T10:54:00Z\nupdated: 2026-11-09T11:00:00Z\n---\n")
        writeString("$dir/todo/R2-unix.md", "---\nid: R2\ntitle: Unix\ncreated_at: 1000\nupdated_at: 2000\n---\n")
        val iso = board.get(id("R1"))!!
        assertEquals(1794221640L, iso.createdAt)
        assertEquals(360L, iso.updatedAt - iso.createdAt)
        assertEquals(1000L to 2000L, board.get(id("R2"))!!.let { it.createdAt to it.updatedAt })
        assertEquals("2026-11-09T10:54:00Z", isoTime(iso.createdAt))
    }

    @Test fun oldSpacedNamesAreFoundAndRenamedOnWrite() {
        val (dir, board, _) = board("board-old")
        createDirAll("$dir/done")
        writeString("$dir/done/R1 - old-name.md", "---\nid: R1\ntitle: Old name\ncreated_at: 1\nupdated_at: 2\n---\n")
        assertEquals("Old name", board.get(id("R1"))!!.title)
        board.writeStatus(id("R1"), Status.Todo, null)
        assertEquals(listOf("R1-old-name.md"), readDir("$dir/todo"))
        assertTrue(readDir("$dir/done").isEmpty())
    }

    @Test fun unknownFoldersAndBrokenFilesAreSkippedWithAWarning() {
        val warnings = mutableListOf<String>()
        val (dir, board, _) = board("board-skip", warnings = warnings)
        board.create(new("Fine"), id("R1"))
        createDirAll("$dir/later")
        writeString("$dir/todo/R2-broken.md", "no front matter")
        writeString("$dir/todo/README.md", "notes")
        assertEquals(listOf(id("R1")), board.list(Filter(all = true)).map { it.id })
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.any { "later" in it } && warnings.any { "R2-broken" in it }, "$warnings")
        assertEquals(warnings, board.warnings)
        assertEquals(id("R2"), board.maxId("r"), "a broken file still owns its id")
        assertFailsWith<TaskException> { board.get(id("R2")) }
    }

    @Test fun readsCreateNothingAndAnExistingGitignoreIsKept() {
        val dir = "${tmpDir("board-read")}/tasks"
        val board = BoardAdapter(dir)
        assertTrue(board.list(Filter(all = true)).isEmpty())
        assertNull(board.get(id("R1")))
        assertTrue(!exists(dir), "listing must not create the board")
        createDirAll(dir)
        writeString("$dir/.gitignore", "mine\n")
        board.create(new("x"), id("R1"))
        assertEquals("mine\n", readToString("$dir/.gitignore"))
    }

    @Test fun createRefusesATakenIdAndAnEmptyTitle() {
        val (_, board, _) = board("board-taken")
        board.create(new("One"), id("R1"))
        assertFailsWith<Taken> { board.create(new("Again"), id("R1")) }
        assertFailsWith<TaskException> { board.create(new(" "), id("R2")) }
    }
}
