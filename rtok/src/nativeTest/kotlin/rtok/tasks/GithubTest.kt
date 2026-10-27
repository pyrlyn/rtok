@file:Suppress("MagicNumber")

package rtok.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.store.Store
import rtok.store.TaskId
import rtok.support.installDb
import rtok.support.tmpDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

internal const val ISSUES = "/repos/me/app/issues"

internal fun tid(s: String) = TaskId.parse(s)

/** An issue as the REST API returns it, trimmed to the fields the adapter reads. */
internal fun issue(
    number: Int,
    task: String,
    state: String,
    reason: String? = null,
    extra: List<String> = emptyList(),
    labels: List<String>? = null,
): JsonObject =
    buildJsonObject {
        put("id", number * 100)
        put("node_id", "I_kw$number")
        put("number", number)
        put("html_url", "https://github.com/me/app/issues/$number")
        put("title", "$task. Task $number")
        put("body", "Why.\n")
        put("state", state)
        put("state_reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        put("labels", buildJsonArray { (labels ?: (listOf("rtok", "rtok:$task") + extra)).forEach { add(buildJsonObject { put("name", it) }) } })
        put("created_at", "2026-10-07T10:00:00Z")
        put("updated_at", "2026-10-07T11:00:00Z")
    }

internal fun arr(vararg e: JsonElement) = JsonArray(e.toList()).toString()

/** Answer the label lookup `labels=<label>&state=<state>`, only with the adapter's token. */
internal fun FakeHttp.byLabel(
    label: String,
    state: String,
    body: String,
) = on("GET", ISSUES, "labels=${queryString(listOf("l" to label)).removePrefix("?l=")}&state=$state&") { c ->
    if (c.headers.contains("authorization" to "Bearer test-token")) ok(body) else status(401)
}

internal fun FakeHttp.bodies(
    method: String,
    path: String,
) = calls.filter { it.method == method && it.path == path }.map { it.json() }

internal fun json(s: String) = Json.parseToJsonElement(s)

/** Whether every key of [part] is in [whole] with the same value (httpmock's `json_body_includes`). */
internal fun includes(
    whole: JsonElement,
    part: String,
) = (json(part) as JsonObject).all { (k, v) -> (whole as JsonObject)[k] == v }

internal fun github(
    fake: FakeHttp,
    project: Long = 0,
) = GithubAdapter("http://mock.test", "me/app", "test-token", Duration.ZERO, fake, project)

class GithubTest {
    private fun new(
        title: String,
        description: String = "",
        parent: TaskId? = null,
    ) = NewTask(title, description, parent)

    @Test fun createLabelsTheIssueAndLinksItUnderItsParent() {
        val fake = FakeHttp()
        fake.byLabel("rtok:A2.1", "all", "[]")
        fake.byLabel("rtok:A2", "all", arr(issue(5, "A2", "open")))
        fake.on("POST", ISSUES) { status(201, issue(9, "A2.1", "open").toString()) }
        fake.on("POST", "$ISSUES/5/sub_issues") { status(201, issue(5, "A2", "open").toString()) }
        val task = github(fake).create(new(" Leaf ", "Done means tested.", tid("A2")), tid("A2.1"))
        assertEquals(json("""{"title":"A2.1. Leaf","body":"Done means tested.","labels":["rtok","rtok:A2.1"]}"""), fake.bodies("POST", ISSUES).single())
        assertEquals(json("""{"sub_issue_id":900}"""), fake.bodies("POST", "$ISSUES/5/sub_issues").single())
        assertEquals("Task 9", task.title, "read back from the issue title")
        assertEquals(tid("A2"), task.parent)
        assertEquals(Status.Open, task.status)
        assertEquals(1_791_367_200, task.createdAt)
        val x = task.external!!
        assertEquals(9L to "I_kw9", x.number to x.nodeId)
        assertEquals("https://github.com/me/app/issues/9", x.url)
    }

    @Test fun createRefusesATakenIdAMissingParentAndADroppedLabel() {
        val fake = FakeHttp()
        val gh = github(fake)
        fake.byLabel("rtok:A1", "all", arr(issue(1, "A1", "closed", "completed")))
        assertFailsWith<Taken> { gh.create(new("Again"), tid("A1")) }

        fake.byLabel("rtok:A3.1", "all", "[]")
        fake.byLabel("rtok:A3", "all", "[]")
        val orphan = assertFailsWith<TaskException> { gh.create(new("Orphan"), tid("A3.1")) }
        assertTrue("no parent task A3" in orphan.message!!, orphan.message)

        fake.byLabel("rtok:A4", "all", "[]")
        fake.on("POST", ISSUES) { status(201, issue(12, "A4", "open", labels = emptyList()).toString()) }
        val bare = assertFailsWith<TaskException> { gh.create(new("Unlabelled"), tid("A4")) }
        assertTrue("push access" in bare.message!!, bare.message)
    }

    @Test fun listReadsRtokIssuesAndSkipsPullRequestsAndStrangers() {
        val fake = FakeHttp()
        val pr = JsonObject(issue(3, "A3", "open") + ("pull_request" to buildJsonObject { put("url", "x") }))
        val stranger = issue(4, "A4", "open", labels = listOf("rtok"))
        fake.byLabel("rtok", "open", arr(issue(2, "A2", "open", extra = listOf(IN_PROGRESS_LABEL)), pr, stranger, issue(1, "A1", "open")))
        fake.byLabel("rtok", "all", arr(issue(1, "A1", "open"), issue(7, "A1.1", "closed", "not_planned"), issue(8, "B9", "closed"), stranger))
        val gh = github(fake)
        val plan = gh.list(Filter())
        assertEquals(listOf("A1", "A2"), plan.map { it.id.toString() })
        assertEquals(Status.InProgress, plan[1].status)
        assertEquals(1, fake.calls.count { "state=open" in it.query })
        assertEquals(tid("A1.1"), gh.list(Filter(statuses = listOf(Status.Closed)))[0].id)
        assertEquals(tid("A1.1"), gh.maxId("a"))
        assertEquals(tid("B9"), gh.maxId("B"))
        assertNull(gh.maxId("Q"))
        assertEquals(listOf(Stray("A4. Task 4", "https://github.com/me/app/issues/4", tid("A4"))), gh.unlabelled())
    }

    @Test fun statusMovesTheLabelAndClosesDoneAndWontDo() {
        val fake = FakeHttp()
        val gh = github(fake)
        fake.byLabel("rtok:A1", "all", arr(issue(1, "A1", "open", extra = listOf("bug"))))
        fake.byLabel("rtok", "open", "[]")
        fake.byLabel("rtok:A2", "all", arr(issue(2, "A2", "closed", "completed", listOf(IN_PROGRESS_LABEL))))
        fake.on("PATCH", "$ISSUES/1") { c ->
            if ((c.json() as JsonObject)["state"] == JsonPrimitive("closed")) {
                ok(issue(1, "A1", "closed", "completed", listOf("bug")).toString())
            } else {
                ok(issue(1, "A1", "open", null, listOf("bug", IN_PROGRESS_LABEL)).toString())
            }
        }
        fake.on("PATCH", "$ISSUES/2") { c ->
            if ((c.json() as JsonObject)["state"] == JsonPrimitive("closed")) {
                ok(issue(2, "A2", "closed", "not_planned").toString())
            } else {
                ok(issue(2, "A2", "open", "reopened").toString())
            }
        }
        assertEquals(Status.InProgress, setStatus(gh, tid("A1"), Status.InProgress, false).status)
        assertEquals(Status.Done, setStatus(gh, tid("A1"), Status.Done, false).status)
        assertEquals(Status.Open, setStatus(gh, tid("A2"), Status.Open, false).status)
        assertEquals(Status.Closed, setStatus(gh, tid("A2"), Status.Closed, false).status)
        val one = fake.bodies("PATCH", "$ISSUES/1")
        assertEquals(json("""{"labels":["rtok","rtok:A1","bug","$IN_PROGRESS_LABEL"],"state":"open"}"""), one[0])
        assertTrue(includes(one[1], """{"state":"closed","state_reason":"completed"}"""))
        val two = fake.bodies("PATCH", "$ISSUES/2")
        assertEquals(json("""{"labels":["rtok","rtok:A2"],"state":"open","state_reason":"reopened"}"""), two[0])
        assertTrue(includes(two[1], """{"state":"closed","state_reason":"not_planned"}"""))
        fake.byLabel("rtok:A9", "all", "[]")
        assertFailsWith<TaskException> { gh.writeStatus(tid("A9"), Status.Done) }
    }

    @Test fun anIdAnotherMachineTookIsSkippedOnCreate() {
        val fake = FakeHttp()
        fake.byLabel("rtok", "all", arr(issue(1, "A1", "open")))
        // Another machine created A2 after this one's seed listed the repository.
        fake.byLabel("rtok:A2", "all", arr(issue(2, "A2", "open")))
        fake.byLabel("rtok:A3", "all", "[]")
        fake.on("POST", ISSUES, bodyHas = "\"rtok:A3\"") { status(201, issue(3, "A3", "open").toString()) }
        val project = TaskProject("github.com/me/app", tmpDir("gh-taken"), "A", github(fake))
        installDb()
        Store.openInMemory().use { store -> assertEquals(tid("A3"), project.create(store, new("Mine")).id) }
    }

    @Test fun theRepoComesFromConfigOrAGithubOrigin() {
        assertEquals("me/app", githubRepo("", "github.com/me/app"))
        assertEquals("org/x.rs", githubRepo("org/x.rs", "gitlab.com/a/b"))
        for ((cfg, key) in listOf("" to "gitlab.com/a/b", "../etc" to "", "me/app?x=1" to "", "me/app/extra" to "")) {
            assertFailsWith<TaskException>("$cfg $key") { githubRepo(cfg, key) }
        }
    }

    @Test fun saveMergesClaimLabelsAndKeepsTheUsers() {
        val fake = FakeHttp()
        val gh = github(fake)
        fake.byLabel("rtok:A1", "all", arr(issue(1, "A1", "open", extra = listOf("bug", "rtok:p:0"))))
        val after = listOf("bug", "rtok:owner:agent-1", "rtok:needs:A2", "rtok:p:0", IN_PROGRESS_LABEL)
        fake.on("PATCH", "$ISSUES/1") { ok(issue(1, "A1", "open", extra = after).toString()) }
        val task = gh.get(tid("A1"))!!
        assertEquals(0, task.priority)
        val saved = gh.save(task.copy(assignee = "agent-1", blockedBy = listOf(tid("A2")), status = Status.InProgress))
        assertEquals(
            json("""{"labels":["rtok","rtok:A1","bug","rtok:owner:agent-1","rtok:needs:A2","rtok:p:0","$IN_PROGRESS_LABEL"],"state":"open"}"""),
            fake.bodies("PATCH", "$ISSUES/1").single(),
        )
        assertEquals("agent-1", saved.assignee)
        assertEquals(listOf(tid("A2")), saved.blockedBy)
        assertEquals(0, saved.priority)
        assertEquals(Status.InProgress, saved.status)
    }

    @Test fun saveStoresConflictAndParallelMarkersAndReadsThemBack() {
        val fake = FakeHttp()
        val gh = github(fake)
        fake.byLabel("rtok:A1", "all", arr(issue(1, "A1", "open", extra = listOf("bug", "rtok:conflicts:A9"))))
        fake.on("PATCH", "$ISSUES/1") { ok(issue(1, "A1", "open", extra = listOf("bug", "rtok:conflicts:A2", "rtok:conflicts:A3", "rtok:parallel")).toString()) }
        val task = gh.get(tid("A1"))!!
        assertEquals(listOf(tid("A9")), task.conflictsWith)
        assertFalse(task.parallel)
        val saved = gh.save(task.copy(conflictsWith = listOf(tid("A2"), tid("A3")), parallel = true))
        assertEquals(
            json("""{"labels":["rtok","rtok:A1","bug","rtok:conflicts:A2","rtok:conflicts:A3","rtok:parallel"],"state":"open"}"""),
            fake.bodies("PATCH", "$ISSUES/1").single(),
        )
        assertEquals(listOf(tid("A2"), tid("A3")), saved.conflictsWith)
        assertTrue(saved.parallel)
        assertTrue(gh.storesMarkers())
    }

    @Test fun taskConflictWritesBothIssuesAndTaskParallelSetsTheLabel() {
        val fake = FakeHttp()
        for ((n, task) in listOf(1 to "A1", 2 to "A2")) {
            fake.byLabel("rtok:$task", "all", arr(issue(n, task, "open", extra = listOf("bug"))))
            fake.on("PATCH", "$ISSUES/$n") { c ->
                val names = ((c.json() as JsonObject)["labels"] as JsonArray).map { (it as JsonPrimitive).content }
                ok(issue(n, task, "open", labels = names).toString())
            }
        }
        val project = TaskProject("github.com/me/app", tmpDir("gh-conflict"), "A", github(fake))
        val task = project.conflict(tid("A1"), tid("A2"), false)
        assertEquals(json("""{"labels":["rtok","rtok:A1","bug","rtok:conflicts:A2"],"state":"open"}"""), fake.bodies("PATCH", "$ISSUES/1").single())
        assertEquals(json("""{"labels":["rtok","rtok:A2","bug","rtok:conflicts:A1"],"state":"open"}"""), fake.bodies("PATCH", "$ISSUES/2").single())
        assertEquals(listOf(tid("A2")), task.conflictsWith)
        fake.byLabel("rtok:A1", "all", arr(issue(1, "A1", "open", extra = listOf("bug"))))
        assertTrue(project.setParallel(tid("A1"), true).parallel)
        assertEquals(json("""{"labels":["rtok","rtok:A1","bug","rtok:parallel"],"state":"open"}"""), fake.bodies("PATCH", "$ISSUES/1").last())
    }
}
