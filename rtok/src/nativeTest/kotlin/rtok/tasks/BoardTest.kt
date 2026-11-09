@file:Suppress("MagicNumber")

// The board layout as rtok opens it. The file format is kotlin-taskboard's own and is tested there; these check the
// wiring: which store a checkout gets, and that every write goes through the engine.

package rtok.tasks

import rtok.config.Tasks
import rtok.store.Store
import rtok.store.TaskId
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.readDir
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun checkout(tag: String): String {
    val dir = tmpDir(tag)
    createDirAll("$dir/.git")
    writeString("$dir/.git/config", "[remote \"origin\"]\n\turl = git@github.com:me/airtalk.git\n")
    return dir
}

private fun store(): Store {
    installDb()
    return Store.openInMemory()
}

private val COLUMNS = listOf("todo", "in-progress", "done", "closed")

private fun filesIn(dir: String) = COLUMNS.flatMap { c -> runCatching { readDir("$dir/$c") }.getOrDefault(emptyList()).map { "$c/$it" } }

class BoardTest {
    @Test fun aNewCheckoutGetsTheBoardAndEveryMoveRelocatesTheOneFile() {
        val root = checkout("board-moves")
        val store = store()
        val p = TaskProject.open(Tasks(), root)
        assertEquals("board", p.layout)
        val made = p.create(store, NewTask("Починить кэш прокси!", "Why."))
        assertEquals("A1", made.id.toString())
        assertTrue(isFile("$root/tasks/todo/A1-починить-кэш-прокси.md"), filesIn("$root/tasks").toString())
        for ((status, folder) in listOf(Status.InProgress to "in-progress", Status.Todo to "todo", Status.Done to "done")) {
            p.status(store, id("A1"), status, false, if (status.isActive) null else "Fixed.")
            assertEquals(listOf("$folder/A1-починить-кэш-прокси.md"), filesIn("$root/tasks"))
        }
        val done = p.get(id("A1"))
        assertEquals(Status.Done, done.status)
        assertEquals("Why.", done.description)
        assertEquals("Fixed.", done.report)
        removeDirAll(root)
    }

    @Test fun readsCreateNothingAndTheAdapterRefusesWrites() {
        val root = checkout("board-read")
        val p = TaskProject.open(Tasks(), root)
        assertTrue(p.listAll().isEmpty())
        assertTrue(p.adapter.list(Filter(all = true)).isEmpty())
        assertTrue(!exists("$root/tasks"), "a read must not create the board")
        assertFailsWith<TaskException> { p.adapter.create(NewTask("x", ""), id("A1")) }
        assertTrue(!exists("$root/tasks"))
        removeDirAll(root)
    }

    @Test fun aBrokenFileIsSkippedWithAWarningOnTheBoard() {
        val root = checkout("board-skip")
        val store = store()
        val p = TaskProject.open(Tasks(), root)
        p.create(store, NewTask("Fine", ""))
        writeString("$root/tasks/todo/A2-broken.md", "no front matter\n")
        val view = p.board(all = true)
        assertEquals(listOf(id("A1")), view.columns.flatMap { c -> c.tasks.map { it.id } })
        assertTrue(view.warnings.any { "A2-broken" in it }, view.warnings.toString())
        // The id a broken file carries stays its own: the next create names the file instead of overwriting it.
        val refused = assertFailsWith<TaskException> { p.create(store, NewTask("Next", "")) }
        assertTrue("A2-broken.md" in refused.message!!, refused.message)
        assertTrue(exists("$root/tasks/todo/A2-broken.md"))
        removeDirAll(root)
    }
}
