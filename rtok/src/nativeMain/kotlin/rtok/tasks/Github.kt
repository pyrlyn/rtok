@file:Suppress("MagicNumber", "TooManyFunctions")

// Port of src/tasks/github.rs: the `github` adapter, one issue per task through the REST API. The issue carries the
// `rtok` label and `rtok:R12`, its title starts with the id, a subtask is a sub-issue of its parent's issue,
// `rtok:in-progress` marks work under way, and done or closed close the issue as `completed` or `not_planned`.
// With `[tasks.github] project` set, the issue also joins that Projects v2 board (GithubProject.kt).

package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import rtok.http.HttpClient
import rtok.store.TaskId
import kotlin.time.Duration

/** The public GitHub REST API. */
const val GITHUB_API = "https://api.github.com"

/** The label `in-progress` sets; open without it is `open`. */
const val IN_PROGRESS_LABEL = "rtok:in-progress"

/** GitHub rejects a label name longer than this. `rtok:owner:` plus a UUID is 47. */
private const val LABEL_MAX = 50

internal val REMOTE_JSON = Json { ignoreUnknownKeys = true }

@Serializable
internal data class GithubLabel(
    val name: String,
)

@Serializable
internal data class GithubIssue(
    /** The database id the sub-issues API takes, not the number. */
    val id: Long,
    val number: Long,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val title: String,
    val body: String? = null,
    val state: String,
    @SerialName("state_reason") val stateReason: String? = null,
    val labels: List<GithubLabel> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Set on pull requests, which share the issues list and numbering. */
    @SerialName("pull_request") val pullRequest: JsonElement? = null,
) {
    val names get() = labels.map { it.name }

    fun taskId(): TaskId? = labels.firstNotNullOfOrNull { labelId(it.name) }

    fun toTask(): Task? {
        val id = taskId() ?: return null
        val status =
            when {
                state == "closed" && (stateReason == "not_planned" || stateReason == "duplicate") -> Status.Closed
                state == "closed" -> Status.Done
                IN_PROGRESS_LABEL in names -> Status.InProgress
                else -> Status.Open
            }
        val meta = Meta.read(names)
        return Task(
            id = id,
            title = taskTitle(id, title),
            description = body.orEmpty().trim(),
            status = status,
            parent = id.parent(),
            createdAt = secs(createdAt),
            updatedAt = secs(updatedAt),
            external = ExternalRef("github", number, htmlUrl, nodeId),
            priority = meta.priority,
            assignee = meta.assignee,
            blockedBy = meta.blockedBy,
            conflictsWith = meta.conflictsWith,
            parallel = meta.parallel,
        )
    }
}

/** `[tasks.github] repo`, else the `owner/name` of a `github.com` origin ([key] is the project key). Checked. */
fun githubRepo(
    configured: String,
    key: String,
): String {
    val repo =
        configured.ifEmpty {
            key.removePrefix("github.com/").takeIf { it.length < key.length }
                ?: bail("github tasks: origin $key is not on github.com; set [tasks.github] repo")
        }

    fun ok(s: String) = s.isNotEmpty() && s != ".." && s.all { it.isLetterOrDigit() && it.code < 0x80 || it in "-_." }
    val at = repo.indexOf('/')
    if (at < 0 || !ok(repo.substring(0, at)) || !ok(repo.substring(at + 1))) bail("github tasks: repo ${rtok.plugin.sdk.debugStr(repo)} is not owner/name")
    return repo
}

/** The `state_reason` a write sends: closing says why, reopening a closed issue says so. */
internal fun closeReason(
    status: Status,
    wasClosed: Boolean,
): String? =
    when {
        status == Status.Done -> "completed"
        status == Status.Closed -> "not_planned"
        wasClosed -> "reopened"
        else -> null
    }

