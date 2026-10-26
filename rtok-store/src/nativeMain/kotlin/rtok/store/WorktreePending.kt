// Port of crates/rtok-store/src/worktree_pending.rs (T289.5): worktrees a host's post-create
// script adopted while no single agent could be named; the first agent to adopt one takes it.
package rtok.store

import rtok.sys.samePath

fun Store.addPendingWorktree(path: String, task: String) = conn {
    it.execute("INSERT INTO worktree_pending (path, task) VALUES (?, ?) ON CONFLICT (path) DO UPDATE SET task = excluded.task", path, task); Unit
}

fun Store.pendingWorktrees(): List<Pair<String, String>> = conn { it.query("SELECT path, task FROM worktree_pending") { r -> r.string(0) to r.string(1) } }


/** `true` when this call removed the row, so of two racing sessions only one wins. Matched by [samePath]. */
fun Store.takePendingWorktree(path: String): Boolean = conn { db ->
    val hit = db.query("SELECT path FROM worktree_pending") { it.string(0) }.filter { samePath(it, path) }
    if (hit.isEmpty()) false else db.execute("DELETE FROM worktree_pending WHERE path IN (${inList(hit.size)})", *hit.toTypedArray()) > 0
}
