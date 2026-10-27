// Port of src/tasks/export.rs: `rtok task export`, a one-way mirror of the disk tasks into an issue tracker. The files
// stay the source of truth; the exporter never reads an issue back into a task. Each backend only lists what the
// tracker holds and applies one action; the diff, the dry run and the report are shared here.

package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.config.Tasks
import rtok.store.TaskId

/** The backends `rtok task export` takes. */
val EXPORT_BACKENDS = listOf("github", "linear")

/** The exporter [backend] names, with its target and token resolved. [key] is the project key (names the GitHub repo when none is set). */
fun openExporter(
    cfg: Tasks,
    key: String,
    backend: String,
): Exporter =
    when (backend) {
        "github" -> {
            val repo = githubRepo(listOf(cfg.export.github.repo, cfg.github.repo).firstOrNull { it.isNotEmpty() } ?: "", key)
            val token = token("github", listOf("GH_TOKEN", "GITHUB_TOKEN"), listOf("gh", "auth", "token"))
            // No Projects v2 board: the mirror is issues only.
            GithubExporter(GithubAdapter(GITHUB_API, repo, token, WRITE_GAP, remoteClient()), repo)
        }

        "linear" -> {
            val team =
                cfg.export.linear.team
                    .trim()
            if (team.isEmpty()) bail("linear export: set [tasks.export.linear] team to the team's short key")
            val apiKey = token("linear", listOf("LINEAR_API_KEY"), emptyList())
            LinearExporter(LINEAR_API, team, apiKey, WRITE_GAP, remoteClient())
        }

        else -> {
            bail("rtok task export: unknown backend ${rtok.plugin.sdk.debugStr(backend)}; use ${EXPORT_BACKENDS.joinToString(" or ")}")
        }
    }

/** What a run does to one task. */
@Serializable
enum class ExportKind(
    val word: String,
) {
    @SerialName("create")
    Create("create"),

    @SerialName("update")
    Update("update"),

    /** The task is done or closed locally and still open on the tracker. */
    @SerialName("close")
    Close("close"),

    /** The task is active locally and finished on the tracker. */
    @SerialName("reopen")
    Reopen("reopen"),
}

/** One write the tracker needs. [fields] is what differs (empty for a create); [url] is known for a create only after the write. */
@Serializable
data class ExportAction(
    val id: TaskId,
    val kind: ExportKind,
    val fields: List<String>,
    val url: String? = null,
)

/** A tracker the tasks are mirrored into. */
interface Exporter : AutoCloseable {
    /** `github` or `linear`, for messages and the report. */
    val name: String

    /** Where the issues go (`owner/name`, a team key), for the report. */
    val target: String

    /** Every rtok issue the tracker holds, read the way the task adapters read them, with `external` set. */
    fun remote(): List<Task>

    /** Make the tracker match [task] for [action]; returns the issue's link. */
    fun apply(
        action: ExportAction,
        task: Task,
    ): String?

    /** Whether the tracker keeps the assignee and the blockers; one that does not must not have them compared. */
    fun keepsClaims(): Boolean = true
}

/** What a run found or did. [unchanged]: issues that already match; [skipped]: finished tasks with no issue. */
@Serializable
data class ExportReport(
    val backend: String,
    val target: String,
    @SerialName("dry_run") val dryRun: Boolean,
    val actions: List<ExportAction>,
    val unchanged: Int,
    val skipped: Int,
)

data class ExportPlan(
    val actions: List<ExportAction>,
    val unchanged: Int,
    val skipped: Int,
)

/**
 * The actions that bring the tracker in line with [local], in id order so a parent is created before its subtasks.
 * A finished task with no issue is skipped; an issue with no local task is left alone.
 */
fun exportDiff(
    local: List<Task>,
    remote: List<Task>,
    claims: Boolean,
): ExportPlan {
    val held = LinkedHashMap<TaskId, Task>()
    remote.forEach { held.getOrPut(it.id) { it } }
    // A finished parent of an active subtask is exported too (created, then closed) instead of orphaning the subtask.
    val parents = local.filter { it.status.isActive }.mapNotNull { it.parent }.toSet()
    val outcomes = local.sortedBy { it.id }.map { task -> outcome(task, held[task.id], task.id in parents, claims) }
    return ExportPlan(outcomes.filterIsInstance<ExportAction>(), outcomes.count { it == UNCHANGED }, outcomes.count { it == SKIPPED })
}

