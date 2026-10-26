// Port of crates/rtok-store/src/worktree_claims.rs (T285): the agent each worktree is bound to.
// The git lock reason is the source of truth; these rows are the fast join `worktree list` uses.
package rtok.store

import rtok.sys.samePath
import kotlin.time.Duration

/** T527: a claim's holder and the two `agents` facts that say whether the claim still holds. */
data class Holder(val agent: String, val lastSeen: Long, val endedAt: Long?) {
    fun holds(now: Long, staleLock: Duration): Boolean = endedAt == null && seenAgo(now) <= staleLock.inWholeSeconds
    fun seenAgo(now: Long): Long { val r = now - lastSeen; return if (((now xor r) and (lastSeen.inv() xor r)) < 0) (if (now >= 0) Long.MAX_VALUE else Long.MIN_VALUE) else r }
}

fun Store.worktreeClaimHolder(agent: String): Holder? = conn {
    it.queryOne("SELECT id, last_seen, ended_at FROM agents WHERE id = ?", agent) { r -> Holder(r.string(0), r.long(1), r.longOrNull(2)) }
}

/** Bind `path` to `agentId`, replacing any earlier claim on the same path. */
fun Store.claimWorktree(path: String, agentId: String, task: String) = conn {
    it.execute("INSERT INTO worktree_claims (path, agent_id, task) VALUES (?, ?, ?) ON CONFLICT (path) DO UPDATE SET agent_id = ?, task = ?, " +
        "claimed_at = unixepoch(), released_at = NULL, finished_at = NULL", path, agentId, task, agentId, task); Unit
}

private fun Store.openClaimPaths(path: String): List<String> = conn { db ->
    db.query("SELECT path FROM worktree_claims WHERE released_at IS NULL") { it.string(0) }.filter { samePath(it, path) }
}

/** `rtok worktree finish` (T952.2); `false` when `path` has no open claim. */
fun Store.finishWorktreeClaim(path: String): Boolean {
    val hit = openClaimPaths(path); if (hit.isEmpty()) return false
    return conn { it.execute("UPDATE worktree_claims SET finished_at = unixepoch() WHERE path IN (${inList(hit.size)}) AND released_at IS NULL", *hit.toTypedArray()) > 0 }
}

fun Store.finishedWorktreeClaims(): List<Pair<String, String>> = conn {
    it.query("SELECT path, task FROM worktree_claims WHERE released_at IS NULL AND finished_at IS NOT NULL") { r -> r.string(0) to r.string(1) }
}

/** `rtok worktree remove` (T286): the open claim on `path`, if any, is released (and its parked row dropped). */
fun Store.releaseWorktreeClaim(path: String) {
    takePendingWorktree(path)
    val hit = openClaimPaths(path); if (hit.isEmpty()) return
    conn { it.execute("UPDATE worktree_claims SET released_at = unixepoch() WHERE path IN (${inList(hit.size)}) AND released_at IS NULL", *hit.toTypedArray()) }
}

/** `rtok worktree migrate` (T660.5): claim and parked rows of `from` now name `to`; rows already at `to` give way. */
fun Store.moveWorktreeRows(from: String, to: String) = conn { db ->
    db.transaction("IMMEDIATE") {
        val claimed = db.query("SELECT path FROM worktree_claims") { it.string(0) }
        val parked = db.query("SELECT path FROM worktree_pending") { it.string(0) }
        fun at(rows: List<String>, want: String) = rows.filter { samePath(it, want) }
        fun run(sql: String, pre: List<Any?>, rows: List<String>) { if (rows.isNotEmpty()) db.execute(sql.replace("#", inList(rows.size)), *(pre + rows).toTypedArray()) }
        run("DELETE FROM worktree_claims WHERE path IN (#)", emptyList(), at(claimed, to))
        run("DELETE FROM worktree_pending WHERE path IN (#)", emptyList(), at(parked, to))
        run("UPDATE worktree_claims SET path = ? WHERE path IN (#)", listOf(to), at(claimed, from))
        run("UPDATE worktree_pending SET path = ? WHERE path IN (#)", listOf(to), at(parked, from))
    }
}

fun Store.openWorktreeClaims(): List<Pair<String, String>> = conn {
    it.query("SELECT path, agent_id FROM worktree_claims WHERE released_at IS NULL") { r -> r.string(0) to r.string(1) }
}

/** The model of the newest call a host session made (`worktree add`'s default `--owner`). */
fun Store.sessionModel(session: String): String? = conn {
    it.stringOf("SELECT models.slug FROM calls INNER JOIN models ON models.id = calls.model_id WHERE calls.session_id = ? ORDER BY calls.ts DESC LIMIT 1", session)
}
