@file:Suppress("MagicNumber")

// Port of src/tasks/export/linear.rs: the Linear exporter. One GraphQL endpoint, a personal API key, and the same label
// vocabulary as the GitHub adapter (`rtok`, `rtok:<id>`, owner, needs, priority, markers), so a task reads back from a
// Linear issue the way it does from a GitHub one. The status is Linear's workflow state, picked by its type.

package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import rtok.http.HttpClient
import rtok.store.TaskId
import kotlin.time.Duration

const val LINEAR_API = "https://api.linear.app/graphql"

/** Issues per page: Linear caps a query at 10,000 complexity points; 50 issues with 50 labels each stay near 3,000. */
private const val PAGE = 50

private const val TEAM =
    "query Team(\$key: String!) { teams(filter: {key: {eq: \$key}}) { nodes { id states(first: 100) { nodes { id type position } } } } }"
private const val LABELS =
    "query Labels(\$team: ID!, \$after: String) { issueLabels(first: 100, after: \$after, filter: {or: [{team: {id: {eq: \$team}}}, " +
        "{team: {null: true}}]}) { nodes { id name } pageInfo { hasNextPage endCursor } } }"

// Archived issues stay in the listing, or a re-run would create their task a second time.
private const val ISSUES_QUERY =
    "query Issues(\$key: String!, \$label: String!, \$after: String, \$first: Int!) { issues(first: \$first, after: \$after, " +
        "includeArchived: true, filter: {team: {key: {eq: \$key}}, labels: {name: {eq: \$label}}}) { nodes { id number url title " +
        "description state { type } labels(first: 50) { nodes { name } } } pageInfo { hasNextPage endCursor } } }"
private const val LABEL_CREATE =
    "mutation LabelCreate(\$input: IssueLabelCreateInput!) { issueLabelCreate(input: \$input) { success issueLabel { id name } } }"
private const val ISSUE_CREATE =
    "mutation IssueCreate(\$input: IssueCreateInput!) { issueCreate(input: \$input) { success issue { id number url } } }"
private const val ISSUE_UPDATE =
    "mutation IssueUpdate(\$id: String!, \$input: IssueUpdateInput!) { issueUpdate(id: \$id, input: \$input) { success issue { id number url } } }"

private val LJSON = Json { ignoreUnknownKeys = true }

