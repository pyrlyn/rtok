@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.tasks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

// Port of export/linear.rs's tests.
class LinearExporterTest {
    private val gql = "/graphql"

    private fun exporter(fake: FakeHttp) = LinearExporter("http://mock.test/graphql", "ENG", "lin_api_test", Duration.ZERO, fake)

    private fun local(
        id: String,
        title: String,
        status: Status,
    ) = localTask(id, status, title, "Why.")

    private fun data(v: String) = """{"data":$v}"""

    private fun issue(
        n: Int,
        task: String,
        title: String,
        state: String,
        extra: List<String> = emptyList(),
    ): String {
        val labels = (listOf("rtok", "rtok:$task") + extra).joinToString(",") { """{"name":"$it"}""" }
        return """{"id":"uuid-$n","number":$n,"url":"https://linear.app/acme/issue/ENG-$n","title":"$task. $title",""" +
            """"description":"Why.","state":{"type":"$state"},"labels":{"nodes":[$labels]}}"""
    }

    private fun issues(vararg nodes: String) = data("""{"issues":{"nodes":[${nodes.joinToString(",")}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}""")

    private fun FakeHttp.Call.vars(): JsonObject = json().jsonObject["variables"]!!.jsonObject

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.content

    /** Answer the operation named [op] when [check] accepts its variables; only with the bare API key. */
    private fun FakeHttp.op(
        op: String,
        answer: String,
        check: (JsonObject) -> Boolean = { true },
    ) = onIf("POST", gql, { c -> op in (c.json().jsonObject["query"].str() ?: "") && c.headers.contains("authorization" to "lin_api_test") && check(c.vars()) }) {
        ok(answer)
    }

    private fun FakeHttp.sent(op: String) = calls.filter { op in it.body }.map { it.vars() }

    private fun setup(fake: FakeHttp) {
        val states =
            listOf("s-backlog" to "backlog", "s-todo" to "unstarted", "s-todo2" to "unstarted", "s-doing" to "started", "s-done" to "completed", "s-wont" to "canceled")
                .mapIndexed { i, (id, t) -> """{"id":"$id","type":"$t","position":$i.0}""" }
                .joinToString(",")
        fake.op("query Team", data("""{"teams":{"nodes":[{"id":"team-1","states":{"nodes":[$states]}}]}}"""))
        fake.op(
            "query Labels",
            data("""{"issueLabels":{"nodes":[{"id":"l-rtok","name":"rtok"},{"id":"l-bug","name":"bug"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}"""),
        )
    }

    private fun written(
        op: String,
        n: Int,
    ) = data("""{"$op":{"success":true,"issue":{"id":"uuid-$n","number":$n,"url":"https://linear.app/acme/issue/ENG-$n"}}}""")

    private val labelX = data("""{"issueLabelCreate":{"success":true,"issueLabel":{"id":"l-x","name":"x"}}}""")

    @Test fun aNewTaskBecomesAnIssueAndASecondRunSendsNothing() {
        val fake = FakeHttp()
        setup(fake)
        fake.op("query Issues", issues())
        fake.op("mutation LabelCreate", data("""{"issueLabelCreate":{"success":true,"issueLabel":{"id":"l-r1","name":"rtok:R1"}}}"""))
        fake.op("mutation IssueCreate", written("issueCreate", 7))
        val tasks = listOf(local("R1", "Ship", Status.Open))
        val first = runExport(exporter(fake), tasks, false)
        assertEquals(listOf("Create R1"), first.summary())
        assertEquals("https://linear.app/acme/issue/ENG-7", first.actions[0].url)
        assertEquals(json("""{"input":{"name":"rtok:R1","teamId":"team-1"}}"""), fake.sent("mutation LabelCreate").single())
        assertEquals(
            json("""{"input":{"title":"R1. Ship","description":"Why.","labelIds":["l-rtok","l-r1"],"teamId":"team-1","stateId":"s-todo"}}"""),
            fake.sent("mutation IssueCreate").single(),
        )

        fake.op("query Issues", issues(issue(7, "R1", "Ship", "unstarted")))
        val again = runExport(exporter(fake), tasks, false)
        assertTrue(again.actions.isEmpty(), "$again")
        assertEquals(1, again.unchanged)
        assertEquals(1, fake.sent("mutation IssueCreate").size)
    }

