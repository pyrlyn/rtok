// Port of the `Project` of src/tasks/run.rs: one project's tasks, the counter they are numbered from and the prefix
// new ids get, and the claim, release, block, conflict, parallel and priority operations over its adapter.
package rtok.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import rtok.config.ProjectRoot
import rtok.config.Tasks
import rtok.config.TasksDisk
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.agentLastSeen
import rtok.store.allocateTaskId
import rtok.store.clearTaskClaim
import rtok.store.seedTaskCounter

/** Creates tried when the id keeps turning out taken, before the last error is the answer. */
private const val TAKEN_RETRIES = 3

/** `rtok task show`: the task and the ids of its subtasks. */
data class Shown(
    val task: Task,
    val subtasks: List<TaskId>,
) {
    fun toJson() = JsonObject(TASK_JSON.encodeToJsonElement(task).jsonObject + ("subtasks" to JsonArray(subtasks.map { JsonPrimitive(it.toString()) })))
}

/** The disk adapter for `[tasks.disk]`: `auto` follows what `dir` holds; an empty or new directory gets files. */
fun diskAdapter(
    cfg: TasksDisk,
    root: String,
): TaskAdapter {
    val dir = "$root/${cfg.dir}"
    val folders =
        when (cfg.layout) {
            "folders" -> true
            "files" -> false
            else -> PlanDirAdapter.holdsFolders(dir)
        }
    return if (folders) PlanDirAdapter(dir) else DiskAdapter(dir)
}

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
    val adapter: TaskAdapter,
) {
    companion object {
        /** The project [cwd] belongs to, with the adapter `[tasks]` names. */
        fun open(
            cfg: Tasks,
            cwd: String,
        ): TaskProject {
            val root = checkoutOf(cwd, "rtok task")
            val key = rtok.projectKey(cwd) ?: bail("rtok task: cannot tell which project this checkout is")
            val prefix = resolvePrefix(cfg.prefix, rtok.projectName(cwd))
            val adapter =
                when (cfg.adapter) {
                    "disk" -> diskAdapter(cfg.disk, root)
                    "github", "gitlab" -> bail("rtok task: the ${cfg.adapter} adapter is not ported yet")
                    else -> bail("rtok task: unknown [tasks] adapter \"${cfg.adapter}\"")
                }
            return TaskProject(key, root, prefix, adapter)
        }
    }

    /** Store a new task under the next free id, the counter first raised past every id the adapter holds. */
    fun create(
        store: Store,
        new: NewTask,
    ): Task {
        val parent = new.parent
        if (parent != null && adapter.get(parent) == null) bail("rtok task: no parent task $parent")
        seed(store, parent)
        repeat(TAKEN_RETRIES - 1) {
            val id = store.allocateTaskId(key, prefix, parent)
            try {
                return adapter.create(new, id)
            } catch (_: Taken) {
                // Another machine's counter got there between the seed and the create; seeding again jumps past it.
                seed(store, parent)
            }
        }
        return adapter.create(new, store.allocateTaskId(key, prefix, parent))
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

    fun get(id: TaskId): Task = adapter.get(id) ?: bail("no task $id")

    /** `status <id> [<status>]`: set it when given, else read it. Finishing drops the claim row. */
    fun status(
        store: Store,
        id: TaskId,
        status: Status?,
        force: Boolean,
        report: String?,
    ): Task {
        if (report != null && (status == null || status.isActive)) bail("rtok task: a report goes with the status done or closed")
        if (status == null) return get(id)
        val task = setStatus(adapter, id, status, force)
        report?.let { adapter.writeReport(id, it) }
        if (!status.isActive) store.clearTaskClaim(key, id.toString())
        return task
    }

    fun show(id: TaskId) = Shown(get(id), adapter.list(Filter(all = true, parent = id)).map { it.id })

    /** Tasks that can be claimed, highest priority first, then by id. */
    fun ready(store: Store): List<ReadyItem> = adapter.claimGuard().use { readyUnlocked(store) }

    internal fun readyUnlocked(store: Store): List<ReadyItem> = Ready.select(listAll()) { holderStale(store, it) }

    /** The first ready task. */
    fun next(store: Store): Task? = ready(store).firstOrNull()?.task

    internal fun listAll() = adapter.list(Filter(all = true))

    /** A store error counts as not stale, so a live claim is not taken because the lookup failed. */
    internal fun holderStale(
        store: Store,
        agent: String,
    ) = Ready.holderIsStale(runCatching { store.agentLastSeen(agent) }, nowSecs())
}

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
    project?.status(store, t.id, Status.Done, false, null)
    return t.id.toString()
}
