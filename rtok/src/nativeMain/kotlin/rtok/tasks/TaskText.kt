// Port of the text and parsing helpers of src/tasks/run.rs: `rtok task init`, the agent a claim binds to, the list
// filter, and the ready, list and show texts.
package rtok.tasks

import rtok.config.TASK_ADAPTERS
import rtok.config.writeFile
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.checkPrefix
import rtok.sys.IoException
import rtok.sys.readToString
import rtok.worktree.Claim
import tomlaot.TomlDoc
import tomlaot.TomlValue

/** `rtok task init`: write `[tasks] adapter` and `prefix` into the checkout's `.rtok.toml`, keeping every other line. */
fun initTasks(
    cwd: String,
    adapter: String?,
    prefix: String?,
): String {
    if (adapter != null && adapter !in TASK_ADAPTERS) bail("rtok task init: --adapter must be one of ${TASK_ADAPTERS.joinToString(", ")}")
    prefix?.let(::checkPrefix)
    val path = "${checkoutOf(cwd, "rtok task init")}/.rtok.toml"
    val text =
        try {
            readToString(path)
        } catch (e: IoException) {
            if (e.kind == IoException.Kind.NotFound) "" else bail("$path: ${e.message}")
        }
    val doc = runCatching { TomlDoc.parse(text) }.getOrElse { bail("$path: not valid TOML") }
    val tasks = runCatching { doc.ensureTable("tasks") }.getOrElse { bail("$path: `tasks` is not a table") }
    when {
        adapter != null -> tasks["adapter"] = TomlValue.Str(adapter)
        !tasks.containsKey("adapter") -> tasks["adapter"] = TomlValue.Str("disk")
    }
    prefix?.let { tasks["prefix"] = TomlValue.Str(it.uppercase()) }
    writeFile(path, doc.toString())
    return path
}

/** The agent a claim binds to: `--agent`, else `RTOK_AGENT_ID`. Neither means the caller has to say who is claiming. */
fun requireAgent(
    store: Store,
    flag: String?,
): String = Claim.caller(store, flag)?.id ?: bail("no agent to bind: pass --agent or set RTOK_AGENT_ID")

/** `list`'s filter from what a caller passed: status names, `all`, a parent id. */
fun taskFilter(
    statuses: List<String>,
    all: Boolean,
    parent: String?,
) = Filter(statuses.map(Status::parse), all, parent?.let(TaskId::parse))

private fun ids(list: List<TaskId>) = list.joinToString(", ")

/** `rtok task ready`: one line per task, `(stale)` when the assignee can be replaced. */
fun readyText(items: List<ReadyItem>): String {
    if (items.isEmpty()) return "no ready task\n"
    return buildString {
        for (item in items) {
            append("${item.task.id}  ${item.task.title}")
            if (item.stale) append("  (stale)")
            if (item.task.parallel) append("  [parallel]")
            if (item.conflictsInProgress.isNotEmpty()) append("  (conflicts with ${ids(item.conflictsInProgress)} in progress)")
            append('\n')
        }
    }
}

/** The list line's tail: `  [parallel]` and `  [conflicts: A3, A4]`. */
private fun markers(t: Task) =
    buildString {
        if (t.parallel) append("  [parallel]")
        if (t.conflictsWith.isNotEmpty()) append("  [conflicts: ${ids(t.conflictsWith)}]")
    }

private const val STATUS_WIDTH = 11

/** `rtok task list`: one line per task, subtasks indented under their parent. */
fun taskTable(tasks: List<Task>): String {
    val width = tasks.maxOfOrNull { it.id.toString().length + 2 * (it.id.path.size - 1) } ?: 0
    return buildString {
        for (t in tasks) {
            val id = "  ".repeat(t.id.path.size - 1) + t.id
            append("${id.padEnd(width)}  ${t.status.str.padEnd(STATUS_WIDTH)}  ${t.title}${markers(t)}\n")
        }
    }
}

/** `rtok task show`: the fields, then the description. */
fun taskDetails(shown: Shown): String {
    val t = shown.task
    return buildString {
        append("${t.id}  ${t.title}\nstatus: ${t.status}\n")
        t.parent?.let { append("parent: $it\n") }
        if (t.priority != DEFAULT_PRIORITY) append("priority: ${t.priority}\n")
        t.assignee?.let { append("assignee: $it\n") }
        if (t.blockedBy.isNotEmpty()) append("blocked by: ${ids(t.blockedBy)}\n")
        if (t.conflictsWith.isNotEmpty()) append("conflicts with: ${ids(t.conflictsWith)}\n")
        if (t.parallel) append("parallel: yes\n")
        if (shown.subtasks.isNotEmpty()) append("subtasks: ${ids(shown.subtasks)}\n")
        t.external?.let { append("link: ${it.url}\n") }
        if (t.description.isNotEmpty()) append("\n${t.description}\n")
    }
}
