@file:Suppress("MagicNumber")

package rtok.tasks

import kotlin.test.Test
import kotlin.test.assertEquals

private fun projectAnswer(options: List<Pair<String, String>>) =
    """{"data":{"repositoryOwner":{"projectV2":{"id":"PVT_1","field":{"id":"PVTSSF_1","options":[""" +
        options.joinToString(",") { (id, name) -> """{"id":"$id","name":"$name"}""" } + "]}}}}}"

private const val ADDED = """{"data":{"addProjectV2ItemById":{"item":{"id":"PVTI_1"}}}}"""
private const val SET_OK = """{"data":{"updateProjectV2ItemFieldValue":{"projectV2Item":{"id":"PVTI_1"}}}}"""

private fun FakeHttp.gql(
    needle: String,
    answer: String,
) = on("POST", "/graphql", bodyHas = needle) { ok(answer) }

private fun FakeHttp.standard() = gql("repositoryOwner", projectAnswer(listOf("o_todo" to "Todo", "o_doing" to "In Progress", "o_done" to "Done")))

private fun FakeHttp.added() = gql("addProjectV2ItemById", ADDED)

private fun FakeHttp.setTo(option: String) = gql("\"option\":\"$option\"", SET_OK)

private fun FakeHttp.gqlHits(needle: String) = hits("POST", "/graphql", needle)

private fun FakeHttp.issueOneExists() {
    byLabel("rtok:A1", "all", arr(issue(1, "A1", "open")))
    on("PATCH", "$ISSUES/1") { ok(issue(1, "A1", "open").toString()) }
}

private fun FakeHttp.createMocks() {
    byLabel("rtok:A1", "all", "[]")
    on("POST", ISSUES) { status(201, issue(1, "A1", "open").toString()) }
}

private val newTask = NewTask("Mine")

class GithubProjectTest {
    @Test fun aNewIssueJoinsTheProjectAsTodo() {
        val fake = FakeHttp()
        fake.createMocks()
        fake.standard()
        fake.gql("\"content\":\"I_kw1\"", ADDED)
        fake.setTo("o_todo")
        github(fake, 4).create(newTask, tid("A1"))
        assertEquals(1, fake.gqlHits("repositoryOwner"))
        assertEquals(1, fake.gqlHits("\"content\":\"I_kw1\""))
        assertEquals(1, fake.gqlHits("\"option\":\"o_todo\""))
    }

    @Test fun statusChangesFollowAndTheIdsAreResolvedOnce() {
        val fake = FakeHttp()
        fake.standard()
        fake.added()
        listOf("o_todo", "o_doing", "o_done").forEach { fake.setTo(it) }
        fake.issueOneExists()
        val gh = github(fake, 4)
        for (s in listOf(Status.InProgress, Status.Done, Status.Closed, Status.Open)) gh.writeStatus(tid("A1"), s)
        assertEquals(1, fake.gqlHits("repositoryOwner"), "cached after the first write")
        assertEquals(4, fake.gqlHits("addProjectV2ItemById"))
        assertEquals(listOf(1, 1, 2), listOf("o_todo", "o_doing", "o_done").map { fake.gqlHits("\"option\":\"$it\"") }, "Closed shares Done")
    }

    @Test fun projectZeroNeverCallsGraphql() {
        val fake = FakeHttp()
        fake.createMocks()
        fake.on("POST", "/graphql") { ok("""{"data":{}}""") }
        github(fake, 0).create(newTask, tid("A1"))
        assertEquals(0, fake.hits("POST", "/graphql"))
    }

    @Test fun projectTroubleWarnsAndLeavesTheIssueWriteAlone() {
        // A missing project, a missing scope, a project without Status and a Status without the option.
        val cases =
            listOf(
                """{"data":{"repositoryOwner":{"projectV2":null}},"errors":[{"message":"Could not resolve to a ProjectV2 with the number 4."}]}""",
                """{"errors":[{"message":"Your token has not been granted the required scopes"}]}""",
                """{"data":{"repositoryOwner":{"projectV2":{"id":"P","field":{}}}}}""",
                projectAnswer(listOf("o_done" to "Done")),
            )
        val warnings = mutableListOf<String>()
        val prev = taskWarningSink
        taskWarningSink = { warnings += it }
        try {
            for (answer in cases) {
                val fake = FakeHttp()
                fake.createMocks()
                fake.gql("repositoryOwner", answer)
                fake.added()
                assertEquals(tid("A1"), github(fake, 4).create(newTask, tid("A1")).id)
                assertEquals(1, fake.gqlHits("repositoryOwner"))
                assertEquals(0, fake.gqlHits("addProjectV2ItemById"), "nothing is added to a project we cannot set")
            }
            val fake = FakeHttp()
            fake.createMocks()
            fake.on("POST", "/graphql") { status(500) }
            github(fake, 4).create(newTask, tid("A1"))
        } finally {
            taskWarningSink = prev
        }
        assertEquals(5, warnings.size, warnings.toString())
    }

    @Test fun aFailedResolveIsRetriedOnTheNextWrite() {
        val fake = FakeHttp()
        fake.on("POST", "/graphql") { status(502) }
        val gh = github(fake, 4)
        fake.issueOneExists()
        gh.writeStatus(tid("A1"), Status.InProgress)
        assertEquals(1, fake.hits("POST", "/graphql"))
        fake.standard()
        fake.added()
        fake.setTo("o_doing")
        gh.writeStatus(tid("A1"), Status.InProgress)
        assertEquals(2, fake.gqlHits("repositoryOwner"))
        assertEquals(1, fake.gqlHits("\"option\":\"o_doing\""))
    }
}
