// Port of crates/rtok-store/src/cache.rs: per-session read cache and hook-input windows.
package rtok.store

fun Store.putReadCache(session: String, path: String, sha256: String, archiveId: String?) = conn { db ->
    db.execute("INSERT INTO read_cache (session, path, sha256, archive_id) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (session, path) DO UPDATE SET sha256 = ?, ts = ?, archive_id = ?", session, path, sha256, archiveId, sha256, unixNow(), archiveId)
    Unit
}

fun Store.getReadCache(session: String, path: String): Pair<String?, Long>? = conn { db ->
    db.queryOne("SELECT archive_id, ts FROM read_cache WHERE session = ? AND path = ?", session, path) { it.stringOrNull(0) to it.long(1) }
}

/** A compaction dropped what `context` was shown (T929): forget the read cache and retire its archive pointers. */
fun Store.forgetSeen(session: String, context: String?) = conn { db ->
    db.execute("DELETE FROM read_cache WHERE session = ?", session)
    if (context != null) db.execute("UPDATE archive SET agent_id = ? WHERE session = ? AND agent_id = ?", FORGOTTEN_CONTEXT, session, context)
    else db.execute("UPDATE archive SET agent_id = ? WHERE session = ? AND agent_id IS NULL", FORGOTTEN_CONTEXT, session)
    Unit
}

fun Store.clearReadCache(session: String, path: String) = conn { db ->
    val keyed = "$path\t"
    db.execute("DELETE FROM read_cache WHERE session = ? AND (path = ? OR substr(path, 1, ?) = ?)", session, path, keyed.codePointCountCompat(), keyed)
    Unit
}

private const val HOOK_INPUTS = "SELECT call_io.request_json, hook_sessions.fields FROM calls " +
    "INNER JOIN call_io ON call_io.call_id = calls.id LEFT JOIN hook_sessions ON hook_sessions.id = call_io.hook_session_id "

fun Store.recentHookInputs(session: String, limit: Long): List<String> = conn { db ->
    rebuildHookInputs(db.query(HOOK_INPUTS + "WHERE calls.session_id = ? AND calls.kind = 'hook' ORDER BY calls.id DESC LIMIT ?", session, limit) { it.stringOrNull(0) to it.stringOrNull(1) })
}

fun Store.recentHookInputsForEvent(session: String, event: String, limit: Long): List<String> = conn { db ->
    rebuildHookInputs(db.query(HOOK_INPUTS + "WHERE calls.session_id = ? AND calls.kind = 'hook' AND calls.name = ? ORDER BY calls.id DESC LIMIT ?", session, event, limit) { it.stringOrNull(0) to it.stringOrNull(1) })
}

fun Store.hookInputsSince(event: String, since: Long, limit: Long): List<String> = conn { db ->
    rebuildHookInputs(db.query(HOOK_INPUTS + "WHERE calls.kind = 'hook' AND calls.name = ? AND calls.ts >= ? ORDER BY calls.id DESC LIMIT ?", event, since, limit) { it.stringOrNull(0) to it.stringOrNull(1) })
}

fun Store.callsSince(session: String, ts: Long): Long = conn { db -> db.longOf("SELECT count(*) FROM calls WHERE session_id = ? AND ts >= ?", session, ts) ?: 0 }

/** A missing body stays `""`; a split one is made whole. */
internal fun rebuildHookInputs(rows: List<Pair<String?, String?>>): List<String> = rows.map { (body, fields) -> body?.let { HookFields.rebuild(it, fields) } ?: "" }

internal fun String.codePointCountCompat(): Int { var n = 0; var i = 0; while (i < length) { if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++; i++; n++ }; return n }
