@file:Suppress("MagicNumber")

package rtok.tasks

import rtok.config.Tasks
import rtok.config.TasksDisk
import rtok.store.TaskId
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.readToString
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun checkout(tag: String) = tmpDir(tag).also { createDirAll("$it/.git") }

/** A legacy `files` directory with one task per column and the old lock file. */
private fun legacy(dir: String) {
    val disk = DiskAdapter(dir)
    disk.create(NewTask("Todo one", "Why."), id("R1"))
    disk.create(NewTask("Doing two"), id("R2"))
    disk.create(NewTask("Done three"), id("R3"))
    disk.create(NewTask("Closed four"), id("R4"))
    disk.save(disk.get(id("R2"))!!.copy(status = Status.InProgress, assignee = "agent-1"))
    disk.writeStatus(id("R3"), Status.Done, null)
    disk.writeStatus(id("R4"), Status.Closed, null)
    disk.claimGuard().close()
}

class MigrateTest {
    @Test fun dryRunListsEveryMoveAndWritesNothing() {
        val root = checkout("migrate-dry")
        legacy("$root/tasks")
        val r = migrateTasks(Tasks(), root, dryRun = true)
        assertEquals(
            listOf(
                "R1: tasks/R1 - todo-one.md -> tasks/todo/R1-todo-one.md",
                "R2: tasks/R2 - doing-two.md -> tasks/in-progress/R2-doing-two.md",
                "R3: tasks/done/R3 - done-three.md -> tasks/done/R3-done-three.md",
                "R4: tasks/done/R4 - closed-four.md -> tasks/closed/R4-closed-four.md",
            ),
            r.text().lines().filter { it.startsWith("R") },
        )
        assertTrue(r.text().endsWith("would move 4 tasks\n"), r.text())
        assertTrue(isFile("$root/tasks/R1 - todo-one.md") && !exists("$root/tasks/todo"))
    }

    @Test fun migrateMovesEachTaskToItsColumnAndIsIdempotent() {
        val root = checkout("migrate-run")
        legacy("$root/tasks")
        val before = DiskAdapter("$root/tasks").list(Filter(all = true))
        val r = migrateTasks(Tasks(), root, dryRun = false)
        assertEquals(4, r.moved.size)
        for (f in listOf("todo/R1-todo-one.md", "in-progress/R2-doing-two.md", "done/R3-done-three.md", "closed/R4-closed-four.md")) assertTrue(isFile("$root/tasks/$f"), f)
        assertTrue(!isFile("$root/tasks/R1 - todo-one.md") && !isFile("$root/tasks/done/R3 - done-three.md"))
        assertTrue(!exists("$root/tasks/.claim.lock"))
        assertEquals(".lock\n.sync/\n", readToString("$root/tasks/.gitignore"))
        val after = BoardAdapter("$root/tasks").list(Filter(all = true))
        assertEquals(before, after, "timestamps, assignee and statuses carry over")
        assertEquals("board", detectLayout("$root/tasks"))
        assertEquals("nothing to migrate\n", migrateTasks(Tasks(), root, dryRun = false).text())
    }

    @Test fun oldConfigKeysAreRewrittenAndOtherLinesStay() {
        val root = checkout("migrate-config")
        legacy("$root/work")
        writeString("$root/.rtok.toml", "# mine\n[tasks]\nprefix = \"Q\"\n\n[tasks.disk]\ndir = \"work\"\nlayout = \"files\"\n\n[other]\nx = 1\n")
        val cfg = Tasks(prefix = "Q", disk = TasksDisk(dir = "work", layout = "files"))
        val dry = migrateTasks(cfg, root, dryRun = true)
        assertEquals(2, dry.config.size)
        assertTrue("[tasks.disk]" in readToString("$root/.rtok.toml"), "a dry run writes nothing")
        migrateTasks(cfg, root, dryRun = false)
        assertEquals("# mine\n[tasks]\nprefix = \"Q\"\ndir = \"work\"\n\n[other]\nx = 1\n", readToString("$root/.rtok.toml"))
        assertTrue(isFile("$root/work/todo/R1-todo-one.md"))
    }

    @Test fun foldersBecomeThePlanLayoutKey() {
        val root = checkout("migrate-folders")
        writeString("$root/.rtok.toml", "[tasks.disk]\nlayout = \"folders\"\n")
        val r = migrateTasks(Tasks(disk = TasksDisk(layout = "auto")), root, dryRun = false)
        assertEquals(1, r.config.size)
        assertEquals("[tasks]\nlayout = \"plan\"\n", readToString("$root/.rtok.toml").lines().filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
    }

    @Test fun theWorkspacePlanLayoutIsRefused() {
        val root = checkout("migrate-plan")
        createDirAll("$root/tasks/T1")
        writeString("$root/tasks/T1/task.md", "---\nstatus: todo\npriority: P2\ncomplexity: 1\nreadiness: 0%\nagent:\n---\n\n# T1. x\n")
        val e = assertFailsWith<TaskException> { migrateTasks(Tasks(), root, dryRun = false) }
        assertTrue("plan/ convention" in e.message.orEmpty(), e.message)
    }
}
