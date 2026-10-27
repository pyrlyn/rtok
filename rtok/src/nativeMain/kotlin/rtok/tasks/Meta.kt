// Port of src/tasks/meta.rs: claim, dependency, priority and marker labels the GitHub and GitLab adapters share.
package rtok.tasks

import rtok.store.TaskId

object Meta {
    const val OWNER_PREFIX = "rtok:owner:"
    const val NEEDS_PREFIX = "rtok:needs:"
    const val PRIORITY_PREFIX = "rtok:p:"
    const val CONFLICTS_PREFIX = "rtok:conflicts:"

    /** Exact label, not a prefix: it carries no value. */
    const val PARALLEL = "rtok:parallel"

    /** What the managed labels of an issue say about its task. */
    data class Labels(
        val priority: Int,
        val assignee: String?,
        val blockedBy: List<TaskId>,
        val conflictsWith: List<TaskId>,
        val parallel: Boolean,
    )

    /** A label `save` owns: the assignee, a blocker, a non-default priority, or a marker. */
    fun isManaged(label: String) =
        label.startsWith(OWNER_PREFIX) || label.startsWith(NEEDS_PREFIX) || label.startsWith(PRIORITY_PREFIX) ||
            label.startsWith(CONFLICTS_PREFIX) || label == PARALLEL

    private fun idOrNull(s: String) = runCatching { TaskId.parse(s) }.getOrNull()

    /** The meta carried by [labels]. Unknown or out-of-range values are dropped. */
    fun read(labels: List<String>): Labels {
        var priority = DEFAULT_PRIORITY
        var assignee: String? = null
        val blockedBy = mutableListOf<TaskId>()
        val conflictsWith = mutableListOf<TaskId>()
        var parallel = false
        for (label in labels) {
            when {
                label.startsWith(OWNER_PREFIX) -> {
                    val rest = label.removePrefix(OWNER_PREFIX)
                    if (assignee == null && rest.isNotEmpty()) assignee = rest
                }

                label.startsWith(NEEDS_PREFIX) -> {
                    idOrNull(label.removePrefix(NEEDS_PREFIX))?.let(blockedBy::add)
                }

                label.startsWith(PRIORITY_PREFIX) -> {
                    label
                        .removePrefix(PRIORITY_PREFIX)
                        .toUByteOrNull()
                        ?.toInt()
                        ?.takeIf { it <= PRIORITY_MAX }
                        ?.let { priority = it }
                }

                label.startsWith(CONFLICTS_PREFIX) -> {
                    idOrNull(label.removePrefix(CONFLICTS_PREFIX))?.let(conflictsWith::add)
                }

                label == PARALLEL -> {
                    parallel = true
                }
            }
        }
        return Labels(priority, assignee, blockedBy.distinct().sorted(), conflictsWith.distinct().sorted(), parallel)
    }

    /** The managed labels [task] should carry. Priority `2` adds none. */
    fun managedFor(task: Task): List<String> =
        buildList {
            task.assignee?.takeIf { it.isNotEmpty() }?.let { add(OWNER_PREFIX + it) }
            task.blockedBy.forEach { add("$NEEDS_PREFIX$it") }
            if (task.priority != DEFAULT_PRIORITY) add("$PRIORITY_PREFIX${task.priority}")
            task.conflictsWith.forEach { add("$CONFLICTS_PREFIX$it") }
            if (task.parallel) add(PARALLEL)
        }

    /** [existing] with its managed labels replaced by [managedFor]; other labels keep their order. */
    fun mergeLabels(
        existing: List<String>,
        task: Task,
    ) = existing.filterNot(::isManaged) + managedFor(task)
}
