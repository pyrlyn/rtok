// Port of crates/rtok-store/src/host_usage.rs: read-only access to a host's own session database
// (OpenCode and Kilo). Not rtok's store: one connection, opened from the caller's `mode=ro` URI.
package rtok.store

import rtok.db.*

/** `(session_id, time_created_ms, data)` with `time_created >= sinceMs`, oldest first. */
fun readHostMessages(
    sqliteUri: String,
    sinceMs: Long,
): List<Triple<String, Long, String>> =
    Db.open(sqliteUri).use { db ->
        db.query("SELECT session_id, time_created, data FROM message WHERE time_created >= ? ORDER BY time_created ASC", sinceMs) { Triple(it.string(0), it.long(1), it.string(2)) }
    }

/** A fixture database with the one table [readHostMessages] reads. */
fun seedHostMessages(
    path: String,
    rows: List<Triple<String, Long, String>>,
) = Db.open(path).use { db ->
    db.exec("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, time_created INTEGER NOT NULL, data TEXT NOT NULL)")
    rows.forEachIndexed { i, (s, c, d) -> db.execute("INSERT INTO message (id, session_id, time_created, data) VALUES (?, ?, ?, ?)", "m$i", s, c, d) }
}