    @Test fun driftIsUpdatedAndDoneAndClosedTasksMoveToTheirStateType() {
        val fake = FakeHttp()
        setup(fake)
        fake.op(
            "query Issues",
            issues(
                issue(1, "R1", "Old title", "backlog", listOf("bug")),
                issue(2, "R2", "Two", "started"),
                issue(3, "R3", "Three", "unstarted"),
                issue(4, "R4", "Four", "completed"),
            ),
        )
        fake.op("mutation LabelCreate", labelX)
        fake.op("mutation IssueUpdate", written("issueUpdate", 1))
        val tasks =
            listOf(
                local("R1", "New title", Status.Open),
                local("R2", "Two", Status.Done),
                local("R3", "Three", Status.Closed),
                local("R4", "Four", Status.Open),
                local("R9", "Old history", Status.Done),
            )
        val report = runExport(exporter(fake), tasks, false)
        assertEquals(listOf("Update R1", "Close R2", "Close R3", "Reopen R4"), report.summary())
        assertEquals(1, report.skipped, "R9 is finished and has no issue")
        val byId = fake.sent("mutation IssueUpdate").associateBy { it["id"].str() }
        val retitle = byId.getValue("uuid-1")["input"]!!.jsonObject
        // The retitle leaves the state alone: `backlog` already reads as open.
        assertEquals("R1. New title", retitle["title"].str())
        assertNull(retitle["stateId"])
        assertEquals(json("""["l-rtok","l-x","l-bug"]"""), retitle["labelIds"])
        assertEquals("s-done", byId.getValue("uuid-2")["input"]!!.jsonObject["stateId"].str())
        assertEquals("s-wont", byId.getValue("uuid-3")["input"]!!.jsonObject["stateId"].str())
        assertEquals("s-todo", byId.getValue("uuid-4")["input"]!!.jsonObject["stateId"].str())
    }

    @Test fun aSubtaskHangsUnderItsParentAndAFinishedParentIsCreatedDone() {
        val fake = FakeHttp()
        setup(fake)
        fake.op("query Issues", issues())
        fake.op("mutation LabelCreate", labelX)
        fake.op("mutation IssueCreate", written("issueCreate", 1)) { it["input"]!!.jsonObject["title"].str() == "R1. Big" }
        fake.op("mutation IssueCreate", written("issueCreate", 2)) { it["input"]!!.jsonObject["title"].str() == "R1.1. Small" }
        val doing = local("R1.1", "Small", Status.InProgress)
        val report = runExport(exporter(fake), listOf(doing, local("R1", "Big", Status.Done)), false)
        assertEquals(listOf("Create R1", "Create R1.1"), report.summary())
        val (parent, child) = fake.sent("mutation IssueCreate").map { it["input"]!!.jsonObject }
        assertEquals("s-done", parent["stateId"].str())
        assertEquals("uuid-1" to "s-doing", child["parentId"].str() to child["stateId"].str())
    }

    @Test fun dryRunListsTheWritesAndSendsNone() {
        val fake = FakeHttp()
        fake.op("query Issues", issues(issue(1, "R1", "Old", "unstarted")))
        val report = runExport(exporter(fake), listOf(local("R1", "New", Status.Open), local("R2", "Fresh", Status.Open)), true)
        assertTrue(report.dryRun)
        assertEquals(listOf("Update R1", "Create R2"), report.summary())
        assertEquals(listOf("title"), report.actions[0].fields)
        assertTrue(fake.calls.none { "mutation" in it.body || "query Team" in it.body })
    }

    @Test fun aGraphqlErrorInA200NamesLinearAndTheUrl() {
        val fake = FakeHttp()
        fake.op("query Issues", """{"errors":[{"message":"Argument Validation Error","path":["issues"]}]}""")
        val text = assertFailsWith<TaskException> { runExport(exporter(fake), emptyList(), false) }.message!!
        assertTrue("linear" in text && "/graphql" in text && "Argument Validation Error" in text && "lin_api_test" !in text, text)
    }

    @Test fun aRateLimitArrivesAsA400AndSaysSo() {
        val fake = FakeHttp()
        fake.on("POST", gql) { status(400, """{"errors":[{"message":"Rate limit exceeded","extensions":{"code":"RATELIMITED"}}]}""") }
        val text = assertFailsWith<TaskException> { runExport(exporter(fake), emptyList(), false) }.message!!
        assertTrue("Rate limit exceeded" in text && "linear" in text, text)
    }

    @Test fun aTeamWithoutAStateForTheStatusIsNamed() {
        val fake = FakeHttp()
        fake.op("query Issues", issues())
        fake.op("query Team", data("""{"teams":{"nodes":[{"id":"team-1","states":{"nodes":[{"id":"s-done","type":"completed","position":0.0}]}}]}}"""))
        val text = assertFailsWith<TaskException> { runExport(exporter(fake), listOf(local("R1", "Go", Status.Open)), false) }.message!!
        assertTrue("linear export: create R1 failed after 0 of 1 writes" in text && "no workflow state of type unstarted or backlog" in text, text)
    }
}
