// Port of src/tasks/ready.rs: when a claim's holder is gone. Which tasks are ready, and the cycle check, are the
// kotlin-taskboard engine's.
package rtok.tasks

object Ready {
    /** [seen] is the assignee's `agents.last_seen`; no row means gone, a store error is not stale. */
    fun holderIsStale(
        seen: Result<Long?>,
        now: Long,
    ): Boolean = seen.fold({ ts -> ts == null || now - ts > STALE_SECS }, { false })
}
