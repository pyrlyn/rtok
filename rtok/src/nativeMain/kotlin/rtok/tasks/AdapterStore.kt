@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// The legacy `files` and `plan` layouts and the remote adapters as a kotlin-taskboard `Store`, so they get the board
// layout's API and concurrency rules: the engine adds revisions, leases, idempotency keys and the journal on top.

package rtok.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pyrlyn.taskboard.BoardError
import pyrlyn.taskboard.BoardException
import pyrlyn.taskboard.Commit
import pyrlyn.taskboard.Reader
import pyrlyn.taskboard.Rev
import pyrlyn.taskboard.Session
import pyrlyn.taskboard.StateNames
import pyrlyn.taskboard.Store
import pyrlyn.taskboard.Versioned
import rtok.agent.sdk.writeAtomic
import rtok.sys.IoException
import rtok.sys.appendString
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.removeFile
import rtok.sys.tryLockExclusive
import kotlin.time.Duration
import kotlin.time.TimeSource
import pyrlyn.taskboard.Task as LibTask
import pyrlyn.taskboard.TaskId as LibId

private const val LOCK_POLL_US = 5_000u

/**
 * A [Store] over [adapter], with the engine's state in [stateDir] (under the main checkout's `.rtok/taskboard/`), so a
 * committed task folder gains no files. Unlike the board layout's file store it keeps no redo log: a crash between
 * two writes of one commit can leave half of it, as these layouts always could.
 */
internal class AdapterStore(
    private val adapter: TaskAdapter,
    private val stateDir: String,
) : Store {
    override fun snapshot(): Reader = View()

    override fun <T> locked(
        timeout: Duration,
        body: (Session) -> T,
    ): T {
        createDirAll(stateDir)
        val start = TimeSource.Monotonic.markNow()
        var fd = tryLockExclusive("$stateDir/.lock")
        while (fd == null) {
            if (start.elapsedNow() >= timeout) throw BoardException(BoardError.Busy())
            platform.posix.usleep(LOCK_POLL_US)
            fd = tryLockExclusive("$stateDir/.lock")
        }
        return DiskGuard(fd).use {
            // The folder ignores itself, so a project that does not ignore `.rtok/` still never commits engine state.
            // Written under the lock: two first writers would otherwise race on the same temporary file.
            if (!exists("$stateDir/.gitignore")) writeAtomic("$stateDir/.gitignore", "*\n".encodeToByteArray())
            body(Locked())
        }
    }

    private fun statePath(name: String) = "$stateDir/$name" + if (name == StateNames.JOURNAL) ".jsonl" else ".json"

    private open inner class View : Reader {
        private var cached: List<Task>? = null

        val current: List<Task> get() = cached ?: adapter.list(Filter(all = true)).also { cached = it }

        fun forget() {
            cached = null
        }

        override fun tasks() = current.map { Versioned(it.lib(), adapterRev(it)) }

        // The adapters report what they skip through warnOnce themselves.
        override fun warnings() = emptyList<String>()

        override fun state(name: String): String? =
            try {
                readToString(statePath(name))
            } catch (e: IoException) {
                if (e.kind == IoException.Kind.NotFound) null else throw e
            }
    }

    private inner class Locked :
        View(),
        Session {
        override fun revOf(task: LibTask): Rev = adapterRev(task.rtok())

        override fun commit(commit: Commit): Map<LibId, Rev> {
            if (commit.remove.isNotEmpty()) throw BoardException(BoardError.Invalid("${adapter.name} tasks cannot be deleted"))
            val had = current.associateBy { it.id }
            val revs = commit.put.associate { lib -> lib.id to revOf(lib).also { write(lib.rtok(), had[lib.id.rtok()]) } }
            forget()
            for ((name, text) in commit.state) {
                if (text != null) {
                    writeAtomic(statePath(name), text.encodeToByteArray())
                } else if (exists(statePath(name))) {
                    removeFile(statePath(name))
                }
            }
            for ((name, lines) in commit.append) appendString(statePath(name), lines.joinToString("") { it + "\n" })
            return revs
        }
    }

    /** Brings the adapter's copy of a task from [had] (null: new) to [want], with the fewest adapter writes. */
    private fun write(
        want: Task,
        had: Task?,
    ) {
        var now = had ?: adapter.create(NewTask(want.title, want.description, want.parent), want.id)
        if (want.status != now.status) now = adapter.writeStatus(want.id, want.status, want.report.takeIf { adapter.movesWithReport() })
        val report = want.report?.takeIf { !want.status.isActive && !adapter.movesWithReport() }
        if (report != null && report != now.report) adapter.writeReport(want.id, report)
        if (differs(want, now, edited = had != null)) {
            adapter.save(want.copy(status = now.status, report = now.report, createdAt = now.createdAt, external = now.external))
        }
    }
}

/**
 * Whether [want] has fields [now] lacks that only `save` writes. A new task's title and description went into
 * `create`, and an adapter may reformat them (a remote one cannot `save` at all).
 */
private fun differs(
    want: Task,
    now: Task,
    edited: Boolean,
) = want.priority != now.priority ||
    want.parent != now.parent ||
    want.assignee?.takeIf { it.isNotEmpty() } != now.assignee?.takeIf { it.isNotEmpty() } ||
    want.blockedBy.sorted() != now.blockedBy.sorted() ||
    want.conflictsWith.sorted() != now.conflictsWith.sorted() ||
    want.parallel != now.parallel ||
    (edited && (want.title.trim() != now.title.trim() || want.description.trim() != now.description.trim()))

/**
 * The rev of a task an adapter holds. It leaves out what some adapter rewrites on its own (the plan layout adds a
 * `Check:` line to a new description and writes its own `done.md` text, a remote one reformats bodies) and the times
 * (an adapter stamps its own), so a write never reads back as a hand edit; an edit of the description or report is
 * not seen as one either.
 */
private fun adapterRev(t: Task): Rev =
    Rev.of(
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(t.id.toString()),
                "title" to JsonPrimitive(t.title.trim()),
                "status" to JsonPrimitive(t.status.str),
                "priority" to JsonPrimitive(t.priority),
                "parent" to JsonPrimitive(t.parent?.toString()),
                "assignee" to JsonPrimitive(t.assignee?.takeIf { it.isNotEmpty() }),
                "blocked_by" to JsonArray(t.blockedBy.sorted().map { JsonPrimitive(it.toString()) }),
                "conflicts_with" to JsonArray(t.conflictsWith.sorted().map { JsonPrimitive(it.toString()) }),
                "parallel" to JsonPrimitive(t.parallel),
                "external" to JsonPrimitive(t.external?.url),
            ),
        ).toString().encodeToByteArray(),
    )
