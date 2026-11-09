// One project's tasks: where they live, the prefix new ids get, and every operation over them. Writes go through the
// kotlin-taskboard engine, over a file store for the board layout and over the adapter for the others.
package rtok.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import pyrlyn.taskboard.Board
import pyrlyn.taskboard.BoardOptions
import pyrlyn.taskboard.Boards
import pyrlyn.taskboard.Commit
import pyrlyn.taskboard.Rev
import pyrlyn.taskboard.StateNames
import pyrlyn.taskboard.Write
import pyrlyn.taskboard.files.FileStore
import rtok.config.ProjectRoot
import rtok.config.Tasks
import rtok.config.contain
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.agentLastSeen
import rtok.store.clearTaskClaim
import rtok.store.seedTaskCounter
import rtok.store.taskCounter
import rtok.sys.IoException
import rtok.sys.isDir
import rtok.sys.readDir
import rtok.worktree.Claim
import kotlin.time.Clock
import pyrlyn.taskboard.Filter as LibFilter
import pyrlyn.taskboard.NewTask as LibNewTask
import pyrlyn.taskboard.Store as TaskStore

/** Creates tried when the id keeps turning out taken, before the last error is the answer. */
private const val TAKEN_RETRIES = 3

private val LOCK_TIMEOUT = BoardOptions().lockTimeout

/** `rtok task show`: the task and the ids of its subtasks. */
data class Shown(
    val task: Task,
    val subtasks: List<TaskId>,
) {
    fun toJson() = JsonObject(TASK_JSON.encodeToJsonElement(task).jsonObject + ("subtasks" to JsonArray(subtasks.map { JsonPrimitive(it.toString()) })))
}

private const val DEFAULT_DIR = "tasks"

/** The legacy files layout's directory is named in `[tasks.disk]` too; the new key wins once it differs from its default. */
internal fun Tasks.boardDirName() = if (dir != DEFAULT_DIR) dir else disk.dir

/** `[tasks] layout`, else the old `[tasks.disk] layout` (`folders` is now `plan`). */
internal fun Tasks.layoutName() =
    if (layout != "auto") {
        layout
    } else if (disk.layout == "folders") {
        "plan"
    } else {
        disk.layout
    }

/**
 * The board directory: [Tasks.boardDirName] under the main checkout (`shared`, so every worktree sees one board) or under
 * this checkout. A repository's `.rtok.toml` is untrusted, so the name may not climb out of the root.
 */
internal fun boardDirOf(
    cfg: Tasks,
    root: ProjectRoot,
): String {
    val name = cfg.boardDirName()
    val absolute = name.startsWith("/") || name.startsWith("\\") || (name.length > 1 && name[1] == ':')
    if (name.isBlank() || absolute || name.split('/', '\\').any { it == ".." }) {
        bail("rtok task: [tasks] dir must be a relative path inside the repository, not ${rtok.plugin.sdk.debugStr(name)}")
    }
    val base = if (cfg.shared) root.main else root.checkout
    try {
        // Only the check matters: the path used stays as written, so a symlinked checkout keeps its own spelling.
        contain(base, name)
    } catch (e: IoException) {
        bail("rtok task: [tasks] dir ${rtok.plugin.sdk.debugStr(name)} leaves the repository: ${e.message}")
    }
    return "$base/$name"
}

// `done/` is left out: the legacy files layout archives finished tasks there too.
private val BOARD_ONLY_COLUMNS = listOf("todo", "in-progress", "closed")

private fun namesIn(dir: String) = runCatching { readDir(dir) }.getOrDefault(emptyList())

/** What `auto` makes of [dir]: a board for a new, empty or column-shaped directory, else the old layout it holds. */
internal fun detectLayout(dir: String): String =
    when {
        namesIn(dir).any { it in BOARD_ONLY_COLUMNS && isDir("$dir/$it") } -> "board"
        namesIn(dir).any { fileId("$dir/$it") != null } || namesIn("$dir/done").any { fileId("$dir/done/$it") != null } -> "files"
        PlanDirAdapter.holdsFolders(dir) -> "plan"
        else -> "board"
    }

/** Hex digits of the hash that tell two state folders apart: enough for the few boards one checkout has. */
private const val STATE_DIR_HASH = 8

/**
 * Where the engine keeps the state of a layout that has no place for it: under the main checkout's `.rtok/taskboard/`,
 * one folder per task directory (or remote), so a committed `plan/` folder gains no files.
 */
internal fun stateDirOf(
    main: String,
    kind: String,
    what: String,
) = "$main/.rtok/taskboard/$kind-${Rev.of(what.encodeToByteArray()).value.take(STATE_DIR_HASH)}"

/** The checkout, not main: `.rtok.toml` and the disk directory are checked-in files, so each worktree has its own. */
internal fun checkoutOf(
    cwd: String,
    what: String,
): String = ProjectRoot.find(cwd)?.checkout ?: bail("$what: not inside a git checkout")

