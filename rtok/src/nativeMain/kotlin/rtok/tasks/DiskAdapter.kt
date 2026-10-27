// Port of src/tasks/disk.rs: the `disk` adapter, one Markdown file per task (`<dir>/R12 - <slug>.md`) with a YAML
// front matter and the description as the body. Done and closed tasks move to `<dir>/done/`. Writes are temp file +
// rename.
package rtok.tasks

import rtok.agent.sdk.writeAtomic
import rtok.store.TaskId
import rtok.sys.IoException
import rtok.sys.createDirAll
import rtok.sys.readDir
import rtok.sys.readToString
import rtok.sys.removeFile

class DiskAdapter(
    private val dir: String,
) : TaskAdapter {
    override val name = "disk"

    private val doneDir get() = "$dir/done"

    private class Skipped(
        val nameId: TaskId,
        val error: Exception,
    )

    private class Scan(
        val found: List<Pair<String, Task>>,
        val skipped: List<Skipped>,
    )

    private fun scan(dir: String): Scan {
        val names =
            try {
                readDir(dir)
            } catch (e: IoException) {
                if (e.kind == IoException.Kind.NotFound) return Scan(emptyList(), emptyList())
                bail("disk tasks: $dir: ${e.message}")
            }
        val found = mutableListOf<Pair<String, Task>>()
        val skipped = mutableListOf<Skipped>()
        for (n in names.sorted()) {
            val path = "$dir/$n"
            // Only `<id> - <slug>.md` is ours; a README or notes beside the tasks stay theirs.
            val nameId = fileId(path) ?: continue
            try {
                found += path to load(path, nameId)
            } catch (e: TaskException) {
                warnSkipped(e)
                skipped += Skipped(nameId, e)
            }
        }
        return Scan(found, skipped)
    }

    /** The file holding [id], in the plan or the archive. Its own broken file is an error; another's is not. */
    private fun find(id: TaskId): Pair<String, Task>? {
        for (d in listOf(dir, doneDir)) {
            val s = scan(d)
            s.skipped.firstOrNull { it.nameId == id }?.let { throw it.error }
            s.found.firstOrNull { fileId(it.first) == id }?.let { return it }
        }
        return null
    }

    /** Write [task] over its file, moving it between the plan and `done/`. The caller holds [lockClaim]. */
    private fun place(
        old: String?,
        task: Task,
    ): Task {
        val into = if (task.status.isActive) dir else doneDir
        createDirAll(into)
        val new = "$into/${diskFileName(task.id, task.title)}"
        writeAtomic(new, renderDisk(task).encodeToByteArray())
        // The new file is complete before the old one goes: a crash in between leaves the task in both directories.
        if (old != null && new != old) removeFile(old)
        return task
    }

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task {
        if (task.title.isBlank()) bail("disk tasks: a task needs a title")
        if (find(id) != null) throw Taken(id)
        val made = Task.open(id, task.title.trim(), task.description, nowSecs())
        return place(null, made)
    }

    override fun list(filter: Filter): List<Task> {
        val dirs = if (filter.wantsFinished) listOf(dir, doneDir) else listOf(dir)
        val all = dirs.flatMap { d -> scan(d).found.map { it.second } }
        return filter.select(all)
    }

    override fun get(id: TaskId) = find(id)?.second

    override fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task =
        lockClaim(dir).use {
            val (old, task) = find(id) ?: bail("disk tasks: no task $id in $dir")
            place(old, task.copy(status = status, updatedAt = nowSecs()))
        }

    override fun claimGuard(): ClaimLock = lockClaim(dir)

    override fun storesMarkers() = true

    override fun save(task: Task): Task {
        val (old, _) = find(task.id) ?: bail("disk tasks: no task ${task.id} in $dir")
        val clean =
            task.copy(
                updatedAt = nowSecs(),
                assignee = task.assignee?.takeIf { it.isNotEmpty() },
                blockedBy = task.blockedBy.distinct().sorted(),
                conflictsWith = task.conflictsWith.distinct().sorted(),
            )
        return place(old, clean)
    }

    override fun maxId(prefix: String): TaskId? {
        val p = prefix.uppercase()
        // A skipped file still owns its name, or the next task would be given its id.
        val ids =
            listOf(dir, doneDir).flatMap { d ->
                val s = scan(d)
                s.found.map { it.second.id } + s.skipped.map { it.nameId }
            }
        return ids.filter { it.prefix == p }.maxOrNull()
    }
}

/** Parse one task file, refusing one whose front-matter id is not its name's. */
private fun load(
    path: String,
    nameId: TaskId,
): Task {
    val text =
        try {
            readToString(path)
        } catch (e: IoException) {
            bail("disk tasks: $path: ${e.message}")
        }
    val task = runCatching { parseDisk(text) }.getOrElse { bail("disk tasks: $path: ${it.message}") }
    if (task.id != nameId) bail("disk tasks: $path: file name says $nameId but the front matter says ${task.id}")
    return task
}
