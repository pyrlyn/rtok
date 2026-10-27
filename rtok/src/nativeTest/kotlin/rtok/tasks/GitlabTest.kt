@file:Suppress("MagicNumber")

package rtok.tasks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Tasks
import rtok.config.TasksGitlab
import rtok.store.Store
import rtok.store.TaskId
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val GL_ISSUES = "/projects/me%2Fapp/issues"

private fun glIssue(
    iid: Int,
    task: String,
    state: String,
    extra: List<String> = emptyList(),
    labels: List<String>? = null,
) = buildJsonObject {
    put("id", iid * 100)
    put("iid", iid)
    put("project_id", 42)
    put("web_url", "https://gitlab.com/me/app/-/issues/$iid")
    put("title", "$task. Task $iid")
    put("description", "Why.\n")
    put("state", state)
    put("labels", buildJsonArray { (labels ?: (listOf("rtok", "rtok:$task") + extra)).forEach { add(JsonPrimitive(it)) } })
    put("issue_type", "issue")
    put("created_at", "2026-10-07T10:00:00.000Z")
    put("updated_at", "2026-10-07T11:00:00.000Z")
}

private fun FakeHttp.glLabel(
    label: String,
    state: String,
    body: String,
) = on("GET", GL_ISSUES, "labels=${queryString(listOf("l" to label)).removePrefix("?l=")}&state=$state&") { c ->
    if (c.headers.contains("authorization" to "Bearer test-token")) ok(body) else status(401)
}

private fun gitlab(fake: FakeHttp) = GitlabAdapter("http://mock.test", "me%2Fapp", "test-token", Duration.ZERO, fake)

private fun new(
    title: String,
    parent: TaskId? = null,
    description: String = "",
) = NewTask(title, description, parent)

class GitlabTest {
    @Test fun createLabelsTheIssueAndLinksItToItsParent() {
        val fake = FakeHttp()
        fake.glLabel("rtok:A2.1", "all", "[]")
        fake.glLabel("rtok:A2", "all", arr(glIssue(5, "A2", "opened")))
        fake.on("POST", GL_ISSUES) { status(201, glIssue(9, "A2.1", "opened").toString()) }
        fake.on("POST", "$GL_ISSUES/5/links") { status(201, """{"link_type":"relates_to"}""") }
        val task = gitlab(fake).create(new(" Leaf ", tid("A2"), "Done means tested."), tid("A2.1"))
        assertEquals(json("""{"title":"A2.1. Leaf","description":"Done means tested.","labels":"rtok,rtok:A2.1"}"""), fake.bodies("POST", GL_ISSUES).single())
        assertEquals(json("""{"target_project_id":42,"target_issue_iid":9,"link_type":"relates_to"}"""), fake.bodies("POST", "$GL_ISSUES/5/links").single())
        assertEquals("Task 9", task.title, "read back from the issue title")
        assertEquals("Why.", task.description)
        assertEquals(tid("A2"), task.parent)
        assertEquals(Status.Open, task.status)
        assertEquals(1_791_367_200, task.createdAt)
        val x = task.external!!
        assertEquals("gitlab" to 9L, x.adapter to x.number)
        assertEquals("https://gitlab.com/me/app/-/issues/9", x.url)
    }

    @Test fun aFailedParentLinkStillReturnsTheCreatedTask() {
        val fake = FakeHttp()
        fake.glLabel("rtok:A1.1", "all", "[]")
        fake.glLabel("rtok:A1", "all", arr(glIssue(1, "A1", "opened")))
        fake.on("POST", GL_ISSUES) { status(201, glIssue(2, "A1.1", "opened").toString()) }
        fake.on("POST", "$GL_ISSUES/1/links") { status(403, """{"message":"403 Forbidden"}""") }
        assertEquals(2L, gitlab(fake).create(new("Leaf", tid("A1")), tid("A1.1")).external!!.number)
    }

