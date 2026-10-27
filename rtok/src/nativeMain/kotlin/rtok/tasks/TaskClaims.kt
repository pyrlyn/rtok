// Port of the claim and release operations of src/tasks/run.rs `Project`.
package rtok.tasks

import rtok.store.Store
import rtok.store.TaskId
import rtok.store.clearTaskClaim
import rtok.store.upsertTaskClaim

/** Claim [id], or the first ready task. One this agent already holds comes back with `changed: false`. */
fun TaskProject.claim(
    store: Store,
    id: TaskId?,
    agent: String,
): ClaimOutcome =
    adapter.claimGuard().use {
        if (id != null) return@use claimHeld(store, id, agent)
        var last: ClaimConflict? = null
        for (item in readyUnlocked(store)) {
            try {
                return@use claimHeld(store, item.task.id, agent)
            } catch (e: ClaimConflict) {
                last = e
            }
        }
        throw last ?: TaskException("no ready task")
    }

private fun TaskProject.claimHeld(
    store: Store,
    id: TaskId,
    agent: String,
): ClaimOutcome {
    val task = get(id)
    if (task.assignee == agent && task.status == Status.InProgress) {
        store.upsertTaskClaim(key, id.toString(), agent, task.title)
        return ClaimOutcome(task, changed = false)
    }
    refusal(store, task, agent)?.let { throw it }
    val saved = adapter.save(task.copy(assignee = agent, status = Status.InProgress))
    store.upsertTaskClaim(key, saved.id.toString(), agent, saved.title)
    return ClaimOutcome(saved, changed = true)
}

/** Clear the assignee and set an in-progress task back to open. Only the holder, unless [force]. */
fun TaskProject.release(
    store: Store,
    id: TaskId,
    agent: String,
    force: Boolean,
): Task =
    adapter.claimGuard().use {
        val task = get(id)
        if (!task.status.isActive) bail("$id is ${task.status} and cannot be released")
        val holder = task.assignee?.takeIf { it.isNotEmpty() }
        if (holder != null && holder != agent && !force) bail("$id is claimed by $holder; pass --force")
        val changed = task.assignee != null || task.status != Status.Open
        val open = task.copy(assignee = null, status = if (task.status == Status.InProgress) Status.Open else task.status)
        val out = if (changed) adapter.save(open) else open
        store.clearTaskClaim(key, id.toString())
        out
    }

/** Why [agent] cannot take [task], or null when it can. */
private fun TaskProject.refusal(
    store: Store,
    task: Task,
    agent: String,
): ClaimConflict? {
    val holder = task.assignee?.takeIf { it.isNotEmpty() }
    return when {
        !task.status.isActive -> {
            ClaimConflict.NotClaimable(task.id, task.status)
        }

        else -> {
            Ready.activeBlocker(task, listAll())?.let { ClaimConflict.Blocked(task.id, it) }
                ?: if (holder != null && holder != agent && !holderStale(store, holder)) ClaimConflict.Already(task.id, holder) else null
        }
    }
}