/** [base] is [GITHUB_API] (a fake in tests); [gap] paces writes ([WRITE_GAP]). [project] 0 keeps to issues only. */
class GithubAdapter(
    base: String,
    private val repo: String,
    token: String,
    gap: Duration,
    client: HttpClient,
    project: Long = 0,
) : TaskAdapter,
    AutoCloseable {
    private val http =
        RemoteHttp(
            "github",
            base,
            client,
            listOf("authorization" to "Bearer $token", "accept" to "application/vnd.github+json", "x-github-api-version" to "2022-11-28"),
            gap,
        )
    private val project = if (project != 0L) GithubProject(repo, project) else null

    override val name = "github"

    override fun close() = http.close()

    private fun syncProject(
        id: TaskId,
        issue: GithubIssue,
        status: Status,
    ) {
        project?.sync(http, id, issue.nodeId, status)
    }

    private fun decode(e: JsonElement): GithubIssue = runCatching { REMOTE_JSON.decodeFromJsonElement<GithubIssue>(e) }.getOrElse { bail("github: unexpected issue JSON: ${it.message}") }

    /** rtok's issues with [label], pull requests left out; [state] is `open` or `all`. */
    private fun issues(
        label: String,
        state: String,
    ): List<GithubIssue> =
        http
            .getAll("/repos/$repo/issues", listOf("labels" to label, "state" to state, "per_page" to "100"))
            .map(::decode)
            .filter { it.pullRequest == null || it.pullRequest is kotlinx.serialization.json.JsonNull }

    /** The issue labelled [id], open or closed: a label lookup, not the search API (30 a minute, lags writes). */
    private fun find(id: TaskId) = issues(idLabel(id), "all").firstOrNull { it.taskId() == id }

    private fun need(id: TaskId) = find(id) ?: bail("github tasks: no task $id in $repo")

    private fun update(
        id: TaskId,
        issue: GithubIssue,
        labels: List<String>,
        status: Status,
        text: Task?,
    ): Task {
        val body =
            buildJsonObject {
                put("labels", JsonArray(labels.map(::JsonPrimitive)))
                put("state", if (status.isActive) "open" else "closed")
                if (text != null) {
                    put("title", issueTitle(text.id, text.title))
                    put("body", text.description)
                }
                closeReason(status, issue.state == "closed")?.let { put("state_reason", it) }
            }
        val updated = decode(http.send("PATCH", "/repos/$repo/issues/${issue.number}", body))
        syncProject(id, updated, status)
        return updated.toTask() ?: bail("github tasks: $id lost its label")
    }

    /** The `save` write. [text] also rewrites title and body: the exporter owns them, a claim must not clobber edits. */
    internal fun patch(
        task: Task,
        text: Boolean,
    ): Task {
        val issue = need(task.id)
        Meta.managedFor(task).firstOrNull { it.length > LABEL_MAX }?.let { bail("github tasks: label \"$it\" is longer than $LABEL_MAX characters") }
        val labels = Meta.mergeLabels(issue.names, task).filter { it != IN_PROGRESS_LABEL } + listOfNotNull(IN_PROGRESS_LABEL.takeIf { task.status == Status.InProgress })
        return update(task.id, issue, labels, task.status, task.takeIf { text })
    }

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task {
        if (task.title.isBlank()) bail("github tasks: a task needs a title")
        // The counter is per machine: another machine may already have issued this id.
        if (find(id) != null) throw Taken(id)
        val parent = id.parent()?.let { p -> find(p) ?: bail("github tasks: no parent task $p in $repo") }
        val label = idLabel(id)
        val body =
            buildJsonObject {
                put("title", issueTitle(id, task.title))
                put("body", task.description)
                put("labels", JsonArray(listOf(JsonPrimitive(LABEL), JsonPrimitive(label))))
            }
        val issue = decode(http.send("POST", "/repos/$repo/issues", body))
        // GitHub drops labels silently for users without push access, and an unlabelled issue is invisible later.
        if (issue.taskId() != id) {
            bail("github tasks: ${issue.htmlUrl} was created without the $label label (labels need push access to $repo); label or close it by hand")
        }
        if (parent != null) {
            // The id already names the parent for rtok; a failed link must not report the stored task as not created.
            try {
                http.send("POST", "/repos/$repo/issues/${parent.number}/sub_issues", buildJsonObject { put("sub_issue_id", issue.id) })
            } catch (e: TaskException) {
                taskWarningSink("github tasks: $id is not linked under ${parent.htmlUrl}: ${e.message}")
            }
        }
        syncProject(id, issue, Status.Open)
        return issue.toTask() ?: bail("github tasks: the new issue has no task id")
    }

    override fun list(filter: Filter): List<Task> = filter.select(issues(LABEL, if (filter.wantsFinished) "all" else "open").mapNotNull { it.toTask() })

    override fun get(id: TaskId): Task? = find(id)?.toTask()

    override fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task {
        val issue = need(id)
        val labels = issue.names.filter { it != IN_PROGRESS_LABEL } + listOfNotNull(IN_PROGRESS_LABEL.takeIf { status == Status.InProgress })
        return update(id, issue, labels, status, null)
    }

    /** Assignee, blockers and priority. Not compare-and-set: the last write wins; `claim` refuses a live holder first. */
    override fun save(task: Task) = patch(task, false)

    override fun storesMarkers() = true

    override fun maxId(prefix: String) = maxWithPrefix(issues(LABEL, "all").mapNotNull { it.taskId() }, prefix)

    override fun unlabelled() = issues(LABEL, "all").filter { it.taskId() == null }.map { Stray(it.title, it.htmlUrl, titleId(it.title)) }
}

/**
 * `data` of a GraphQL answer; the server answers 200 with `errors` for a missing scope or an unknown project.
 * [path] is the endpoint under the client's base; [what] starts the message an `errors` answer bails with.
 */
internal fun graphql(
    http: RemoteHttp,
    query: String,
    variables: JsonObject,
    path: String = "/graphql",
    what: String = "GraphQL",
): JsonObject {
    val res =
        http.send(
            "POST",
            path,
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            },
        ) as? JsonObject ?: bail("$what: unexpected answer")
    val errors = res["errors"] as? JsonArray
    if (!errors.isNullOrEmpty()) {
        bail("$what: " + errors.mapNotNull { ((it as? JsonObject)?.get("message") as? JsonPrimitive)?.content }.joinToString("; "))
    }
    return res["data"] as? JsonObject ?: JsonObject(emptyMap())
}
