@file:Suppress("MagicNumber")

// Port of the src/tasks/sync.rs tests (the GitHub read-only test waits for the github adapter).

package rtok.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.allocateTaskId
import rtok.store.seedTaskCounter
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun project(adapter: TaskAdapter) = TaskProject("p", "/tmp", "A", adapter)

private fun store(): Store {
    installDb()
    return Store.openInMemory()
}

/** An adapter that lists whatever it was given, twins and all. */
private class Fixed(
    val tasks: List<Task>,
    val strays: List<Stray>,
) : TaskAdapter {
    override val name = "fixed"

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task = error("sync never writes")

    override fun list(filter: Filter) = filter.select(tasks)

    override fun get(id: TaskId): Task? = error("unused")

    override fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task = error("sync never writes")

    override fun maxId(prefix: String): TaskId? = error("unused")

    override fun unlabelled() = strays
}

class TaskSyncTest {
    @Test fun diskIdsAboveTheCounterRaiseItAndASecondRunIsQuiet() {
        val dir = tmpDir("tasksync")
        val disk = DiskAdapter(dir)
        disk.create(NewTask("Pulled"), id("A7"))
        disk.create(NewTask("Leaf"), id("A7.2"))
        disk.writeStatus(id("A7"), Status.Done)
        val store = store()
        store.seedTaskCounter("p", null, 3u)
        val p = project(disk)
        val r = syncTasks(p, store)
        assertEquals(7u to "A8", r.counter to r.next.toString())
        assertEquals(listOf(id("A7"), id("A7.2")), r.aboveCounter)
        assertEquals(listOf(Raised(null, 3u, 7u), Raised(id("A7"), 0u, 2u)), r.raised)
        assertEquals("disk: next id A8\nraised top level: 3 -> 7\nraised subtasks of A7: 0 -> 2\nabove the counter: A7, A7.2\n", syncText(r))
        assertEquals(id("A8"), store.allocateTaskId("p", "A", null))
        assertEquals(id("A7.3"), store.allocateTaskId("p", "A", id("A7")))
        val again = syncTasks(p, store)
        assertTrue(again.isClean && again.raised.isEmpty())
        assertTrue(syncText(again).endsWith("counters in step\n"), syncText(again))
        removeDirAll(dir)
    }

    @Test fun aCounterAheadOfTheAdapterIsNeverLowered() {
        val dir = tmpDir("tasksync-ahead")
        val store = store()
        store.seedTaskCounter("p", null, 9u)
        val r = syncTasks(project(DiskAdapter("$dir/none")), store)
        assertEquals(Triple(9u, 0, true), Triple(r.counter, r.raised.size, r.isClean))
        removeDirAll(dir)
    }

    @Test fun duplicatesAndUnlabelledIssuesAreReported() {
        val stray = Stray("A5. Lost", "https://github.com/me/app/issues/9", id("A5"))
        val t = { s: String -> Task.open(id(s), "T", "", 0) }
        val store = store()
        store.seedTaskCounter("p", null, 3u)
        val r = syncTasks(project(Fixed(listOf(t("A2"), t("A2"), t("A2"), t("A3")), listOf(stray))), store)
        assertEquals(listOf(id("A2")), r.duplicates)
        assertTrue(r.aboveCounter.isEmpty() && r.raised.isEmpty())
        assertFalse(r.isClean)
        assertEquals("fixed: next id A4\nduplicate ids: A2\nno id label: A5. Lost (label rtok:A5 is gone) https://github.com/me/app/issues/9\n", syncText(r))
    }

    @Test fun theReportSerializesForJson() {
        val r = SyncReport("disk", 2u, id("A3"), listOf(Raised(null, 0u, 2u)), listOf(id("A2")), emptyList(), emptyList())
        val v = Json.encodeToJsonElement(r).jsonObject
        assertEquals(JsonPrimitive("A3"), v["next"])
        assertEquals(Json.parseToJsonElement("""{"parent":null,"from":0,"to":2}"""), v["raised"]!!.jsonArray[0])
        assertEquals(Json.parseToJsonElement("""["A2"]"""), v["above_counter"])
    }
}
