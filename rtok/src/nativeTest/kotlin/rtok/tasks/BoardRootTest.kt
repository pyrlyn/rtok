@file:Suppress("MagicNumber")

package rtok.tasks

import rtok.config.ProjectRoot
import rtok.config.Tasks
import rtok.config.TasksDisk
import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun rootOf(path: String) = ProjectRoot.find(path)!!

class BoardRootTest {
    @Test fun aSharedBoardLivesUnderTheMainCheckoutFromAnyWorktree() {
        val (main, wt) = worktreeLayout(tmpDir("board-root"))
        assertEquals("$main/tasks", boardDirOf(Tasks(), rootOf(wt)))
        assertEquals("$main/tasks", boardDirOf(Tasks(), rootOf(main)))
    }

    @Test fun anUnsharedBoardStaysInItsCheckout() {
        val (main, wt) = worktreeLayout(tmpDir("board-own"))
        assertEquals("$wt/tasks", boardDirOf(Tasks(shared = false), rootOf(wt)))
        assertEquals("$main/work", boardDirOf(Tasks(dir = "work"), rootOf(main)))
    }

    @Test fun theOldDiskKeysStillNameTheDirectoryAndTheLayout() {
        val tasks = Tasks(disk = TasksDisk(dir = "old", layout = "folders"))
        assertEquals("old", tasks.boardDirName())
        assertEquals("plan", tasks.layoutName())
        assertEquals("new", Tasks(dir = "new", disk = TasksDisk(dir = "old")).boardDirName())
        assertEquals("files", Tasks(layout = "files", disk = TasksDisk(layout = "folders")).layoutName())
        assertEquals("files", Tasks(disk = TasksDisk(layout = "files")).layoutName())
        assertEquals("auto", Tasks().layoutName())
    }

    @Test fun aDirThatLeavesTheRootIsRefused() {
        val (main, _) = worktreeLayout(tmpDir("board-escape"))
        for (bad in listOf("../elsewhere", "a/../../b", "/abs/tasks", "", "..")) {
            val e = assertFailsWith<TaskException>(bad) { boardDirOf(Tasks(dir = bad), rootOf(main)) }
            assertTrue("[tasks] dir" in e.message.orEmpty(), e.message)
        }
    }

    @Test fun aSymlinkOutOfTheRootIsRefused() {
        val (main, _) = worktreeLayout(tmpDir("board-link"))
        val outside = tmpDir("board-outside")
        createDirAll(main)
        writeString("$outside/f", "x")
        assertEquals(0, platform.posix.symlink(outside, "$main/escape"))
        assertFailsWith<TaskException> { boardDirOf(Tasks(dir = "escape/tasks"), rootOf(main)) }
    }

    @Test fun autoReadsTheDirectoryItIsGiven() {
        val dir = tmpDir("board-detect")
        assertEquals("board", detectLayout("$dir/missing"))
        assertEquals("board", detectLayout(dir))
        createDirAll("$dir/a/done")
        DiskAdapter("$dir/a").create(NewTask("x"), rtok.store.TaskId.parse("R1"))
        assertEquals("files", detectLayout("$dir/a"))
        writeString("$dir/b/T1/task.md".also { createDirAll("$dir/b/T1") }, "x")
        assertEquals("plan", detectLayout("$dir/b"))
        createDirAll("$dir/c/in-progress")
        assertEquals("board", detectLayout("$dir/c"))
    }
}
