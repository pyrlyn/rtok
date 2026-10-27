@file:Suppress("MagicNumber", "TooManyFunctions", "ReturnCount")

// Port of src/tasks/gitlab.rs: the `gitlab` adapter, one issue per task through the REST API v4. Labels as on GitHub
// (`rtok`, `rtok:R12`); scoped `status::in-progress`, `status::done` and `status::wont-do` carry the status, since a
// GitLab issue has no close reason. A subtask's issue is linked (`relates_to`) to its parent's.

package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import rtok.http.HttpClient
import rtok.plugin.sdk.debugStr
import rtok.store.TaskId
import kotlin.time.Duration

const val GITLAB_IN_PROGRESS = "status::in-progress"
const val GITLAB_DONE = "status::done"
const val GITLAB_WONT_DO = "status::wont-do"

private val STATUS_LABELS = listOf(GITLAB_IN_PROGRESS, GITLAB_DONE, GITLAB_WONT_DO)

@Serializable
internal data class GitlabIssue(
    val iid: Long,
    @SerialName("project_id") val projectId: Long,
    @SerialName("web_url") val webUrl: String,
    val title: String,
    val description: String? = null,
    val state: String,
    val labels: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun taskId(): TaskId? = labels.firstNotNullOfOrNull(::labelId)

    fun has(label: String) = labels.any { it.equals(label, ignoreCase = true) }

    fun toTask(): Task? {
        val id = taskId() ?: return null
        val status =
            when {
                state == "closed" && has(GITLAB_WONT_DO) -> Status.Closed
                state == "closed" -> Status.Done
                has(GITLAB_IN_PROGRESS) -> Status.InProgress
                else -> Status.Open
            }
        val meta = Meta.read(labels)
        return Task(
            id = id,
            title = taskTitle(id, title),
            description = description.orEmpty().trim(),
            status = status,
            parent = id.parent(),
            createdAt = secs(createdAt),
            updatedAt = secs(updatedAt),
            external = ExternalRef("gitlab", iid, webUrl, null),
            priority = meta.priority,
            assignee = meta.assignee,
            blockedBy = meta.blockedBy,
            conflictsWith = meta.conflictsWith,
            parallel = meta.parallel,
        )
    }
}

/** A checked `[tasks.gitlab] url`: [base] without a trailing slash, [host] lower-cased, [path] below the host. */
data class GitlabInstance(
    val base: String,
    val host: String,
    val path: String,
)

/** `[tasks.gitlab] url` as a plain https base URL: no query, fragment or user. */
fun gitlabInstance(url: String): GitlabInstance {
    val u = url.trim()

    fun refuse(): Nothing = bail("gitlab tasks: [tasks.gitlab] url ${debugStr(url)} must be a plain https:// base URL")
    if (!u.startsWith("https://", ignoreCase = true)) {
        if ("://" !in u) bail("gitlab tasks: [tasks.gitlab] url ${debugStr(url)} is not a URL")
        refuse()
    }
    val rest = u.substring("https://".length)
    if ('?' in rest || '#' in rest) refuse()
    val authority = rest.substringBefore('/')
    if ('@' in authority) refuse()
    val host = authority.substringBefore(':').lowercase()
    if (host.isEmpty()) refuse()
    val port = authority.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
    val path = rest.substringAfter('/', "").trim('/')
    val base = "https://$host$port" + if (path.isEmpty()) "" else "/$path"
    return GitlabInstance(base, host, path)
}

fun gitlabApiBase(instance: GitlabInstance) = "${instance.base}/api/v4"

/** The project path for API URLs (`group%2Fname`) or a numeric id, from config or a matching origin [key]. */
fun gitlabProject(
    configured: String,
    instance: GitlabInstance,
    key: String,
): String {
    val path =
        configured.trim().trim('/').ifEmpty {
            originPath(instance, key) ?: bail("gitlab tasks: origin $key is not on ${instance.host}; set [tasks.gitlab] project")
        }
    if (path.isNotEmpty() && path.all { it in '0'..'9' }) return path

    fun ok(s: String) = s.isNotEmpty() && s != "." && s != ".." && s.all { (it.isLetterOrDigit() && it.code < 0x80) || it in "-_." }
    val parts = path.split('/')
    if (parts.size < 2 || !parts.all(::ok)) bail("gitlab tasks: project ${debugStr(path)} is not group/name or a numeric id")
    return parts.joinToString("%2F")
}

/** The project path of [key] when its host is the instance's; an https remote also repeats the instance's path. */
private fun originPath(
    instance: GitlabInstance,
    key: String,
): String? {
    val slash = key.indexOf('/')
    if (slash < 0) return null
    val path = key.substring(slash + 1)
    if (!key.substring(0, slash).substringBefore(':').equals(instance.host, ignoreCase = true)) return null
    val below = instance.path.lowercase()
    if (below.isEmpty()) return path
    return path.removePrefix("$below/").takeIf { path.startsWith("$below/") } ?: path
}

