// Port of src/tasks/ready.rs: which tasks can be claimed, and which dependency edges form a cycle.
package rtok.tasks

import rtok.store.TaskId

object Ready {
    /** [seen] is the assignee's `agents.last_seen`; no row means gone, a store error is not stale. */
    fun holderIsStale(
        seen: Result<Long?>,
        now: Long,
    ): Boolean = seen.fold({ ts -> ts == null || now - ts > STALE_SECS }, { false })

    /** [stale] reports whether that assignee can be replaced; only called for a non-empty assignee. */
    fun select(
        tasks: List<Task>,
        stale: (String) -> Boolean,
    ): List<ReadyItem> {
        val anyBlocks = tasks.any { it.blockedBy.isNotEmpty() }
        val active = tasks.filter { it.status.isActive }.map { it.id }.toSet()
        return tasks
            .mapNotNull { task ->
                val holder = task.assignee?.takeIf { it.isNotEmpty() }
                val isStale = holder != null && stale(holder)
                val claimable =
                    when (task.status) {
                        Status.Open -> holder == null || isStale
                        Status.InProgress -> isStale
                        Status.Done, Status.Closed -> false
                    }
                when {
                    !claimable -> null
                    task.blockedBy.any { it in active } -> null
                    !anyBlocks && tasks.any { it.parent == task.id && it.status.isActive } -> null
                    else -> ReadyItem(task, isStale, conflictingInProgress(task, tasks))
                }
            }.sortedWith(compareBy<ReadyItem> { it.task.priority }.thenBy { it.task.id })
    }

    private fun conflictingInProgress(
        task: Task,
        tasks: List<Task>,
    ) = tasks
        .filter { it.status == Status.InProgress && it.id != task.id }
        .filter { task.id in it.conflictsWith || it.id in task.conflictsWith }
        .map { it.id }
        .sorted()

    /** The first active id in `blocked_by`. */
    fun activeBlocker(
        task: Task,
        tasks: List<Task>,
    ): TaskId? {
        val active = tasks.filter { it.status.isActive }.map { it.id }.toSet()
        return task.blockedBy.firstOrNull { it in active }
    }

    /** Cycles in the wait-graph (task → blocker, parent → child), each rotated to its lowest id. */
    fun findCycles(tasks: List<Task>): List<List<TaskId>> {
        val adj = mutableMapOf<TaskId, MutableSet<TaskId>>()

        fun node(id: TaskId) = adj.getOrPut(id) { mutableSetOf() }
        for (task in tasks) {
            node(task.id)
            for (b in task.blockedBy) {
                node(task.id).add(b)
                node(b)
            }
            task.parent?.let {
                node(it).add(task.id)
                node(task.id)
            }
        }
        val color = mutableMapOf<TaskId, Int>()
        val stack = ArrayList<TaskId>()
        val cycles = ArrayList<List<TaskId>>()

        fun dfs(n: TaskId) {
            color[n] = 1
            stack.add(n)
            for (to in adj[n].orEmpty().sorted()) {
                when (color[to] ?: 0) {
                    1 -> stack.indexOf(to).takeIf { it >= 0 }?.let { cycles.add(rotateLowest(stack.subList(it, stack.size).toList())) }
                    0 -> dfs(to)
                }
            }
            stack.removeAt(stack.size - 1)
            color[n] = 2
        }
        for (n in adj.keys.sorted()) if ((color[n] ?: 0) == 0) dfs(n)
        return cycles.distinct().sortedWith(::compareLists)
    }

    private fun compareLists(
        a: List<TaskId>,
        b: List<TaskId>,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) a[i].compareTo(b[i]).let { if (it != 0) return it }
        return a.size.compareTo(b.size)
    }

    private fun rotateLowest(cycle: List<TaskId>): List<TaskId> {
        val c = if (cycle.size >= 2 && cycle.first() == cycle.last()) cycle.dropLast(1) else cycle
        if (c.isEmpty()) return c
        val at = c.indices.minBy { c[it] }
        return c.drop(at) + c.take(at)
    }
}
