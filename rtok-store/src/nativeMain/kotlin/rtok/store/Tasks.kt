// Port of crates/rtok-store/src/task_claims.rs and task_counters.rs.
package rtok.store

import rtok.db.*

data class TaskClaim(val project: String, val taskId: String, val agentId: String, val title: String, val since: Long)

fun Store.upsertTaskClaim(project: String, taskId: String, agent: String, title: String) {
    val since = unixNow()
    conn { db ->
        db.transaction("IMMEDIATE") {
            val seq = (db.longOf("SELECT max(seq) FROM task_claims") ?: 0) + 1
            db.execute("INSERT INTO task_claims (project, task_id, agent_id, title, since, seq) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project, task_id) DO UPDATE SET agent_id = ?, title = ?, since = ?, seq = ?", project, taskId, agent, title, since, seq, agent, title, since, seq)
        }
    }
}

fun Store.clearTaskClaim(project: String, taskId: String) = conn { it.execute("DELETE FROM task_claims WHERE project = ? AND task_id = ?", project, taskId); Unit }

fun Store.latestTaskClaim(project: String, agent: String): TaskClaim? = conn { db ->
    db.queryOne("SELECT project, task_id, agent_id, title, since FROM task_claims WHERE project = ? AND agent_id = ? ORDER BY seq DESC LIMIT 1", project, agent) {
        TaskClaim(it.string(0), it.string(1), it.string(2), it.string(3), it.long(4))
    }
}

private fun counterKey(parent: TaskId?) = parent?.path?.joinToString(".") ?: ""
private fun readLast(db: Db, project: String, key: String) = db.longOf("SELECT last FROM task_counters WHERE project = ? AND parent = ?", project, key) ?: 0
private fun writeLast(db: Db, project: String, key: String, last: Long) =
    db.execute("INSERT INTO task_counters (project, parent, last) VALUES (?, ?, ?) ON CONFLICT (project, parent) DO UPDATE SET last = ?", project, key, last, last)

private fun <T> lockedHint(body: () -> T): T = try { body() } catch (e: Throwable) {
    if (isLocked(e)) throw StoreException("task id allocation: another rtok process held the store's write lock past its busy timeout; retry: ${e.message}", e) else throw e
}

private fun toU32(n: Long): UInt = if (n < 0 || n > UInt.MAX_VALUE.toLong()) throw StoreException("task counter overflow") else n.toUInt()

/** Next id under `parent` (or top level) in `project`; numbering is shared by every prefix. */
fun Store.allocateTaskId(project: String, prefix: String, parent: TaskId?): TaskId {
    checkPrefix(prefix)
    parent?.child(1u)
    val key = counterKey(parent)
    val n = lockedHint {
        conn { db ->
            db.transaction("EXCLUSIVE") {
                if (parent != null) {
                    val top = readLast(db, project, "")
                    if (parent.path[0].toLong() > top) throw StoreException("parent $parent was never allocated in this project (last is $top)")
                }
                val next = readLast(db, project, key) + 1
                writeLast(db, project, key, next); next
            }
        }
    }
    val u = toU32(n)
    return parent?.child(u) ?: TaskId.new(prefix, u)
}

fun Store.taskCounter(project: String, parent: TaskId?): UInt = toU32(conn { readLast(it, project, counterKey(parent)) })

/** Raise the counter to at least `atLeast`; never lowers it. */
fun Store.seedTaskCounter(project: String, parent: TaskId?, atLeast: UInt): UInt {
    val key = counterKey(parent)
    return toU32(lockedHint { conn { db -> db.transaction("EXCLUSIVE") { val last = maxOf(readLast(db, project, key), atLeast.toLong()); writeLast(db, project, key, last); last } } })
}