private fun isStatus(label: String) = STATUS_LABELS.any { it.equals(label, ignoreCase = true) }

private fun statusLabel(status: Status) =
    when (status) {
        Status.Open -> null
        Status.InProgress -> GITLAB_IN_PROGRESS
        Status.Done -> GITLAB_DONE
        Status.Closed -> GITLAB_WONT_DO
    }

/** [base] is [gitlabApiBase] (a fake in tests); [project] is [gitlabProject]'s answer. */
class GitlabAdapter(
    base: String,
    private val project: String,
    token: String,
    gap: Duration,
    client: HttpClient,
) : TaskAdapter,
    AutoCloseable {
    private val http = RemoteHttp("gitlab", base, client, listOf("authorization" to "Bearer $token"), gap)

    override val name = "gitlab"

    override fun close() = http.close()

    private val issuesPath get() = "/projects/$project/issues"

    private val shown get() = project.replace("%2F", "/")

    private fun decode(e: JsonElement): GitlabIssue = runCatching { REMOTE_JSON.decodeFromJsonElement<GitlabIssue>(e) }.getOrElse { bail("gitlab: unexpected issue JSON: ${it.message}") }

    private fun issues(
        label: String,
        state: String,
    ) = http.getAll(issuesPath, listOf("labels" to label, "state" to state, "per_page" to "100")).map(::decode)

    private fun find(id: TaskId) = issues(idLabel(id), "all").firstOrNull { it.taskId() == id }

    private fun need(id: TaskId) = find(id) ?: bail("gitlab tasks: no task $id in $shown")

    /** `state_event` when the open/closed side changes. */
    private fun stateEvent(
        status: Status,
        issue: GitlabIssue,
    ) = when {
        status.isActive && issue.state == "closed" -> "reopen"
        !status.isActive && issue.state != "closed" -> "close"
        else -> null
    }

    private fun put(
        id: TaskId,
        issue: GitlabIssue,
        body: JsonObject,
    ): Task {
        val updated = if (body.isEmpty()) issue else decode(http.send("PUT", "$issuesPath/${issue.iid}", body))
        return updated.toTask() ?: bail("gitlab tasks: $id lost its label")
    }

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task {
        if (task.title.isBlank()) bail("gitlab tasks: a task needs a title")
        if (find(id) != null) throw Taken(id)
        val parent = id.parent()?.let { p -> find(p) ?: bail("gitlab tasks: no parent task $p in $shown") }
        val label = idLabel(id)
        val body =
            buildJsonObject {
                put("title", issueTitle(id, task.title))
                put("description", task.description)
                put("labels", "$LABEL,$label")
            }
        val issue = decode(http.send("POST", issuesPath, body))
        if (issue.taskId() != id) {
            bail("gitlab tasks: ${issue.webUrl} was created without the $label label (labels need more than the Guest role in $shown); label or close it by hand")
        }
        if (parent != null) {
            val link =
                buildJsonObject {
                    put("target_project_id", issue.projectId)
                    put("target_issue_iid", issue.iid)
                    put("link_type", "relates_to")
                }
            try {
                http.send("POST", "$issuesPath/${parent.iid}/links", link)
            } catch (e: TaskException) {
                taskWarningSink("gitlab tasks: $id is not linked to ${parent.webUrl}: ${e.message}")
            }
        }
        return issue.toTask() ?: bail("gitlab tasks: the new issue has no task id")
    }

    override fun list(filter: Filter) = filter.select(issues(LABEL, if (filter.wantsFinished) "all" else "opened").mapNotNull { it.toTask() })

    override fun get(id: TaskId) = find(id)?.toTask()

    override fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task {
        val issue = need(id)
        val target = statusLabel(status)
        val body =
            buildJsonObject {
                put("remove_labels", STATUS_LABELS.filter { it != target }.joinToString(","))
                target?.let { put("add_labels", it) }
                stateEvent(status, issue)?.let { put("state_event", it) }
            }
        return put(id, issue, body)
    }

    override fun save(task: Task): Task {
        val issue = need(task.id)
        val desired = issue.labels.filter { !Meta.isManaged(it) && !isStatus(it) } + listOfNotNull(statusLabel(task.status)) + Meta.managedFor(task)
        val remove = issue.labels.filter { it !in desired }
        val add = desired.filter { it !in issue.labels }
        val body =
            buildJsonObject {
                if (remove.isNotEmpty()) put("remove_labels", remove.joinToString(","))
                if (add.isNotEmpty()) put("add_labels", add.joinToString(","))
                stateEvent(task.status, issue)?.let { put("state_event", it) }
            }
        return put(task.id, issue, body)
    }

    override fun storesMarkers() = true

    override fun maxId(prefix: String) = maxWithPrefix(issues(LABEL, "all").mapNotNull { it.taskId() }, prefix)
}
