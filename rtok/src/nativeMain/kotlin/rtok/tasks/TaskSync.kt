// Port of src/tasks/sync.rs: `rtok task sync` raises every counter of a project to the highest number its adapter
// holds and reports what was off. Done tasks count too: their numbers were handed out and must not come back.
package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.seedTaskCounter
import rtok.store.taskCounter

/** One counter that was behind the adapter; [parent] null is the top-level counter. */
@Serializable
data class Raised(
    val parent: TaskId?,
    val from: UInt,
    val to: UInt,
)

@Serializable
data class SyncReport(
    val adapter: String,
    /** The top-level counter after the sync: the next id is this plus one. */
    val counter: UInt,
    val next: TaskId,
    val raised: List<Raised>,
    /** Ids numbered above their counter before the sync. */
    @SerialName("above_counter") val aboveCounter: List<TaskId>,
    /** Ids that more than one task claims. */
    val duplicates: List<TaskId>,
    /** Issues that kept rtok's label but lost their `rtok:<id>` one. */
    val unlabelled: List<Stray>,
) {
    val isClean get() = aboveCounter.isEmpty() && duplicates.isEmpty() && unlabelled.isEmpty()
}

private fun number(id: TaskId) = id.path.lastOrNull() ?: 0u

/** Parents in BTreeMap order: the top level first, then by id. */
private val PARENT_ORDER = Comparator<TaskId?> { a, b -> if (a == null || b == null) compareValues(a != null, b != null) else a.compareTo(b) }

fun syncTasks(
    project: TaskProject,
    store: Store,
): SyncReport {
    val tasks = project.adapter.list(Filter(all = true))
    // `list` sorts by id, so a repeated id is next to its twin.
    val duplicates =
        tasks
            .zipWithNext()
            .filter { (a, b) -> a.id == b.id }
            .map { it.first.id }
            .distinct()
    val groups = tasks.groupBy { it.id.parent() }.entries.sortedWith(compareBy(PARENT_ORDER) { it.key })
    val raised = mutableListOf<Raised>()
    val above = mutableListOf<TaskId>()
    for (entry in groups) {
        val parent = entry.key
        val group = entry.value
        val top = group.maxOf { number(it.id) }
        val from = store.taskCounter(project.key, parent)
        if (top > from) {
            store.seedTaskCounter(project.key, parent, top)
            above += group.map { it.id }.filter { number(it) > from }
            raised += Raised(parent, from, top)
        }
    }
    val counter = store.taskCounter(project.key, null)
    return SyncReport(
        adapter = project.adapter.name,
        counter = counter,
        next = TaskId.new(project.prefix, counter + 1u),
        raised = raised,
        aboveCounter = above.zipWithNext().filter { (a, b) -> a != b }.map { it.first } + listOfNotNull(above.lastOrNull()),
        duplicates = duplicates,
        unlabelled = project.adapter.unlabelled(),
    )
}

/** `rtok task sync`'s text: the counter, then one line per kind of drift. */
fun syncText(r: SyncReport): String {
    if (r.isClean && r.raised.isEmpty()) return "${r.adapter}: next id ${r.next}, counters in step\n"
    return buildString {
        append("${r.adapter}: next id ${r.next}\n")
        for (x in r.raised) append("raised ${x.parent?.let { "subtasks of $it" } ?: "top level"}: ${x.from} -> ${x.to}\n")
        if (r.aboveCounter.isNotEmpty()) append("above the counter: ${r.aboveCounter.joinToString(", ")}\n")
        if (r.duplicates.isNotEmpty()) append("duplicate ids: ${r.duplicates.joinToString(", ")}\n")
        for (s in r.unlabelled) append("no id label: ${s.title}${s.id?.let { " (label rtok:$it is gone)" }.orEmpty()} ${s.url}\n")
    }
}