@Serializable private data class LPageInfo(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable private data class LState(
    val id: String,
    val type: String,
    val position: Double,
)

@Serializable private data class LStates(
    val nodes: List<LState>,
)

@Serializable private data class LTeam(
    val id: String,
    val states: LStates,
)

@Serializable private data class LTeams(
    val nodes: List<LTeam>,
)

@Serializable private data class LTeamData(
    val teams: LTeams,
)

@Serializable private data class LLabel(
    val id: String,
    val name: String,
)

@Serializable private data class LLabelPage(
    val nodes: List<LLabel>,
    val pageInfo: LPageInfo = LPageInfo(),
)

@Serializable private data class LLabelsData(
    val issueLabels: LLabelPage,
)

@Serializable private data class LLabelCreated(
    val success: Boolean,
    val issueLabel: LLabel? = null,
)

@Serializable private data class LName(
    val name: String,
)

@Serializable private data class LNames(
    val nodes: List<LName>,
)

@Serializable private data class LStateType(
    val type: String,
)

@Serializable private data class LIssue(
    val id: String,
    val number: Double,
    val url: String,
    val title: String,
    val description: String? = null,
    val state: LStateType,
    val labels: LNames,
)

@Serializable private data class LIssuePage(
    val nodes: List<LIssue>,
    val pageInfo: LPageInfo = LPageInfo(),
)

@Serializable private data class LIssuesData(
    val issues: LIssuePage,
)

@Serializable private data class LWritten0(
    val id: String,
    val url: String,
)

@Serializable private data class LWritten(
    val success: Boolean,
    val issue: LWritten0? = null,
)

/** The task status a Linear workflow state type reads as. */
internal fun linearStatus(stateType: String) =
    when (stateType) {
        "started" -> Status.InProgress
        "completed" -> Status.Done
        "canceled", "duplicate" -> Status.Closed
        else -> Status.Open
    }

/** [url] is [LINEAR_API] (a fake in tests); [apiKey] is a personal key, sent bare; [team] the team's short key. */
class LinearExporter(
    private val url: String,
    team: String,
    apiKey: String,
    gap: Duration,
    client: HttpClient,
) : Exporter {
    // Linear takes a personal key bare; `Bearer` is for OAuth tokens only.
    private val http = RemoteHttp("linear", url, client, listOf("authorization" to apiKey), gap)
    private val key = team

    private class Team(
        val id: String,
        val states: List<LState>,
    )

    /** What the exporter remembers of an issue to write to it later. */
    private class Held(
        val uuid: String,
        val labels: List<String>,
    )

    private var team: Team? = null

    /** Label name to id, for the team's labels and the workspace's; filled on the first write. */
    private var labels: MutableMap<String, String>? = null
    private var issues = mutableMapOf<TaskId, Held>()

    override val name = "linear"
    override val target get() = key

    override fun close() = http.close()

    /** One query or mutation. A GraphQL failure can arrive as HTTP 200, so `errors` is read before `data` is trusted. */
    private inline fun <reified T> gql(
        query: String,
        variables: JsonObject,
    ): T {
        val data = graphql(http, query, variables, "", "linear: POST ${http.base}")
        if (data.isEmpty()) bail("linear: POST ${http.base}: the answer has no data")
        return runCatching { LJSON.decodeFromJsonElement<T>(data) }.getOrElse { bail("linear: POST ${http.base}: unexpected JSON") }
    }

    private fun teamAndState(status: Status): Pair<String, String> {
        val t =
            team ?: run {
                val node =
                    gql<LTeamData>(TEAM, buildJsonObject { put("key", key) }).teams.nodes.firstOrNull()
                        ?: bail("linear: no team with key ${rtok.plugin.sdk.debugStr(key)}")
                Team(node.id, node.states.nodes).also { team = it }
            }
        // Types in order of preference: a team with no `unstarted` state keeps new work in `backlog`.
        val wanted =
            when (status) {
                Status.Open -> listOf("unstarted", "backlog")
                Status.InProgress -> listOf("started")
                Status.Done -> listOf("completed")
                Status.Closed -> listOf("canceled")
            }
        val state =
            wanted.firstNotNullOfOrNull { kind -> t.states.filter { it.type == kind }.minByOrNull { it.position } }
                ?: bail("linear: team $key has no workflow state of type ${wanted.joinToString(" or ")}")
        return t.id to state.id
    }

    private fun cursor(p: LPageInfo): JsonElement? = p.endCursor?.takeIf { p.hasNextPage }?.let(::JsonPrimitive)

    /** The ids of labels [names], creating the ones the team does not have yet (`labelIds` needs them to exist). */
    private fun labelIds(
        names: List<String>,
        teamId: String,
    ): List<String> {
        val known =
            labels ?: mutableMapOf<String, String>().also { map ->
                var after: JsonElement = JsonNull
                while (true) {
                    val page =
                        gql<LLabelsData>(
                            LABELS,
                            buildJsonObject {
                                put("team", teamId)
                                put("after", after)
                            },
                        ).issueLabels
                    page.nodes.forEach { map[it.name] = it.id }
                    after = cursor(page.pageInfo) ?: break
                }
                labels = map
            }
        return names.map { name ->
            known.getOrPut(name) {
                val input =
                    buildJsonObject {
                        put(
                            "input",
                            buildJsonObject {
                                put("name", name)
                                put("teamId", teamId)
                            },
                        )
                    }
                val made = gql<JsonObject>(LABEL_CREATE, input)["issueLabelCreate"]?.let { LJSON.decodeFromJsonElement<LLabelCreated>(it) }
                made?.issueLabel?.takeIf { made.success }?.id ?: bail("linear: POST ${http.base}: label ${rtok.plugin.sdk.debugStr(name)} was not created")
            }
        }
    }

    private fun written(
        query: String,
        op: String,
        variables: JsonObject,
    ): LWritten0 {
        val w = gql<JsonObject>(query, variables)[op]?.let { LJSON.decodeFromJsonElement<LWritten>(it) }
        return w?.issue?.takeIf { w.success } ?: bail("linear: POST ${http.base}: the write was not accepted")
    }

    override fun remote(): List<Task> {
        val tasks = mutableListOf<Task>()
        val held = mutableMapOf<TaskId, Held>()
        var after: JsonElement = JsonNull
        while (true) {
            val vars =
                buildJsonObject {
                    put("key", key)
                    put("label", LABEL)
                    put("after", after)
                    put("first", PAGE)
                }
            val page = gql<LIssuesData>(ISSUES_QUERY, vars).issues
            for (issue in page.nodes) {
                val names = issue.labels.nodes.map { it.name }
                // An issue carrying `rtok` but no `rtok:<id>` is not one of ours to touch.
                val id = names.firstNotNullOfOrNull(::labelId) ?: continue
                val meta = Meta.read(names)
                tasks +=
                    Task
                        .open(id, taskTitle(id, issue.title), issue.description ?: "", 0)
                        .copy(
                            status = linearStatus(issue.state.type),
                            priority = meta.priority,
                            assignee = meta.assignee,
                            blockedBy = meta.blockedBy,
                            conflictsWith = meta.conflictsWith,
                            parallel = meta.parallel,
                            external = ExternalRef("linear", issue.number.toLong(), issue.url, issue.id),
                        )
                held.getOrPut(id) { Held(issue.id, names) }
            }
            after = cursor(page.pageInfo) ?: break
        }
        issues = held
        return tasks
    }

    override fun apply(
        action: ExportAction,
        task: Task,
    ): String {
        val (teamId, state) = teamAndState(task.status)
        val existing =
            if (action.kind == ExportKind.Create) {
                listOf(LABEL, idLabel(task.id))
            } else {
                issues[task.id]?.labels ?: bail("linear: no issue for task ${task.id}")
            }
        // `mergeLabels` keeps a person's own labels and rewrites the ones rtok owns.
        val merged = Meta.mergeLabels(existing, task)
        val ids = labelIds(merged, teamId)
        val base =
            mapOf(
                "title" to JsonPrimitive(issueTitle(task.id, task.title)),
                "description" to JsonPrimitive(task.description),
                "labelIds" to JsonArray(ids.map(::JsonPrimitive)),
            )
        val out =
            if (action.kind == ExportKind.Create) {
                val parentId =
                    task.id.parent()?.let { p ->
                        issues[p]?.uuid ?: bail("linear: the parent $p of ${task.id} has no issue")
                    }
                val input =
                    base + mapOf("teamId" to JsonPrimitive(teamId), "stateId" to JsonPrimitive(state)) +
                        listOfNotNull(parentId?.let { "parentId" to JsonPrimitive(it) })
                written(ISSUE_CREATE, "issueCreate", buildJsonObject { put("input", JsonObject(input)) })
            } else {
                val uuid = issues.getValue(task.id).uuid
                // The state moves only when the status differs: an issue parked in another `unstarted` column stays there.
                val input = if ("status" in action.fields) base + ("stateId" to JsonPrimitive(state)) else base
                written(
                    ISSUE_UPDATE,
                    "issueUpdate",
                    buildJsonObject {
                        put("id", uuid)
                        put("input", JsonObject(input))
                    },
                )
            }
        issues[task.id] = Held(out.id, merged)
        return out.url
    }
}
