// Port of crates/rtok-store/src/note_files.rs (T374): the files a note is about.
package rtok.store

import rtok.plugin.sdk.NoteHit

private const val MAX_READ_KEYS = 512

/** Replace the files `noteId` is linked to with `paths` (root-relative). */
fun Store.setNoteFiles(
    noteId: Int,
    paths: List<String>,
) = conn { db ->
    db.transaction {
        db.execute("DELETE FROM note_files WHERE note_id = ?", noteId)
        for (p in paths) db.execute("INSERT OR IGNORE INTO note_files (note_id, path) VALUES (?, ?)", noteId, p)
    }
}

/** Live notes linked to any of `paths`: most-linked first, then newest. */
fun Store.notesForFiles(
    project: String?,
    paths: List<String>,
    limit: UInt,
): List<NoteHit> {
    if (paths.isEmpty()) return emptyList()
    val rows =
        conn { db ->
            var sql =
                "SELECT notes.id, notes.title, substr(notes.body, 1, 120) FROM note_files INNER JOIN notes ON notes.id = note_files.note_id " +
                    "WHERE note_files.path IN (${inList(paths.size)}) AND notes.retired IS NULL"
            val args = ArrayList<Any?>(paths)
            if (project != null) {
                sql += " AND (notes.project = ? OR notes.project IS NULL)"
                args += project
            }
            db.query(sql, *args.toTypedArray()) { NoteHit(it.int(0), it.string(1), it.string(2)) }
        }
    val linked = LinkedHashMap<Int, Pair<Int, NoteHit>>()
    for (h in rows) {
        val cur = linked[h.id]
        linked[h.id] = ((cur?.first ?: 0) + 1) to (cur?.second ?: h)
    }
    return linked.values
        .sortedWith { a, b -> if (a.first != b.first) b.first.compareTo(a.first) else b.second.id.compareTo(a.second.id) }
        .take(maxOf(limit, 1u).toInt())
        .map { it.second }
}

/** Raw read-cache keys of `session`, path order (capped). */
fun Store.readCacheKeys(session: String): List<String> =
    conn {
        it.query("SELECT path FROM read_cache WHERE session = ? ORDER BY path ASC LIMIT ?", session, MAX_READ_KEYS) { r -> r.string(0) }
    }
