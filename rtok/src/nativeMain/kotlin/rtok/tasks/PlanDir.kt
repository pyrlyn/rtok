// Port of src/tasks/plan_dir.rs: the folder layout of the `disk` adapter, `<dir>/<id>/task.md` and a `done.md` closing
// report whose presence means the task is closed. A write changes only the `status`, `priority` and `agent` lines of
// task.md's front matter; what the layout has no key for (blockers, conflicts, parallel) is refused, not dropped.
package rtok.tasks

import rtok.agent.sdk.writeAtomic
import rtok.store.TaskId
import rtok.sys.IoException
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileStat
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.readDir
import rtok.sys.readToString

private const val TASK = "task.md"
private const val DONE = "done.md"

/** Highest priority the front matter can carry (`P3`); rtok's `4` has no `P` word. */
private const val PRIORITY_P_MAX = 3

/** `done.md` of a closed ("won't do") task starts with this. */
private const val DROPPED = "Dropped"

/** The `complexity` and `readiness` of a task created through rtok. */
private const val NEW_HEAD = "complexity: 3\nreadiness: 0%"

/** The front-matter lines a write rewrites: status, priority, agent. */
private const val HEAD_KEYS = 3

private const val NANOS = 1_000_000_000L

private fun readOrNull(path: String): String? =
    try {
        readToString(path)
    } catch (e: IoException) {
        if (e.kind == IoException.Kind.NotFound) null else bail("disk tasks: $path: ${e.message}")
    }

/** Rewrite the three keys of [path] (a task.md) from [task]. The caller holds the claim lock. */
private fun storeHead(
    path: String,
    task: Task,
) {
    val id = task.id
    if (task.blockedBy.isNotEmpty() || task.conflictsWith.isNotEmpty() || task.parallel) {
        bail("disk tasks: $id: the folder layout has no place for blockers, conflicts or the parallel marker")
    }
    val text = readOrNull(path) ?: bail("disk tasks: $path: not found")
    val out =
        try {
            withHead(text, task)
        } catch (e: TaskException) {
            bail("$id: ${e.message}")
        }
    writeAtomic(path, out.encodeToByteArray())
}

class PlanDirAdapter(
    private val dir: String,
) : TaskAdapter {
    override val name = "disk"

    companion object {
        /** Whether [dir] already holds the folder layout: some `<id>/task.md` in it. */
        fun holdsFolders(dir: String): Boolean = runCatching { readDir(dir) }.getOrDefault(emptyList()).any { isFile("$dir/$it/$TASK") }
    }

    private fun folder(id: TaskId) = "$dir/$id"

    private fun read(id: TaskId): Task? {
        val path = "${folder(id)}/$TASK"
        val text = readOrNull(path) ?: return null
        val donePath = "${folder(id)}/$DONE"
        val done = readOrNull(donePath)
        val stamp = fileStat(if (done != null) donePath else path)?.first?.div(NANOS) ?: nowSecs()
        return try {
            parsePlan(id, text, done, stamp)
        } catch (e: TaskException) {
            bail("disk tasks: $path: ${e.message}")
        }
    }

    /** Every task folder that parses, and the ids of the ones that do not. */
    private fun scan(): Pair<List<Task>, List<TaskId>> {
        val names =
            try {
                readDir(dir)
            } catch (e: IoException) {
                if (e.kind == IoException.Kind.NotFound) return emptyList<Task>() to emptyList()
                bail("disk tasks: $dir: ${e.message}")
            }
        val found = mutableListOf<Task>()
        val skipped = mutableListOf<TaskId>()
        for (n in names.sorted()) {
            val id = folderId("$dir/$n", n) ?: continue
            try {
                read(id)?.let { found += it }
            } catch (e: TaskException) {
                warnSkipped(e)
                skipped += id
            }
        }
        return found to skipped
    }

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task {
        val title = task.title.trim()
        if (title.isEmpty() || '\n' in title || '\r' in title) bail("disk tasks: a task needs a one-line title")
        if (exists("${folder(id)}/$TASK")) throw Taken(id)
        var body = task.description.trimEnd()
        // The plan check wants exactly one `Check:` line on an active task; one made without gets a prompt.
        if (body.lines().none { it.startsWith("Check:") }) {
            if (body.isNotEmpty()) body += "\n\n"
            body += "Check: say how to prove this task is done."
        }
        val text = "---\nstatus: todo\npriority: P$DEFAULT_PRIORITY\n$NEW_HEAD\nagent:\n---\n\n# $id. $title\n\n$body\n"
        createDirAll(folder(id))
        writeAtomic("${folder(id)}/$TASK", text.encodeToByteArray())
        return read(id) ?: bail("disk tasks: $id vanished after create")
    }

    override fun list(filter: Filter) = filter.select(scan().first)

    override fun get(id: TaskId) = read(id)

    override fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task =
        lockClaim(dir).use {
            val task = read(id) ?: bail("disk tasks: no task $id in $dir")
            if (task.status == status) return@use task
            if (!task.status.isActive) {
                bail("disk tasks: $id is ${task.status} and has a done.md; reopening it is the creator's call, remove ${folder(id)}/$DONE yourself")
            }
            if (status.isActive) {
                storeHead("${folder(id)}/$TASK", task.copy(status = status, assignee = if (status == Status.Open) null else task.assignee))
            } else {
                val text = if (status == Status.Closed) "$DROPPED: closed with `rtok task status`.\n" else "Done: closed with `rtok task status`.\n"
                writeAtomic("${folder(id)}/$DONE", text.encodeToByteArray())
            }
            read(id) ?: bail("disk tasks: $id vanished")
        }

    override fun writeReport(
        id: TaskId,
        report: String,
    ) {
        lockClaim(dir).use {
            val status = read(id)?.status?.takeIf { !it.isActive } ?: bail("disk tasks: $id is not finished, so it has no closing report")
            val r = report.trim()
            if (r.isEmpty()) bail("disk tasks: the closing report is empty")
            val text = if (status == Status.Closed && !r.startsWith(DROPPED)) "$DROPPED: $r\n" else "$r\n"
            writeAtomic("${folder(id)}/$DONE", text.encodeToByteArray())
        }
    }

    override fun claimGuard(): ClaimLock = lockClaim(dir)

    override fun save(task: Task): Task {
        val old = read(task.id) ?: bail("disk tasks: no task ${task.id} in $dir")
        if (!old.status.isActive || !task.status.isActive) bail("disk tasks: ${task.id} is finished; its done.md is the record")
        storeHead("${folder(task.id)}/$TASK", task.copy(assignee = task.assignee?.takeIf { it.isNotEmpty() }))
        return read(task.id) ?: bail("disk tasks: ${task.id} vanished")
    }

    override fun maxId(prefix: String): TaskId? {
        val p = prefix.uppercase()
        val (found, skipped) = scan()
        return (found.map { it.id } + skipped).filter { it.prefix == p }.maxOrNull()
    }
}

