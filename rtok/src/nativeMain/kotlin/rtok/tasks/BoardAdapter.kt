// The task board: one folder per column under [dir] (`todo`, `in-progress`, `done`, `closed`), one Markdown file per
// task (`<ID>-<slug>.md`). The folder is the status, so moving a file is a status change. Writes are temp file + rename
// and take `<dir>/.lock`. Kept free of config, store and CLI types so it can become a library.
package rtok.tasks

import rtok.agent.sdk.writeAtomic
import rtok.store.TaskId
import rtok.sys.IoException
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileStat
import rtok.sys.isDir
import rtok.sys.readDir
import rtok.sys.readToString
import rtok.sys.removeFile

/** The columns left to right; a column's folder is its status's name. */
internal val BOARD_COLUMNS = Status.entries

private const val NANOS = 1_000_000_000L
private const val GITIGNORE = ".lock\n.sync/\n"

@Suppress("TooManyFunctions")
class BoardAdapter(
    private val dir: String,
    private val clock: () -> Long = ::nowSecs,
    private val warn: (String) -> Unit = ::warnOnce,
) : TaskAdapter {
    override val name = "board"

    private val noted = mutableListOf<String>()

    /** What was skipped while reading, once each, for callers that report it (`board --json`). */
    val warnings: List<String> get() = noted

    private class Found(
        val path: String,
        val task: Task,
        val extra: String,
    )

    /** Every copy of one id: normally one file; two only after a crash between a move's write and its unlink. */
    private class Hit(
        val best: Found,
        val paths: List<String>,
    ) {
        /** A write of [edited] would change nothing: one copy, and every field but `updated` as it is. */
        fun unchangedBy(edited: Task) = paths.size == 1 && edited.copy(updatedAt = best.task.updatedAt) == best.task
    }

    private fun note(message: String) {
        if (message in noted) return
        noted += message
        warn(message)
    }

    private fun names(column: Status): List<String> =
        try {
            readDir("$dir/${column.str}").sorted()
        } catch (e: IoException) {
            if (e.kind == IoException.Kind.NotFound) emptyList() else bail("board tasks: $dir/${column.str}: ${e.message}")
        }

    private fun load(
        path: String,
        nameId: TaskId,
        column: Status,
    ): Found {
        val text =
            try {
                readToString(path)
            } catch (e: IoException) {
                bail("board tasks: $path: ${e.message}")
            }
        val stamp = fileStat(path)?.first?.div(NANOS) ?: clock()
        val task = runCatching { parseBoard(text, column, stamp) }.getOrElse { bail("board tasks: $path: ${it.message}") }
        if (task.id != nameId) bail("board tasks: $path: file name says $nameId but the front matter says ${task.id}")
        return Found(path, task, unknownKeys(splitFrontMatter(text).first))
    }

    /** Folders and loose task files that are not part of the board, reported instead of read. */
    private fun noteStrays() {
        val entries =
            try {
                readDir(dir).sorted()
            } catch (_: IoException) {
                return
            }
        for (n in entries) {
            val path = "$dir/$n"
            when {
                n.startsWith('.') || n in BOARD_COLUMNS.map { it.str } -> {}

                isDir(path) -> {
                    note("skipping the folder $path: the board's columns are todo, in-progress, done and closed")
                }

                boardFileId(n) != null -> {
                    note("skipping $path: a task file belongs in a column folder")
                }
            }
        }
    }

    private fun scan(columns: List<Status>): List<Task> {
        noteStrays()
        val out = mutableListOf<Task>()
        for (column in columns) {
            for (n in names(column)) {
                val nameId = boardFileId(n) ?: continue
                try {
                    out += load("$dir/${column.str}/$n", nameId, column).task
                } catch (e: TaskException) {
                    note("skipping a task that does not parse: ${e.message}")
                }
            }
        }
        return out
    }

    /** The file holding [id]. Its own broken file is an error; another's is not. */
    private fun find(id: TaskId): Hit? {
        val found = mutableListOf<Found>()
        var broken: TaskException? = null
        for (column in BOARD_COLUMNS) {
            for (n in names(column)) {
                if (boardFileId(n) != id) continue
                try {
                    found += load("$dir/${column.str}/$n", id, column)
                } catch (e: TaskException) {
                    broken = e
                }
            }
        }
        if (found.isEmpty()) return broken?.let { throw it }
        // After an interrupted move the newer write wins; a tie goes to the later column.
        val best = found.withIndex().maxWith(compareBy({ it.value.task.updatedAt }, { it.index })).value
        return Hit(best, found.map { it.path })
    }

    /** The board directory is made, with its `.gitignore`, the first time rtok writes. */
    private fun prepare() {
        if (isDir(dir)) return
        createDirAll(dir)
        ensureGitignore()
    }

    /** Keep the lock and the sync state out of git; a `.gitignore` that is already there is the user's. */
    fun ensureGitignore() {
        if (!exists("$dir/.gitignore")) writeAtomic("$dir/.gitignore", GITIGNORE.encodeToByteArray())
    }

    /** The path [task] is stored at. */
    fun pathOf(task: Task) = "$dir/${task.status.str}/${boardFileName(task)}"

    /** Write [edited] over its file, moving it between columns; a write that changes nothing touches nothing. */
    private fun place(
        hit: Hit?,
        edited: Task,
        touch: Boolean = true,
    ): Task {
        if (touch && hit != null && hit.unchangedBy(edited)) return hit.best.task
        val task = if (touch) edited.copy(updatedAt = clock()) else edited
        val path = pathOf(task)
        createDirAll("$dir/${task.status.str}")
        writeAtomic(path, renderBoard(task, hit?.best?.extra.orEmpty()).encodeToByteArray())
        // The new file is complete before the old one goes. Names are compared case-insensitively: on APFS the old and
        // the new spelling are one file, and unlinking it would delete what was just written.
        hit?.paths?.filterNot { it.equals(path, ignoreCase = true) }?.forEach(::removeFile)
        return task
    }

    /** Store [task] as it is, `updated` included: a migration moves tasks, it does not edit them. */
    fun import(task: Task): Task {
        prepare()
        return place(find(task.id), task, touch = false)
    }

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task {
        if (task.title.isBlank()) bail("board tasks: a task needs a title")
        prepare()
        return lockClaim(dir, LOCK).use {
            if (find(id) != null) throw Taken(id)
            place(null, Task.open(id, task.title.trim(), task.description, clock()))
        }
    }

    override fun list(filter: Filter): List<Task> = filter.select(scan(if (filter.wantsFinished) BOARD_COLUMNS else BOARD_COLUMNS.filter { it.isActive }))

    override fun get(id: TaskId) = find(id)?.best?.task

    override fun movesWithReport() = true

    override fun writeStatus(
        id: TaskId,
        status: Status,
        report: String?,
    ): Task {
        prepare()
        return lockClaim(dir, LOCK).use {
            val hit = find(id) ?: bail("board tasks: no task $id in $dir")
            val task = hit.best.task
            place(hit, task.copy(status = status, report = report?.trim()?.takeIf { it.isNotEmpty() } ?: task.report))
        }
    }

    override fun writeReport(
        id: TaskId,
        report: String,
    ) {
        prepare()
        lockClaim(dir, LOCK).use {
            val hit = find(id) ?: bail("board tasks: no task $id in $dir")
            if (hit.best.task.status.isActive) bail("board tasks: $id is not finished, so it has no closing report")
            val r = report.trim()
            if (r.isEmpty()) bail("board tasks: the closing report is empty")
            place(hit, hit.best.task.copy(report = r))
        }
    }

    override fun claimGuard(): ClaimLock {
        prepare()
        return lockClaim(dir, LOCK)
    }

    override fun storesMarkers() = true

    override fun save(task: Task): Task {
        val hit = find(task.id) ?: bail("board tasks: no task ${task.id} in $dir")
        val clean =
            task.copy(
                assignee = task.assignee?.takeIf { it.isNotEmpty() },
                blockedBy = task.blockedBy.distinct().sorted(),
                conflictsWith = task.conflictsWith.distinct().sorted(),
            )
        return place(hit, clean)
    }

    override fun maxId(prefix: String): TaskId? {
        val p = prefix.uppercase()
        // A file that does not parse still owns its name, or the next task would be given its id.
        return BOARD_COLUMNS.flatMap { c -> names(c).mapNotNull(::boardFileId) }.filter { it.prefix == p }.maxOrNull()
    }

    private companion object {
        const val LOCK = ".lock"
    }
}