    @Test fun createRefusesATakenIdAMissingParentAndADroppedLabel() {
        val fake = FakeHttp()
        val gl = gitlab(fake)
        fake.glLabel("rtok:A1", "all", arr(glIssue(1, "A1", "closed", listOf(GITLAB_DONE))))
        assertFailsWith<Taken> { gl.create(new("Again"), tid("A1")) }
        fake.glLabel("rtok:A3.1", "all", "[]")
        fake.glLabel("rtok:A3", "all", "[]")
        val orphan = assertFailsWith<TaskException> { gl.create(new("Orphan"), tid("A3.1")) }
        assertTrue("no parent task A3 in me/app" in orphan.message!!, orphan.message)
        assertFailsWith<TaskException>("no title") { gl.create(new("  "), tid("A5")) }
        fake.glLabel("rtok:A4", "all", "[]")
        fake.on("POST", GL_ISSUES) { status(201, glIssue(12, "A4", "opened", labels = emptyList()).toString()) }
        val bare = assertFailsWith<TaskException> { gl.create(new("Unlabelled"), tid("A4")) }
        assertTrue("Guest role" in bare.message!!, bare.message)
    }

    @Test fun listReadsRtokIssuesAndTellsDoneFromWontDo() {
        val fake = FakeHttp()
        val stranger = glIssue(4, "A4", "opened", labels = listOf("rtok"))
        fake.glLabel("rtok", "opened", arr(glIssue(2, "A2", "opened", listOf(GITLAB_IN_PROGRESS)), stranger, glIssue(1, "A1", "opened", listOf("bug"))))
        fake.glLabel(
            "rtok",
            "all",
            arr(glIssue(1, "A1", "opened"), glIssue(7, "A1.1", "closed", listOf(GITLAB_WONT_DO)), glIssue(8, "B9", "closed"), glIssue(9, "B10", "closed", listOf(GITLAB_DONE))),
        )
        val gl = gitlab(fake)
        val plan = gl.list(Filter())
        assertEquals(listOf("A1", "A2"), plan.map { it.id.toString() })
        assertEquals(Status.InProgress, plan[1].status)
        assertEquals(1, fake.calls.count { "state=opened" in it.query })
        assertEquals(listOf(tid("A1.1")), gl.list(Filter(statuses = listOf(Status.Closed))).map { it.id })
        assertEquals(listOf("B9", "B10"), gl.list(Filter(statuses = listOf(Status.Done))).map { it.id.toString() }, "closed without a status label is done")
        assertEquals(tid("A1.1"), gl.maxId("a"))
        assertEquals(tid("B10"), gl.maxId("B"))
        assertNull(gl.maxId("Q"))
    }

    @Test fun statusSwapsTheLabelsAndClosesOrReopens() {
        val fake = FakeHttp()
        val gl = gitlab(fake)
        fake.glLabel("rtok:A1", "all", arr(glIssue(1, "A1", "opened", listOf("bug"))))
        fake.glLabel("rtok", "opened", "[]")
        fake.glLabel("rtok:A2", "all", arr(glIssue(2, "A2", "closed", listOf(GITLAB_DONE))))
        fake.on("PUT", "$GL_ISSUES/1") { c ->
            val closing = (c.json() as JsonObject)["state_event"] == JsonPrimitive("close")
            ok(if (closing) glIssue(1, "A1", "closed", listOf("bug", GITLAB_DONE)).toString() else glIssue(1, "A1", "opened", listOf("bug", GITLAB_IN_PROGRESS)).toString())
        }
        fake.on("PUT", "$GL_ISSUES/2") { c ->
            val wont = (c.json() as JsonObject)["add_labels"] == JsonPrimitive(GITLAB_WONT_DO)
            ok(if (wont) glIssue(2, "A2", "closed", listOf(GITLAB_WONT_DO)).toString() else glIssue(2, "A2", "opened").toString())
        }
        assertEquals(Status.InProgress, setStatus(gl, tid("A1"), Status.InProgress, false).status)
        assertEquals(Status.Done, setStatus(gl, tid("A1"), Status.Done, false).status)
        assertEquals(Status.Open, setStatus(gl, tid("A2"), Status.Open, false).status)
        // A2 still reads as closed here (the fake does not change), so won't-do only moves the label.
        assertEquals(Status.Closed, setStatus(gl, tid("A2"), Status.Closed, false).status)
        assertEquals(
            listOf(
                json("""{"add_labels":"$GITLAB_IN_PROGRESS","remove_labels":"status::done,status::wont-do"}"""),
                json("""{"add_labels":"$GITLAB_DONE","remove_labels":"status::in-progress,status::wont-do","state_event":"close"}"""),
            ),
            fake.bodies("PUT", "$GL_ISSUES/1"),
        )
        assertEquals(
            listOf(
                json("""{"remove_labels":"status::in-progress,status::done,status::wont-do","state_event":"reopen"}"""),
                json("""{"add_labels":"$GITLAB_WONT_DO","remove_labels":"status::in-progress,status::done"}"""),
            ),
            fake.bodies("PUT", "$GL_ISSUES/2"),
        )
        fake.glLabel("rtok:A9", "all", "[]")
        val err = assertFailsWith<TaskException> { gl.writeStatus(tid("A9"), Status.Done) }
        assertTrue("no task A9 in me/app" in err.message!!, err.message)
    }

