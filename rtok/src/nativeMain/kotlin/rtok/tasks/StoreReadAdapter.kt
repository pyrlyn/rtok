// The board layout's tasks for the callers that read through a TaskAdapter (list, export, sync, the web page).
package rtok.tasks

import rtok.store.TaskId
import pyrlyn.taskboard.Store as TaskStore

private const val WRITES = "board tasks are written through the board, not the adapter"

/** Reads [store]; writes go through [TaskProject.engine], so this adapter refuses them. */
internal class StoreReadAdapter(
    private val store: TaskStore,
) : TaskAdapter {
    override val name = "board"

    private fun all(): List<Task> {
        val read = store.snapshot()
        return read.tasks().map { it.value.rtok() }.also { read.warnings().forEach(::warnOnce) }
    }

    override fun list(filter: Filter) = filter.select(all())

    override fun get(id: TaskId) = all().firstOrNull { it.id == id }

    override fun maxId(prefix: String): TaskId? = all().map { it.id }.filter { it.prefix == prefix.uppercase() }.maxOrNull()

    override fun movesWithReport() = true

    override fun storesMarkers() = true

    override fun create(
        task: NewTask,
        id: TaskId,
    ): Task = bail(WRITES)

    override fun writeStatus(
        id: TaskId,
        status: Status,
        report: String?,
    ): Task = bail(WRITES)
}
