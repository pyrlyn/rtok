// Claim and release: a claim is a lease on the board, renewed by claiming again.
package rtok.tasks

import rtok.store.Store
import rtok.store.TaskId
import rtok.store.clearTaskClaim
import rtok.store.upsertTaskClaim

/** Claim [id], or the first ready task. One this agent already holds comes back with `changed: false`, its lease renewed. */
fun TaskProject.claim(
    store: Store,
    id: TaskId?,
    agent: String,
): ClaimOutcome {
    val claimed = onBoard { engine(store).claim(id?.lib(), agent) } ?: throw TaskException("no ready task")
    val task = claimed.task.value.rtok()
    // The prompt hook names the agent's current task from this row, without opening the board on every prompt.
    store.upsertTaskClaim(key, task.id.toString(), agent, task.title)
    return ClaimOutcome(task, claimed.changed)
}

/** Clear the assignee and set an in-progress task back to open. Only the holder, unless [force] or its lease lapsed. */
fun TaskProject.release(
    store: Store,
    id: TaskId,
    agent: String,
    force: Boolean,
): Task {
    val task =
        try {
            onBoard {
                engine(store)
                    .release(id.lib(), agent, force)
                    .task.value
                    .rtok()
            }
        } catch (e: ClaimConflict.Already) {
            bail("${e.message}; pass --force")
        }
    store.clearTaskClaim(key, id.toString())
    return task
}
