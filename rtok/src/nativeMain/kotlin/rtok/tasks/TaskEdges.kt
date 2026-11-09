// The block, conflict, parallel and priority edits, as board updates: each one a field merge, so two agents editing
// different fields of one task never overwrite each other.
package rtok.tasks

import pyrlyn.taskboard.Op
import pyrlyn.taskboard.Patch
import rtok.store.TaskId

/** [id] waits on [blocker]. A self-edge, a missing blocker or a cycle writes nothing; an existing edge is kept. */
fun TaskProject.block(
    id: TaskId,
    blocker: TaskId,
): Task {
    if (id == blocker) bail("$id cannot depend on itself")
    return onBoard {
        val board = engine()
        board.get(blocker.lib())
        board
            .update(id.lib(), Patch(addBlockedBy = setOf(blocker.lib())))
            .task.value
            .rtok()
    }
}

/**
 * [id] and [other] touch the same files ([remove]: they no longer do). Both tasks are written in one batch; an edge
 * already as asked is left alone. `remove` still clears [id]'s side of an edge whose peer is gone.
 */
fun TaskProject.conflict(
    id: TaskId,
    other: TaskId,
    remove: Boolean,
): Task {
    if (id == other) bail("$id cannot conflict with itself")
    needMarkers()
    val mine = if (remove) Patch(removeConflictsWith = setOf(other.lib())) else Patch(addConflictsWith = setOf(other.lib()))
    val theirs = if (remove) Patch(removeConflictsWith = setOf(id.lib())) else Patch(addConflictsWith = setOf(id.lib()))
    return onBoard {
        val board = engine()
        val peerGone = listAll().none { it.id == other }
        when {
            peerGone && remove -> {
                board
                    .update(id.lib(), mine)
                    .task.value
                    .rtok()
            }

            peerGone -> {
                bail("no task $other")
            }

            else -> {
                board
                    .batch(listOf(Op.Update(id.lib(), mine), Op.Update(other.lib(), theirs)))
                    .results[0]
                    .task.value
                    .rtok()
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
    return onBoard {
        engine()
            .update(id.lib(), Patch(parallel = on))
            .task.value
            .rtok()
    }
}

private fun TaskProject.needMarkers() {
    if (!adapter.storesMarkers()) bail("${adapter.name} tasks cannot store conflict or parallel markers yet: use the board layout")
}

/** `0` is highest, [PRIORITY_MAX] lowest. The same level is not rewritten. */
fun TaskProject.setPriority(
    id: TaskId,
    level: Int,
): Task {
    if (level !in 0..PRIORITY_MAX) bail("priority $level is outside 0–$PRIORITY_MAX")
    return onBoard {
        engine()
            .update(id.lib(), Patch(priority = level))
            .task.value
            .rtok()
    }
}
