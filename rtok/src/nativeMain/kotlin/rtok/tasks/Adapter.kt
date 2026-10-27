// Port of src/tasks/adapter.rs: the task adapter interface and the rules every adapter shares.
package rtok.tasks

import kotlinx.serialization.Serializable
import rtok.store.TaskId

/** Held across a claim, release or save so two disk writers cannot interleave. */
fun interface ClaimLock : AutoCloseable

/** What `list` returns. The default is the plan: every active task, top level and subtasks. */
data class Filter(
    val statuses: List<Status> = emptyList(),
    val all: Boolean = false,
    val parent: TaskId? = null,
) {
    fun matches(task: Task): Boolean {
        val status = if (statuses.isEmpty()) all || task.status.isActive else task.status in statuses
        return status && (parent == null || task.parent == parent)
    }

    /** The matches among [tasks], sorted by id. */
    fun select(tasks: Iterable<Task>) = tasks.filter(::matches).sortedBy { it.id }

    /** Whether finished tasks can match, so an adapter knows to read its archive. */
    val wantsFinished get() = all || statuses.any { !it.isActive }
}

/** `create`'s error when the id is already stored. */
class Taken(
    val id: TaskId,
) : Exception("task $id already exists")

/** A remote issue that carries rtok's label but no id label. */
@Serializable
data class Stray(
    val title: String,
    val url: String,
    val id: TaskId? = null,
)

/** A task storage backend: plain files on disk, GitHub Issues or GitLab Issues. */
interface TaskAdapter {
    /** `disk`, `github` or `gitlab`, for error messages. */
    val name: String

    fun create(
        task: NewTask,
        id: TaskId,
    ): Task

    fun list(filter: Filter): List<Task>

    fun get(id: TaskId): Task?

    /** Callers go through [setStatus], which guards parents. */
    fun writeStatus(
        id: TaskId,
        status: Status,
    ): Task

    /** The highest id with [prefix], done ones included. */
    fun maxId(prefix: String): TaskId?

    fun writeReport(
        id: TaskId,
        report: String,
    ): Unit = bail("$name tasks: a closing report is not stored")

    fun unlabelled(): List<Stray> = emptyList()

    fun claimGuard(): ClaimLock = ClaimLock {}

    fun save(task: Task): Task = bail("$name tasks: saving a claim is not supported")

    /** Whether `save` keeps `conflicts_with` and `parallel`. */
    fun storesMarkers() = false
}

/** Set a task's status. A parent with active subtasks cannot finish unless [force]. */
fun setStatus(
    adapter: TaskAdapter,
    id: TaskId,
    status: Status,
    force: Boolean,
): Task {
    if (!status.isActive && !force) {
        val open = adapter.list(Filter(parent = id))
        if (open.isNotEmpty()) bail("$id has open subtasks: ${open.joinToString(", ") { it.id.toString() }}; finish them first or pass --force")
    }
    return adapter.writeStatus(id, status)
}