class TaskProject(
    /** The counter key, `projectKey`. */
    val key: String,
    /** The checkout's root: `.rtok.toml` and the disk adapter's directory live here. */
    val root: String,
    val prefix: String,
    /** Reads for the callers that list tasks (CLI, MCP, web, export, sync); every write goes through [engine]. */
    val adapter: TaskAdapter,
    /** Where the tasks live: the board directory for the disk layouts, the checkout for a remote adapter. */
    val boardDir: String = root,
    /** `board`, `files` or `plan`, decided once per command; a remote adapter's name otherwise. */
    val layout: String = adapter.name,
    /** What the engine reads and writes: the board's folder, or [adapter] with its state kept apart. */
    internal val tasks: TaskStore = AdapterStore(adapter, stateDirOf(root, adapter.name, boardDir)),
    /** What leases and timestamps are measured by; a test turns it forward to let a lease lapse. */
    internal val clock: Clock = Clock.System,
) {
    companion object {
        /** The board directory's layout: its own folder for a board, the old adapter under the engine otherwise. */
        private fun openDisk(
            cfg: Tasks,
            found: ProjectRoot,
            key: String,
            prefix: String,
        ): TaskProject {
            val dir = boardDirOf(cfg, found)
            val wanted = cfg.layoutName()
            val layout = if (wanted == "auto") detectLayout(dir) else wanted
            if (layout == "board") {
                val files = FileStore(dir)
                return TaskProject(key, found.checkout, prefix, StoreReadAdapter(files), dir, layout, files)
            }
            val adapter = if (layout == "files") DiskAdapter(dir) else PlanDirAdapter(dir)
            return TaskProject(key, found.checkout, prefix, adapter, dir, layout, AdapterStore(adapter, stateDirOf(found.main, layout, dir)))
        }

        /** The project [cwd] belongs to, with the adapter `[tasks]` names. */
        fun open(
            cfg: Tasks,
            cwd: String,
        ): TaskProject {
            val found = ProjectRoot.find(cwd) ?: bail("rtok task: not inside a git checkout")
            val root = found.checkout
            val key = rtok.projectKey(cwd) ?: bail("rtok task: cannot tell which project this checkout is")
            val prefix = resolvePrefix(cfg.prefix, rtok.projectName(cwd))
            if (cfg.adapter == "disk") return openDisk(cfg, found, key, prefix)
            val adapter =
                when (cfg.adapter) {
                    "github" -> {
                        val repo = githubRepo(cfg.github.repo, key)
                        val token = token("github", listOf("GH_TOKEN", "GITHUB_TOKEN"), listOf("gh", "auth", "token"))
                        GithubAdapter(GITHUB_API, repo, token, WRITE_GAP, remoteClient(), cfg.github.project.toLong())
                    }

                    "gitlab" -> {
                        val instance = gitlabInstance(cfg.gitlab.url)
                        val project = gitlabProject(cfg.gitlab.project, instance, key)
                        val vars = listOf("GITLAB_TOKEN", "GITLAB_ACCESS_TOKEN", "GL_TOKEN")
                        val token = token("gitlab", vars, listOf("glab", "config", "get", "token", "--host", instance.host))
                        GitlabAdapter(gitlabApiBase(instance), project, token, WRITE_GAP, remoteClient())
                    }

                    else -> {
                        bail("rtok task: unknown [tasks] adapter \"${cfg.adapter}\"")
                    }
                }
            return TaskProject(key, root, prefix, adapter, root, adapter.name, AdapterStore(adapter, stateDirOf(found.main, adapter.name, key)))
        }
    }

    /**
     * The engine over [tasks]. With [store], a holder whose lease ran out keeps the task while its agent is still seen
     * (`agents.last_seen`), so a claim lasts as long as its agent runs.
     */
    internal fun engine(store: Store? = null): Board =
        Boards.open(
            tasks,
            BoardOptions(clock = clock, idPrefix = prefix, liveness = store?.let { s -> { agent: String -> !holderStale(s, agent) } }, warn = ::warnOnce),
        )

    /** Store a new task under the next free id. */
    fun create(
        store: Store,
        new: NewTask,
    ): Task {
        val parent = new.parent
        if (parent != null && adapter.get(parent) == null) bail("rtok task: no parent task $parent")
        // rtok's own counter remembers ids of deleted tasks and those an older rtok handed out; the board starts past it.
        raiseIds(null, store.taskCounter(key, null).toLong())
        repeat(TAKEN_RETRIES) { attempt ->
            try {
                val made =
                    onBoard {
                        engine(store)
                            .create(LibNewTask(new.title, new.description, parent?.lib()))
                            .task.value
                            .rtok()
                    }
                // `rtok task seed` and `sync` still report rtok's counter, so it follows the board.
                store.seedTaskCounter(key, made.parent, made.id.path.last())
                return made
            } catch (e: Taken) {
                // Another machine created this id on the remote after the board listed it; the next try starts past it.
                if (attempt == TAKEN_RETRIES - 1) throw e
                raiseIds(
                    e.id.parent(),
                    e.id.path
                        .last()
                        .toLong(),
                )
            }
        }
        error("unreachable")
    }

    /** Raises the board's last number under [parent] (null: top level) in the engine's documented `ids` blob. */
    private fun raiseIds(
        parent: TaskId?,
        atLeast: Long,
    ) {
        val name = parent?.toString().orEmpty()

        fun last(text: String?) = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty()

        if (atLeast <= 0 || ((last(tasks.snapshot().state(StateNames.IDS))[name] as? JsonPrimitive)?.longOrNull ?: 0L) >= atLeast) return
        onBoard {
            tasks.locked(LOCK_TIMEOUT) { s ->
                val ids = last(s.state(StateNames.IDS))
                if (((ids[name] as? JsonPrimitive)?.longOrNull ?: 0L) < atLeast) {
                    s.commit(Commit(state = mapOf(StateNames.IDS to JsonObject(ids + (name to JsonPrimitive(atLeast))).toString())))
                }
            }
        }
    }

    /** Raise the counter for [parent] (null: top level) to the highest number in use, every prefix counted. */
    fun seed(
        store: Store,
        parent: TaskId?,
    ): UInt {
        val depth = parent?.path?.size ?: 0
        val max = adapter.list(Filter(all = true, parent = parent)).mapNotNull { it.id.path.getOrNull(depth) }.maxOrNull() ?: 0u
        return store.seedTaskCounter(key, parent, max)
    }

    fun get(id: TaskId): Task = onBoard { engine().get(id.lib()).value.rtok() }

    /**
     * `status <id> [<status>]`: set it when given, else read it. A task another live agent holds moves only with
     * [force] or for that agent ([actor], by default whoever rtok resolves as the caller).
     */
    fun status(
        store: Store,
        id: TaskId,
        status: Status?,
        force: Boolean,
        report: String?,
        actor: String? = runCatching { Claim.caller(store, null)?.id }.getOrNull(),
    ): Task {
        if (report != null && (status == null || status.isActive)) bail("rtok task: a report goes with the status done or closed")
        if (status == null) return get(id)
        val task =
            onBoard {
                engine(store)
                    .move(id.lib(), status.lib(), report, force, Write(actor = actor))
                    .task.value
                    .rtok()
            }
        if (!status.isActive) store.clearTaskClaim(key, id.toString())
        return task
    }

    fun show(id: TaskId): Shown =
        onBoard {
            val shown = engine().show(id.lib())
            Shown(shown.task.value.rtok(), shown.subtasks.map { it.rtok() })
        }

    /** Tasks that can be claimed, highest priority first, then by id. A read: it takes no lock and creates nothing. */
    fun ready(store: Store): List<ReadyItem> {
        val cards =
            onBoard { engine(store).snapshot(LibFilter(ready = true)) }
                .columns
                .flatMap { it.cards }
                .associateBy { it.id.rtok() }
        val all = listAll()
        return all
            .filter { it.id in cards }
            .map { ReadyItem(it, cards.getValue(it.id).stale, conflictingInProgress(it, all)) }
            .sortedWith(compareBy<ReadyItem> { it.task.priority }.thenBy { it.task.id })
    }

    /** The first ready task. */
    fun next(store: Store): Task? = ready(store).firstOrNull()?.task

    internal fun listAll() =
        tasks
            .snapshot()
            .tasks()
            .map { it.value.rtok() }
            .sortedBy { it.id }

    /** A store error counts as not stale, so a live claim is not taken because the lookup failed. */
    internal fun holderStale(
        store: Store,
        agent: String,
    ) = Ready.holderIsStale(runCatching { store.agentLastSeen(agent) }, nowSecs())
}

private fun conflictingInProgress(
    task: Task,
    tasks: List<Task>,
) = tasks
    .filter { it.status == Status.InProgress && it.id != task.id }
    .filter { task.id in it.conflictsWith || it.id in task.conflictsWith }
    .map { it.id }
    .sorted()

/**
 * T952.2: close [task] in the project of [main] when its store has it active, after the worktree's PR merged. Null for
 * an id it does not know or one already closed.
 */
fun closeIfActive(
    store: Store,
    cfg: Tasks,
    main: String,
    task: String,
): String? {
    val id = runCatching { TaskId.parse(task) }.getOrNull()
    val project = id?.let { TaskProject.open(cfg, main) }
    val t = id?.let { project?.adapter?.get(it) }?.takeIf { it.status.isActive } ?: return null
    // The merge finished the holder's work, so it closes on the holder's behalf.
    project?.status(store, t.id, Status.Done, false, null, actor = t.assignee)
    return t.id.toString()
}