    @Test fun anIdAnotherMachineTookIsSkippedOnCreate() {
        val fake = FakeHttp()
        fake.glLabel("rtok", "all", arr(glIssue(1, "A1", "opened")))
        fake.glLabel("rtok:A2", "all", arr(glIssue(2, "A2", "opened")))
        fake.glLabel("rtok:A3", "all", "[]")
        fake.on("POST", GL_ISSUES, bodyHas = "\"labels\":\"rtok,rtok:A3\"") { status(201, glIssue(3, "A3", "opened").toString()) }
        val project = TaskProject("gitlab.com/me/app", tmpDir("gl-taken"), "A", gitlab(fake))
        installDb()
        Store.openInMemory().use { store -> assertEquals(tid("A3"), project.create(store, new("Mine")).id) }
    }

    @Test fun theInstanceMustBeHttpsAndTheProjectComesFromConfigOrOrigin() {
        val gl = gitlabInstance("https://gitlab.com")
        assertEquals("https://gitlab.com/api/v4", gitlabApiBase(gl))
        val sub = gitlabInstance(" https://Git.Example.com/gitlab/ ")
        assertEquals("https://git.example.com/gitlab/api/v4", gitlabApiBase(sub))
        for (bad in listOf("http://gitlab.com", "gitlab.com", "https://gitlab.com/?x=1", "https://me@gitlab.com")) {
            assertFailsWith<TaskException>(bad) { gitlabInstance(bad) }
        }
        assertEquals("me%2Fapp", gitlabProject("", gl, "gitlab.com/me/app"))
        assertEquals("grp%2Fsub%2Fapp", gitlabProject("", gl, "gitlab.com/grp/sub/app"))
        assertEquals("org%2Fx.rs", gitlabProject("/org/x.rs/", gl, "github.com/a/b"))
        assertEquals("1234", gitlabProject("1234", gl, ""))
        val err = assertFailsWith<TaskException> { gitlabProject("", gl, "github.com/me/app") }
        assertTrue("set [tasks.gitlab] project" in err.message!!, err.message)
        // An https remote repeats the instance's path; ssh ones and ports do not matter.
        assertEquals("me%2Fapp", gitlabProject("", sub, "git.example.com/gitlab/me/app"))
        assertEquals("me%2Fapp", gitlabProject("", sub, "git.example.com:2222/me/app"))
        for (bad in listOf("app", "../etc/x", "me/app?x=1", "me//app", "me/app#x", "me%2Fapp")) {
            assertFailsWith<TaskException>(bad) { gitlabProject(bad, gl, "") }
        }
    }