private const val UNCHANGED = "unchanged"
private const val SKIPPED = "skipped"

/** The action [task] needs against [have] (its issue, if any), else [UNCHANGED] or [SKIPPED]. */
@Suppress("ReturnCount")
private fun outcome(
    task: Task,
    have: Task?,
    isParent: Boolean,
    claims: Boolean,
): Any {
    val fields =
        when {
            have != null -> drifted(task, have, claims)
            task.status.isActive -> return ExportAction(task.id, ExportKind.Create, emptyList())
            isParent -> return ExportAction(task.id, ExportKind.Create, listOf("status"))
            else -> return SKIPPED
        }
    val kind =
        when {
            fields.isEmpty() -> return UNCHANGED
            !task.status.isActive && have.status.isActive -> ExportKind.Close
            task.status.isActive && !have.status.isActive -> ExportKind.Reopen
            else -> ExportKind.Update
        }
    return ExportAction(task.id, kind, fields, have.external?.url)
}

/** Text compares without the line ending and edge whitespace a web editor adds. */
private fun text(s: String) = s.replace("\r\n", "\n").trim()

/** The fields of [remote] that no longer match [local], in the order the report names them. */
internal fun drifted(
    local: Task,
    remote: Task,
    claims: Boolean,
): List<String> =
    buildList {
        if (text(local.title) != text(remote.title)) add("title")
        if (text(local.description) != text(remote.description)) add("body")
        if (local.status != remote.status) add("status")
        if (local.priority != remote.priority) add("priority")
        if (claims) {
            if (local.assignee != remote.assignee) add("assignee")
            if (local.blockedBy != remote.blockedBy) add("needs")
            if (local.conflictsWith != remote.conflictsWith) add("conflicts")
            if (local.parallel != remote.parallel) add("parallel")
        }
    }

/** Plan against what the tracker holds and, unless [dryRun], apply it. Stops at the first failed write; a re-run picks up from there. */
fun runExport(
    exporter: Exporter,
    local: List<Task>,
    dryRun: Boolean,
): ExportReport {
    val remote =
        try {
            exporter.remote()
        } catch (e: TaskException) {
            bail("${exporter.name} export: listing the existing issues: ${e.message}")
        }
    val plan = exportDiff(local, remote, exporter.keepsClaims())
    var actions = plan.actions
    if (!dryRun) {
        val byId = local.associateBy { it.id }
        actions =
            plan.actions.mapIndexed { done, action ->
                val url =
                    try {
                        exporter.apply(action, byId.getValue(action.id))
                    } catch (e: TaskException) {
                        bail("${exporter.name} export: ${action.kind.word} ${action.id} failed after $done of ${plan.actions.size} writes: ${e.message}")
                    }
                action.copy(url = url ?: action.url)
            }
    }
    return ExportReport(exporter.name, exporter.target, dryRun, actions, plan.unchanged, plan.skipped)
}

/** `rtok task export`'s text: a summary line, then one line per action. */
fun renderExport(r: ExportReport): String =
    buildString {
        fun count(k: ExportKind) = r.actions.count { it.kind == k }
        append(
            "${r.backend} (${r.target}): ${count(ExportKind.Create)} create, ${count(ExportKind.Update)} update, " +
                "${count(ExportKind.Close)} close, ${count(ExportKind.Reopen)} reopen, ${r.unchanged} unchanged",
        )
        if (r.skipped > 0) append(", ${r.skipped} finished not exported")
        if (r.dryRun) append(" (dry run: nothing sent)")
        append('\n')
        for (a in r.actions) {
            append("${a.kind.word} ${a.id}")
            if (a.fields.isNotEmpty()) append("  (${a.fields.joinToString(", ")})")
            a.url?.let { append("  $it") }
            append('\n')
        }
    }
