@file:Suppress("MagicNumber")

package rtok.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Port of export/github.rs's tests. Issue titles read back as "Task <n>", bodies as "Why.".
class GithubExporterTest {
    private fun exporter(fake: FakeHttp) = GithubExporter(github(fake), "me/app")

    private fun local(
        id: String,
        n: Int,
        status: Status,
    ) = localTask(id, status, "Task $n", "Why.")

    private fun listing(
        fake: FakeHttp,
        body: String,
    ) = fake.byLabel("rtok", "all", body)

    @Test fun aNewTaskBecomesAnIssueAndASecondRunSendsNothing() {
        val fake = FakeHttp()
        val tasks = listOf(local("R1", 7, Status.Open))
        listing(fake, "[]")
        fake.byLabel("rtok:R1", "all", "[]")
        fake.on("POST", ISSUES) { status(201, issue(7, "R1", "open").toString()) }
        val first = runExport(exporter(fake), tasks, false)
        assertEquals(listOf("Create R1"), first.summary())
        assertEquals("https://github.com/me/app/issues/7", first.actions[0].url)
        assertEquals(json("""{"title":"R1. Task 7","body":"Why.","labels":["rtok","rtok:R1"]}"""), fake.bodies("POST", ISSUES).single())

        listing(fake, arr(issue(7, "R1", "open")))
        val again = runExport(exporter(fake), tasks, false)
        assertTrue(again.actions.isEmpty(), "$again")
        assertEquals(1, again.unchanged)
        assertEquals(1, fake.hits("POST", ISSUES))
    }

    @Test fun driftIsPatchedAndDoneAndClosedTasksCloseWithTheirReason() {
        val fake = FakeHttp()
        val i1 = issue(1, "R1", "open")
        val i2 = issue(2, "R2", "open")
        val i3 = issue(3, "R3", "open")
        val i4 = issue(4, "R4", "closed", "completed")
        listing(fake, arr(i1, i2, i3, i4))
        listOf("R1" to i1, "R2" to i2, "R3" to i3, "R4" to i4).forEach { (t, i) -> fake.byLabel("rtok:$t", "all", arr(i)) }
        for (n in 1..4) fake.on("PATCH", "$ISSUES/$n") { ok(issue(n, "R$n", "open").toString()) }
        val tasks =
            listOf(
                localTask("R1", Status.Open, "New title", "Why."),
                local("R2", 2, Status.Done),
                local("R3", 3, Status.Closed),
                local("R4", 4, Status.Open),
                local("R9", 9, Status.Done),
            )
        val report = runExport(exporter(fake), tasks, false)
        assertEquals(listOf("Update R1", "Close R2", "Close R3", "Reopen R4"), report.summary())
        assertEquals(1, report.skipped, "R9 is finished and has no issue")
        assertEquals(
            json("""{"title":"R1. New title","body":"Why.","labels":["rtok","rtok:R1"],"state":"open"}"""),
            fake.bodies("PATCH", "$ISSUES/1").single(),
        )
        assertTrue(includes(fake.bodies("PATCH", "$ISSUES/2").single(), """{"state":"closed","state_reason":"completed"}"""))
        assertTrue(includes(fake.bodies("PATCH", "$ISSUES/3").single(), """{"state":"closed","state_reason":"not_planned"}"""))
        assertTrue(includes(fake.bodies("PATCH", "$ISSUES/4").single(), """{"state":"open","state_reason":"reopened"}"""))
    }

    @Test fun dryRunListsTheWritesAndSendsNone() {
        val fake = FakeHttp()
        listing(fake, arr(issue(1, "R1", "open")))
        val report = runExport(exporter(fake), listOf(localTask("R1", Status.Open, "New", "Why."), local("R2", 2, Status.Open)), true)
        assertTrue(report.dryRun)
        assertEquals(listOf("Update R1", "Create R2"), report.summary())
        assertEquals(listOf("title"), report.actions[0].fields)
        assertTrue(fake.calls.all { it.method == "GET" })
    }

    @Test fun anInProgressTaskIsCreatedThenLabelled() {
        val fake = FakeHttp()
        listing(fake, "[]")
        var posted = false
        fake.on("POST", ISSUES) {
            posted = true
            status(201, issue(5, "R1", "open").toString())
        }
        fake.onIf("GET", ISSUES, { "labels=rtok%3AR1&" in it.query }) { ok(if (posted) arr(issue(5, "R1", "open")) else "[]") }
        fake.on("PATCH", "$ISSUES/5") { ok(issue(5, "R1", "open").toString()) }
        val task = local("R1", 5, Status.InProgress).copy(assignee = "a1", blockedBy = listOf(tid("R2")), priority = 0)
        val report = runExport(exporter(fake), listOf(task), false)
        assertEquals(listOf("Create R1"), report.summary())
        val labels = """{"labels":["rtok","rtok:R1","rtok:owner:a1","rtok:needs:R2","rtok:p:0","rtok:in-progress"]}"""
        assertTrue(includes(fake.bodies("PATCH", "$ISSUES/5").single(), labels))
    }

    @Test fun aFailedWriteNamesTheBackendTheTaskAndHowFarItGot() {
        val fake = FakeHttp()
        listing(fake, "[]")
        fake.byLabel("rtok:R1", "all", "[]")
        fake.on("POST", ISSUES) { status(422, """{"message":"Validation Failed"}""") }
        val e = assertFailsWith<TaskException> { runExport(exporter(fake), listOf(local("R1", 1, Status.Open)), false) }
        val text = e.message!!
        assertTrue("github export: create R1 failed after 0 of 1 writes" in text && ISSUES in text && "Validation Failed" in text, text)
        assertTrue("test-token" !in text, "no token in errors")
    }
}