    @Test fun saveAddsTheOwnerAndKeepsAUserLabel() {
        val fake = FakeHttp()
        val gl = gitlab(fake)
        fake.glLabel("rtok:A1", "all", arr(glIssue(1, "A1", "opened", listOf("bug"))))
        fake.on("PUT", "$GL_ISSUES/1") { ok(glIssue(1, "A1", "opened", listOf("bug", GITLAB_IN_PROGRESS, "rtok:owner:agent-1")).toString()) }
        val saved = gl.save(gl.get(tid("A1"))!!.copy(assignee = "agent-1", status = Status.InProgress))
        assertEquals(json("""{"add_labels":"status::in-progress,rtok:owner:agent-1"}"""), fake.bodies("PUT", "$GL_ISSUES/1").single())
        assertEquals("agent-1", saved.assignee)
        assertEquals(Status.InProgress, saved.status)
    }

    @Test fun saveStoresConflictAndParallelMarkersAndReadsThemBack() {
        val fake = FakeHttp()
        val gl = gitlab(fake)
        fake.glLabel("rtok:A1", "all", arr(glIssue(1, "A1", "opened", listOf("bug", "rtok:conflicts:A9", "rtok:parallel"))))
        fake.on("PUT", "$GL_ISSUES/1") { ok(glIssue(1, "A1", "opened", listOf("bug", "rtok:conflicts:A2", "rtok:conflicts:A3")).toString()) }
        val task = gl.get(tid("A1"))!!
        assertEquals(listOf(tid("A9")), task.conflictsWith)
        assertTrue(task.parallel)
        val saved = gl.save(task.copy(conflictsWith = listOf(tid("A2"), tid("A3")), parallel = false))
        assertEquals(
            json("""{"remove_labels":"rtok:conflicts:A9,rtok:parallel","add_labels":"rtok:conflicts:A2,rtok:conflicts:A3"}"""),
            fake.bodies("PUT", "$GL_ISSUES/1").single(),
        )
        assertEquals(listOf(tid("A2"), tid("A3")), saved.conflictsWith)
        assertFalse(saved.parallel)
        assertTrue(gl.storesMarkers())
    }

    @Test fun taskConflictWritesBothIssuesAndTaskParallelSetsTheLabel() {
        val fake = FakeHttp()
        for ((n, task) in listOf(1 to "A1", 2 to "A2")) {
            fake.glLabel("rtok:$task", "all", arr(glIssue(n, task, "opened", listOf("bug"))))
            fake.on("PUT", "$GL_ISSUES/$n") { c ->
                val add = ((c.json() as JsonObject)["add_labels"] as JsonPrimitive).content
                ok(glIssue(n, task, "opened", listOf("bug", add)).toString())
            }
        }
        val project = TaskProject("gitlab.com/me/app", tmpDir("gl-conflict"), "A", gitlab(fake))
        assertEquals(listOf(tid("A2")), project.conflict(tid("A1"), tid("A2"), false).conflictsWith)
        assertEquals(json("""{"add_labels":"rtok:conflicts:A2"}"""), fake.bodies("PUT", "$GL_ISSUES/1").single())
        assertEquals(json("""{"add_labels":"rtok:conflicts:A1"}"""), fake.bodies("PUT", "$GL_ISSUES/2").single())
        assertTrue(project.setParallel(tid("A1"), true).parallel)
        assertEquals(json("""{"add_labels":"rtok:parallel"}"""), fake.bodies("PUT", "$GL_ISSUES/1").last())
    }

    @Test fun gitlabNeedsAnOriginOnItsHostAndAnHttpsUrl() {
        val dir = tmpDir("gl-open")
        createDirAll("$dir/.git")
        writeString("$dir/.git/config", "[remote \"origin\"]\n\turl = git@github.com:me/airtalk.git\n")
        // Both fail before any token lookup or request.
        val err = assertFailsWith<TaskException> { TaskProject.open(Tasks(adapter = "gitlab"), dir) }
        assertTrue("set [tasks.gitlab] project" in err.message!!, err.message)
        val http = assertFailsWith<TaskException> { TaskProject.open(Tasks(adapter = "gitlab", gitlab = TasksGitlab(url = "http://gitlab.example.com")), dir) }
        assertTrue("https://" in http.message!!, http.message)
    }
}
