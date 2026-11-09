// Port of src/tasks/adapter.rs: the task adapter interface and the rules every adapter shares.
package rtok.tasks

import kotlinx.serialization.Serializable
import rtok.store.TaskId

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
    /** `board`, `files`, `plan`, `github` or `gitlab`, for error messages. */
    val name: String

    fun create(
        task: NewTask,
        id: TaskId,
    ): Task

    fun list(filter: Filter): List<Task>

    fun get(id: TaskId): Task?

    /**
     * Callers go through [setStatus], which guards parents. [report] is only stored by an adapter whose
     * [movesWithReport] is true, in the same write as the move.
     */
    fun writeStatus(
        id: TaskId,
        status: Status,
        report: String? = null,
    ): Task

    /** Whether [writeStatus] stores its `report` itself. */
    fun movesWithReport() = false

    /** The highest id with [prefix], done ones included. */
    fun maxId(prefix: String): TaskId?

    fun writeReport(
        id: TaskId,
        report: String,
    ): Unit = bail("$name tasks: a closing report is not stored")

    fun unlabelled(): List<Stray> = emptyList()

    fun save(task: Task): Task = bail("$name tasks: saving a claim is not supported")

    /** Whether `save` keeps `conflicts_with` and `parallel`. */
    fun storesMarkers() = false
}
