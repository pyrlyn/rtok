// Port of the block, conflict, parallel and priority edits of src/tasks/run.rs `Project`.
package rtok.tasks

import rtok.store.TaskId

/** [id] waits on [blocker]. A self-edge, a missing blocker or a cycle writes nothing; an existing edge is kept. */
fun TaskProject.block(
    id: TaskId,
    blocker: TaskId,
): Task {
    if (id == blocker) bail("$id cannot depend on itself")
    return adapter.claimGuard().use {
        val task = get(id)
        if (adapter.get(blocker) == null) bail("no task $blocker")
        if (blocker in task.blockedBy) return@use task
        val edged = task.copy(blockedBy = (task.blockedBy + blocker).sorted())
        val all = listAll()
        val graph = if (all.any { it.id == id }) all.map { if (it.id == id) edged else it } else all + edged
        Ready.findCycles(graph).firstOrNull()?.let { cycle -> bail("$id would cycle: ${cycle.joinToString(" -> ")}") }
        adapter.save(edged)
    }
}

/**
 * [id] and [other] touch the same files ([remove]: they no longer do). Both tasks are written; an edge already as
 * asked is left alone. `remove` still clears [id]'s side of an edge whose peer is gone.
 */
fun TaskProject.conflict(
    id: TaskId,
    other: TaskId,
    remove: Boolean,
): Task {
    if (id == other) bail("$id cannot conflict with itself")
    needMarkers()
    return adapter.claimGuard().use {
        val task = get(id)
        val peer = adapter.get(other)
        when {
            peer == null && remove -> {
                if (other in task.conflictsWith) adapter.save(task.copy(conflictsWith = task.conflictsWith - other)) else task
            }

            peer == null -> {
                bail("no task $other")
            }

            else -> {
                editConflict(peer, id, remove)?.let { adapter.save(it) }
                editConflict(task, other, remove)?.let { adapter.save(it) } ?: task
            }
        }
    }
}

/** Mark [id] safe to run beside any other task, or ([on] false) take the mark off. */
fun TaskProject.setParallel(
    id: TaskId,
    on: Boolean,
): Task {
    needMarkers()
    return adapter.claimGuard().use {
        val task = get(id)
        if (task.parallel == on) task else adapter.save(task.copy(parallel = on))
    }
}

private fun TaskProject.needMarkers() {
    if (!adapter.storesMarkers()) bail("${adapter.name} tasks cannot store conflict or parallel markers yet: use the disk adapter")
}

/** `0` is highest, [PRIORITY_MAX] lowest. The same level is not rewritten. */
fun TaskProject.setPriority(
    id: TaskId,
    level: Int,
): Task {
    if (level !in 0..PRIORITY_MAX) bail("priority $level is outside 0–$PRIORITY_MAX")
    return adapter.claimGuard().use {
        val task = get(id)
        if (task.priority == level) task else adapter.save(task.copy(priority = level))
    }
}

/** [t] with [with] added to (or, [remove], taken from) its conflicts; null when it is already as asked. */
private fun editConflict(
    t: Task,
    with: TaskId,
    remove: Boolean,
): Task? {
    val has = with in t.conflictsWith
    return when {
        remove && has -> t.copy(conflictsWith = t.conflictsWith - with)
        !remove && !has -> t.copy(conflictsWith = (t.conflictsWith + with).sorted())
        else -> null
    }
}
