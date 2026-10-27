// Port of the src/model/tasks.rs tests (T441.13.7, T630): the board lists a fixture project the way
// `rtok task list --all` does; no [tasks] is the empty state; a failed adapter is the page's text; the finished cap.
@file:Suppress("MagicNumber")

package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.config.Layers
import rtok.store.TaskId
import rtok.support.diskConfig
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.writeString
import rtok.tasks.ExternalRef
import rtok.tasks.Filter
import rtok.tasks.NewTask
import rtok.tasks.Status
import rtok.tasks.Task
import rtok.tasks.TaskProject
import rtok.tasks.taskTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun checkout(name: String): String {
    val dir = tmpDir(name)
    createDirAll("$dir/.git")
    writeString("$dir/.git/config", "[remote \"origin\"]\n\turl = git@github.com:me/airtalk.git\n")
    return dir
}

private fun task(
    name: String,
    status: Status,
    updated: Long,
) = Task.open(id(name), "title $name", "a long body", 0).copy(status = status, updatedAt = updated)

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

class TasksPageTest {
    @Test fun theBoardListsAFixtureProjectTheWayTaskListDoes() {
        val cfg = diskConfig("tasks-page-home").first
        val dir = checkout("tasks-page-board")
        val disk = TaskProject.open(cfg.tasks, dir).adapter
        listOf("A1" to null, "A1.1" to "A1", "A2" to null, "A3" to null).forEach { (n, p) ->
            disk.create(NewTask("title $n", "a long body", p?.let(::id)), id(n))
        }

        fun get(n: String) = disk.get(id(n))!!
        disk.save(get("A1").copy(status = Status.InProgress, assignee = "agent-1"))
        disk.save(get("A1.1").copy(blockedBy = listOf(id("A2")), conflictsWith = listOf(id("A3")), parallel = true, priority = 0))
        disk.save(get("A2").copy(status = Status.Done))

        val page = TasksPage.read(cfg, dir, true)
        assertEquals("ready", page.state, page.text)
        val board = page.board!!
        assertEquals("disk", board.s("adapter"))
        val cli = disk.list(Filter(all = true))
        val cards = board["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(cli.map { it.id.toString() }, cards.map { it.s("id") }, "the same tasks in the same order")
        assertEquals(taskTable(cli), page.text)

        fun card(n: String) = cards.first { it.s("id") == n }
        assertEquals("in-progress", card("A1").s("status"))
        assertEquals("agent-1", card("A1").s("assignee"))
        assertEquals("done", card("A2").s("status"))
        assertEquals("open", card("A3").s("status"))
        val c = card("A1.1")
        assertEquals("A1", c.s("parent"))
        assertEquals(listOf("A2"), c["blocked_by"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("A3"), c["conflicts_with"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", c.s("parallel"))
        assertEquals("0", c.s("priority"))
        assertFalse(page.toJson().toString().contains("a long body"), "a card carries no description")
    }

    @Test fun aProjectWithoutTasksGetsTheEmptyState() {
        val cfg = diskConfig("tasks-page-none").first
        val dir = checkout("tasks-page-none-repo")
        val page = TasksPage.read(cfg, dir, false)
        assertEquals("unconfigured", page.state)
        assertNull(page.board)
        assertTrue(page.text.contains("rtok task init"), page.text)
        assertFalse(exists("$dir/tasks"), "reading creates nothing")
    }

    @Test fun aLayerThatSetsTasksMakesTheProjectConfigured() {
        val home = tmpDir("tasks-page-layer")
        val cfg = diskConfig("tasks-page-layer-cfg").first.also { it.home = home }
        assertFalse(TasksPage.configured(cfg))
        writeString("$home/config.toml", "[tasks]\nprefix = \"T\"\n")
        assertTrue(TasksPage.configured(cfg))
    }

    @Test fun aFailedAdapterIsThePagesTextNotAFailedSnapshot() {
        val cfg = diskConfig("tasks-page-fail").first
        val page = TasksPage.read(cfg, tmpDir("tasks-page-nogit"), true)
        assertEquals("failed", page.state)
        assertNull(page.board)
        assertTrue(page.text.startsWith("tasks did not answer"), page.text)
    }

    @Test fun theCapKeepsTheNewestFinishedTasksAndEveryActiveOne() {
        val tasks = (1..60).map { task("A$it", Status.Done, it.toLong()) } + task("A61", Status.Open, 0) + task("A62", Status.Closed, 1)
        val page = TasksPage.ready("disk", tasks)
        val cards = page.board!!["tasks"]!!.jsonArray.map { it.jsonObject }
        val done = cards.filter { it.s("status") == "done" }.map { it.s("id") }
        assertEquals(TasksPage.FINISHED_SHOWN, done.size)
        assertTrue("A60" in done && "A10" !in done, "newest kept")
        assertTrue(cards.any { it.s("id") == "A61" }, "active kept")
        assertTrue(cards.any { it.s("id") == "A62" }, "closed has its own cap")
        assertEquals("10", page.board!!.s("hidden"))
        assertTrue(page.text.contains("10 older done or closed"), page.text)
    }

    @Test fun onlyAnHttpIssueLinkReachesThePage() {
        fun link(url: String) = TasksPage.card(task("A1", Status.Open, 0).copy(external = ExternalRef("github", 1, url)))["external"]
        assertEquals("1", (link("https://github.com/me/airtalk/issues/1") as JsonObject).s("number"))
        assertEquals(JsonNull, link("javascript:alert(1)"))
        assertEquals(JsonNull, link("data:text/html,x"))
    }

    @Test fun anEmptyProjectSaysHowToAddATask() {
        val page = TasksPage.ready("disk", emptyList())
        assertEquals("ready", page.state)
        assertTrue(page.text.contains("rtok task create"), page.text)
        assertEquals(JsonArray(emptyList()), page.board!!["tasks"])
    }

    @Test fun configuredFollowsTheSelectedConfigFile() {
        val home = tmpDir("tasks-cfg")
        val custom = "$home/custom.toml"
        writeString(custom, "[tasks]\nprefix = \"Z\"\n")
        val cfg: Config = Layers.load(home, custom, null)
        assertEquals("Z", cfg.tasks.prefix)
        assertTrue(TasksPage.configured(cfg), "the explicit file sets [tasks]")
        writeString("$home/config.toml", "[tasks]\nprefix = \"A\"\n")
        assertEquals("Z", Layers.load(home, cfg.loadedFrom, null).tasks.prefix)
        assertTrue(TasksPage.configured(Layers.load(home, null, null)), "the default file still counts")
        writeString("$home/config.toml", "")
        assertFalse(TasksPage.configured(Layers.load(home, null, null)), "defaults alone are not configured")
    }

    @Test fun theSnapshotCarriesTheTasksPage() {
        val snap = Snapshot.build(diskConfig("tasks-page-snap").first)
        val tasks = snap["tasks"]!!.jsonObject
        assertTrue(tasks.s("state") in setOf("reading", "unconfigured", "failed", "ready"), tasks.toString())
    }
}
