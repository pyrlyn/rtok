@file:Suppress("MagicNumber")

// Port of the src/tasks/mod.rs, meta.rs, adapter.rs and ready.rs tests (TaskId's own tests live in :rtok-store).

package rtok.tasks

import kotlinx.serialization.json.Json
import rtok.store.Store
import rtok.store.TaskId
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun task(s: String) = Task.open(id(s), s, "", 0)

class TasksTest {
    @Test fun defaultPrefixIsTheFirstLetter() {
        for ((p, want) in listOf("rtok" to "R", "airtalk" to "A", "2fa-app" to "F", "123" to null)) assertEquals(want, defaultPrefix(p), p)
    }

    @Test fun aConfiguredPrefixWinsOverTheProjectName() {
        assertEquals("AT", resolvePrefix("at", "rtok"))
        assertEquals("R", resolvePrefix("", "rtok"))
        assertFailsWith<TaskException> { resolvePrefix("", "123") }
        assertFailsWith<TaskException> { resolvePrefix("", null) }
        assertFailsWith<Exception> { resolvePrefix("R2", "rtok") }
    }

    @Test fun statusesRoundTripAndTakeHostWords() {
        for (s in Status.entries) {
            assertEquals(s, Status.parse(s.str))
            assertEquals("\"$s\"", Json.encodeToString(Status.serializer(), s))
        }
        for ((w, want) in listOf("pending" to Status.Todo, "in_progress" to Status.InProgress, "In-Progress" to Status.InProgress, "completed" to Status.Done, "not_planned" to Status.Closed)) {
            assertEquals(want, Status.parse(w), w)
        }
        assertFailsWith<TaskException> { Status.parse("blocked") }
        assertTrue(Status.Todo.isActive && Status.InProgress.isActive)
        assertFalse(Status.Done.isActive || Status.Closed.isActive)
    }

    @Test fun aTaskRoundTripsThroughJsonWithoutEmptyOptionals() {
        val t = Task.open(id("R3"), "Ship it", "", 0).copy(status = Status.InProgress, createdAt = 1, updatedAt = 2)
        val json = TASK_JSON.encodeToString(Task.serializer(), t)
        for (k in listOf("parent", "external", "priority", "assignee", "blocked_by", "conflicts_with", "parallel")) assertFalse(k in json, json)
        assertEquals(t, TASK_JSON.decodeFromString(Task.serializer(), json))
        assertEquals(id("R3"), Task.open(id("R3.1"), "", "", 0).parent)
    }

    @Test fun readyAndClaimJsonFlattenTheTask() {
        val item = ReadyItem(task("R1"), stale = true).toJson()
        assertEquals("\"R1\"", item["id"].toString())
        assertEquals("true", item["stale"].toString())
        assertNull(item["conflicts_in_progress"])
        assertEquals("false", ClaimOutcome(task("R1"), false).toJson()["changed"].toString())
    }

    @Test fun markersRoundTripAndAHandEditedLabelIsIgnored() {
        val labels = listOf("bug", "rtok:conflicts:A3", "rtok:conflicts:A2", "rtok:conflicts:A2", "rtok:conflicts:not-an-id", "rtok:parallel")
        val meta = Meta.read(labels)
        assertEquals(listOf(id("A2"), id("A3")), meta.conflictsWith)
        assertTrue(meta.parallel)
        assertEquals(5, labels.count(Meta::isManaged))
        assertFalse(Meta.read(listOf("rtok:parallels")).parallel)
    }

    @Test fun managedLabelsReplaceOnlyTheirOwn() {
        val t = task("R1").copy(assignee = "a", priority = 0, blockedBy = listOf(id("R2")), parallel = true)
        val merged = Meta.mergeLabels(listOf("bug", "rtok:owner:old", "rtok:p:9", "ui"), t)
        assertEquals(listOf("bug", "ui", "rtok:owner:a", "rtok:needs:R2", "rtok:p:0", "rtok:parallel"), merged)
        val back = Meta.read(merged)
        assertEquals(0, back.priority)
        assertEquals("a", back.assignee)
        assertEquals(DEFAULT_PRIORITY, Meta.read(listOf("rtok:p:9")).priority)
    }

    @Test fun aFilterHidesFinishedTasksUnlessAsked() {
        val done = task("R2").copy(status = Status.Done)
        val tasks = listOf(task("R3"), done, task("R1.1"))
        assertEquals(listOf(id("R1.1"), id("R3")), Filter().select(tasks).map { it.id })
        assertEquals(3, Filter(all = true).select(tasks).size)
        assertEquals(listOf(id("R1.1")), Filter(parent = id("R1")).select(tasks).map { it.id })
        assertTrue(Filter(statuses = listOf(Status.Done)).wantsFinished)
        assertFalse(Filter().wantsFinished)
    }
}

// The queue, cycle and subtask rules are kotlin-taskboard's and tested there; these check rtok's side of them.
class ReadyTest {
    @Test fun aMissingAgentIsStaleAndAStoreErrorIsNot() {
        assertTrue(Ready.holderIsStale(Result.success(null), 0))
        assertFalse(Ready.holderIsStale(Result.success(1_000L), 1_000 + STALE_SECS))
        assertTrue(Ready.holderIsStale(Result.success(1_000L), 1_000 + STALE_SECS + 1))
        assertFalse(Ready.holderIsStale(Result.failure(Exception("db")), 9_999))
    }

    @Test fun anAdapterWithOnlyTheRequiredMethodsGetsTheEngineRules() {
        val tasks = mutableMapOf(id("R1") to task("R1"), id("R1.1") to task("R1.1").copy(parent = id("R1")))
        val adapter =
            object : TaskAdapter {
                override val name = "mem"

                override fun create(
                    task: NewTask,
                    id: TaskId,
                ) = error("unused")

                override fun list(filter: Filter) = filter.select(tasks.values)

                override fun get(id: TaskId) = tasks[id]

                override fun writeStatus(
                    id: TaskId,
                    status: Status,
                    report: String?,
                ) = tasks.getValue(id).copy(status = status).also { tasks[id] = it }

                override fun maxId(prefix: String) = tasks.keys.maxOrNull()
            }
        installDb()
        val store = Store.openInMemory()
        val root = tmpDir("mem-adapter")
        val p = TaskProject("proj", root, "R", adapter)
        val e = assertFailsWith<TaskException> { p.status(store, id("R1"), Status.Done, false, null, actor = null) }
        assertTrue("R1.1" in e.message!!, e.message)
        assertEquals(Status.Done, p.status(store, id("R1"), Status.Done, true, null, actor = null).status)
        assertEquals(Status.Done, tasks.getValue(id("R1")).status)
        assertFailsWith<TaskException> { adapter.save(task("R1")) }
        assertFalse(adapter.storesMarkers())
        removeDirAll(root)
    }
}