/** The id a folder is named after, when the name is exactly that id's spelling. */
private fun folderId(
    path: String,
    name: String,
): TaskId? =
    runCatching { TaskId.parse(name) }
        .getOrNull()
        ?.takeIf { it.toString() == name && isDir(path) }

/** `(front matter, body)` of a task.md. */
internal fun splitFront(text: String): Pair<String, String> {
    if (!text.startsWith("---\n")) bail("no front matter (the file must start with ---)")
    val rest = text.removePrefix("---\n")
    val end = rest.indexOf(FENCE)
    if (end < 0) bail("front matter is not closed with ---")
    return rest.substring(0, end) to rest.substring(end + FENCE.length)
}

private fun value(
    head: String,
    key: String,
): String? = head.lines().firstNotNullOfOrNull { l -> l.takeIf { it.startsWith("$key:") }?.substring(key.length + 1) }?.trim()

private fun rustLines(s: String) = if (s.isEmpty()) emptyList() else s.removeSuffix("\n").split("\n").map { it.removeSuffix("\r") }

private fun some(v: String?) = v?.let { "Some(\"$it\")" } ?: "None"

/** `(status, priority, agent)` of an active task's front matter. */
private fun activeHead(head: String): Triple<Status, Int, String?> {
    val status =
        when (val s = value(head, "status")) {
            "todo" -> Status.Open
            "in progress" -> Status.InProgress
            else -> bail("status ${some(s)}, want todo or in progress")
        }
    val p = value(head, "priority")
    val priority =
        p
            ?.takeIf { it.startsWith("P") }
            ?.substring(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..PRIORITY_P_MAX }
    return Triple(status, priority ?: bail("priority ${some(p)}, want P0..P3"), value(head, "agent")?.takeIf { it.isNotEmpty() })
}

internal fun parsePlan(
    id: TaskId,
    text: String,
    done: String?,
    stamp: Long,
): Task {
    // A finished task's task.md is history, and the oldest predate the front matter: they only must not stop a listing.
    val finished = done != null
    val (head, body) =
        try {
            splitFront(text)
        } catch (e: TaskException) {
            if (finished) "" to text else throw e
        }
    val heading = "# $id. "
    val lines = rustLines(body).dropWhile { !it.startsWith("# ") }
    val first = lines.firstOrNull()?.takeIf { it.startsWith(heading) }?.removePrefix(heading)
    val raw = first ?: if (finished) "" else bail("no `${heading}Title` heading")
    val title = if (raw.isBlank() && finished) id.toString() else raw.trim()
    val description = lines.drop(1).joinToString("\n").trim()
    val (status, priority, assignee) =
        if (done != null) {
            Triple(if (done.trimStart().startsWith(DROPPED)) Status.Closed else Status.Done, DEFAULT_PRIORITY, null)
        } else {
            activeHead(head)
        }
    return Task.open(id, title, "", 0).copy(description = description, status = status, priority = priority, assignee = assignee, createdAt = stamp, updatedAt = stamp)
}

/** [text] with its `status`, `priority` and `agent` lines set from [task]. */
internal fun withHead(
    text: String,
    task: Task,
): String {
    val status =
        when (task.status) {
            Status.Open -> "todo"
            Status.InProgress -> "in progress"
            else -> bail("only todo and in progress are front matter statuses")
        }
    if (task.priority > PRIORITY_P_MAX) bail("priority ${task.priority} has no P word (P0..P3)")
    val agent = task.assignee.orEmpty()
    if ('\n' in agent || '\r' in agent) bail("the agent name must be one line")
    if (agent.isEmpty() == (task.status == Status.InProgress)) {
        bail("an in-progress task needs an agent and a todo one has none; use `rtok task claim`")
    }
    val (head, body) = splitFront(text)
    var seen = 0
    val lines =
        rustLines(head).map { l ->
            val new =
                when (l.substringBefore(':')) {
                    "status" -> "status: $status"
                    "priority" -> "priority: P${task.priority}"
                    "agent" -> if (agent.isEmpty()) "agent:" else "agent: $agent"
                    else -> return@map l
                }
            seen++
            new
        }
    if (seen != HEAD_KEYS) bail("front matter lacks status, priority or agent")
    return "---\n${lines.joinToString("\n")}\n---\n$body"
}
