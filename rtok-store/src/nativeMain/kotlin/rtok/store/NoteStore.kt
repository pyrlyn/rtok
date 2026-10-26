// Port of crates/rtok-store/src/note_store.rs (kv lives in Kv.kt).
package rtok.store

import rtok.db.*
import rtok.plugin.sdk.NoteHit

private const val NOT_SESSION = "kind NOT LIKE 'checkpoint:%' AND kind NOT LIKE 'session:%'"

private fun projectIs(project: String?) = if (project != null) "project = ?" else "project IS NULL"

private fun args(vararg a: Any?): Array<Any?> = arrayOf(*a)

internal fun recordNoteVersion(
    db: Db,
    noteId: Int,
    title: String,
    body: String,
) {
    val next = (db.longOf("SELECT max(version) FROM note_versions WHERE note_id = ?", noteId) ?: 0) + 1
    db.execute("INSERT INTO note_versions (note_id, title, body, version) VALUES (?, ?, ?, ?)", noteId, title, body, next)
}

private fun daysSince(
    now: Long,
    lastUsed: Long?,
) = lastUsed?.let { ageDays(now, it) }

fun Store.insertNote(
    project: String?,
    kind: String,
    title: String,
    body: String,
): Int =
    conn {
        it.queryOne("INSERT INTO notes (project, kind, title, body) VALUES (?, ?, ?, ?) RETURNING id", project, kind, title, body) { r -> r.int(0) }!!
    }

fun Store.insertNoteIfAbsent(
    project: String?,
    kind: String,
    title: String,
    body: String,
): Int? =
    conn {
        it.queryOne("INSERT OR IGNORE INTO notes (project, kind, title, body) VALUES (?, ?, ?, ?) RETURNING id", project, kind, title, body) { r -> r.int(0) }
    }

/** An imported note keeps its id, ts, retired, superseded_by and pinned when the export had them. */
fun Store.insertPortableNoteIfAbsent(n: PortableNote): Int? {
    val portable = n.id != null || n.ts != null || n.retired != null || n.supersededBy != null || (n.pinned ?: 0) != 0
    if (!portable) return insertNoteIfAbsent(n.project, n.kind, n.title, n.body)
    val cols = ArrayList<String>()
    val vals = ArrayList<Any?>()
    n.id?.let {
        cols += "id"
        vals += it
    }
    n.ts?.let {
        cols += "ts"
        vals += it
    }
    cols += listOf("project", "kind", "title", "body", "retired", "superseded_by", "pinned")
    vals.addAll(listOf(n.project, n.kind, n.title, n.body, n.retired, n.supersededBy, n.pinned ?: 0))
    return conn { it.queryOne("INSERT OR IGNORE INTO notes (${cols.joinToString(", ")}) VALUES (${inList(cols.size)}) RETURNING id", *vals.toTypedArray()) { r -> r.int(0) } }
}

/** Insert or replace the body of `(project, kind, title)`; `(id, existed)`. A changed body keeps its old version. */
fun Store.upsertNote(
    project: String?,
    kind: String,
    title: String,
    body: String,
): Pair<Int, Boolean> =
    conn { db ->
        db.transaction("IMMEDIATE") {
            val existing =
                db.queryOne("SELECT id, title, body FROM notes WHERE kind = ? AND title = ? AND ${projectIs(project)}", *args(kind, title, *listOfNotNull(project).toTypedArray())) {
                    Triple(it.int(0), it.string(1), it.string(2))
                }
            if (existing != null && existing.third != body && keepsNoteVersions(kind)) recordNoteVersion(db, existing.first, existing.second, existing.third)
            val id =
                db.queryOne(
                    "INSERT INTO notes (project, kind, title, body) VALUES (?, ?, ?, ?) ON CONFLICT (COALESCE(project, ''), kind, title) DO UPDATE SET " +
                        "body = excluded.body, ts = unixepoch(), retired = NULL, superseded_by = NULL RETURNING id",
                    project,
                    kind,
                    title,
                    body,
                ) { it.int(0) }!!
            id to (existing != null)
        }
    }

/** `(version, title, body)`, oldest first. */
fun Store.noteVersions(id: Int): List<Triple<Int, String, String>> =
    conn { db ->
        db.query("SELECT version, title, body FROM note_versions WHERE note_id = ? ORDER BY version ASC", id) { Triple(it.int(0), it.string(1), it.string(2)) }
    }

data class ListedNote(
    val project: String?,
    val kind: String,
    val title: String,
    val body: String,
)

fun Store.listNotes(
    project: String?,
    includeRetired: Boolean,
): List<ListedNote> =
    conn { db ->
        var sql = "SELECT project, kind, title, body FROM notes WHERE $NOT_SESSION"
        if (!includeRetired) sql += " AND retired IS NULL"
        if (project != null) sql += " AND project = ?"
        db.query("$sql ORDER BY id ASC", *listOfNotNull(project).toTypedArray()) { ListedNote(it.stringOrNull(0), it.string(1), it.string(2), it.string(3)) }
    }

fun Store.listExportNotes(project: String?): List<ExportNote> =
    conn { db ->
        db.query(
            "SELECT id, ts, project, kind, title, body, retired, superseded_by, pinned FROM notes WHERE $NOT_SESSION" +
                (if (project != null) " AND project = ?" else "") + " ORDER BY id ASC",
            *listOfNotNull(project).toTypedArray(),
        ) {
            ExportNote(it.int(0), it.long(1), it.stringOrNull(2), it.string(3), it.string(4), it.string(5), it.longOrNull(6), it.intOrNull(7), it.int(8))
        }
    }

fun Store.latestNoteForProject(
    project: String?,
    kindPrefix: String,
): String? =
    conn {
        it.stringOf("SELECT body FROM notes WHERE kind LIKE ? AND ${projectIs(project)} ORDER BY id DESC LIMIT 1", "$kindPrefix%", *listOfNotNull(project).toTypedArray())
    }

fun Store.latestNote(kind: String): String? = conn { it.stringOf("SELECT body FROM notes WHERE kind = ? ORDER BY id DESC LIMIT 1", kind) }

fun Store.checkpointSessionIds(): List<String> =
    conn { db ->
        db
            .query("SELECT kind FROM notes WHERE kind LIKE 'checkpoint:%' OR kind LIKE 'session:%'") { it.string(0) }
            .mapNotNull { k -> (k.removePrefixOrNull("checkpoint:") ?: k.removePrefixOrNull("session:"))?.takeIf { it.isNotEmpty() } }
    }

private fun String.removePrefixOrNull(p: String) = if (startsWith(p)) substring(p.length) else null

fun Store.latestSessionNote(project: String?): String? =
    conn {
        it.stringOf("SELECT body FROM notes WHERE kind LIKE 'session:%' AND ${projectIs(project)} ORDER BY id DESC LIMIT 1", *listOfNotNull(project).toTypedArray())
    }

/** Live note titles: pinned first, then by retention score, then newest; at most `limit` (min 1). */
fun Store.listNoteTitles(
    project: String?,
    limit: UInt,
): List<Pair<Int, String>> {
    val lim = maxOf(limit, 1u).toLong()

    data class R(
        val id: Int,
        val title: String,
        val pinned: Int,
        val uses: Int,
        val ts: Long,
        val lastUsed: Long?,
    )
    val rows =
        conn { db ->
            db.query(
                "SELECT id, title, pinned, uses, ts, last_used FROM notes WHERE retired IS NULL AND $NOT_SESSION" +
                    (if (project != null) " AND project = ?" else "") + " ORDER BY pinned DESC, id DESC LIMIT ?",
                *(listOfNotNull<Any>(project) + (lim * 4)).toTypedArray(),
            ) {
                R(it.int(0), it.string(1), it.int(2), it.int(3), it.long(4), it.longOrNull(5))
            }
        }
    val now = unixNow()

    fun score(r: R) = retentionScore(r.pinned != 0, r.uses, ageDays(now, r.ts), daysSince(now, r.lastUsed))
    return rows.sortedWith(compareByDescending<R> { it.pinned }.thenByDescending { score(it) }.thenByDescending { it.id }).take(lim.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).map { it.id to it.title }
}

fun Store.touchNote(id: Int) =
    conn {
        it.execute("UPDATE notes SET uses = uses + 1, last_used = unixepoch() WHERE id = ?", id)
        Unit
    }

fun Store.noteRow(id: Int): NoteRow? =
    conn { db ->
        db.queryOne("SELECT id, project, kind, title, body, retired, superseded_by, pinned FROM notes WHERE id = ?", id) {
            NoteRow(it.int(0), it.stringOrNull(1), it.string(2), it.string(3), it.string(4), it.longOrNull(5), it.intOrNull(6), it.int(7))
        }
    }

fun Store.retireNote(
    id: Int,
    supersededBy: Int?,
): Boolean = conn { it.execute("UPDATE notes SET retired = unixepoch(), superseded_by = ? WHERE id = ?", supersededBy, id) == 1 }

/** Unpinned, never-used notes older than `idleDays` (user/decision kinds kept); retired when `apply`. */
fun Store.forgetStaleNotes(
    project: String?,
    idleDays: UInt,
    apply: Boolean,
): List<Pair<Int, String>> =
    conn { db ->
        val cutoff = unixNow() - idleDays.toLong() * 86_400
        val keeps = FORGET_KEEPS.joinToString(",") { "'$it'" }
        val rows =
            db.query(
                "SELECT id, title FROM notes WHERE retired IS NULL AND pinned = 0 AND uses = 0 AND ts < ? AND $NOT_SESSION AND kind NOT IN ($keeps)" +
                    (if (project != null) " AND project = ?" else "") + " ORDER BY id ASC",
                *(listOf<Any?>(cutoff) + listOfNotNull(project)).toTypedArray(),
            ) { it.int(0) to it.string(1) }
        if (apply && rows.isNotEmpty()) db.execute("UPDATE notes SET retired = unixepoch() WHERE id IN (${rows.joinToString(",") { it.first.toString() }})")
        rows
    }

fun Store.setNotePinned(
    id: Int,
    pinned: Boolean,
): Boolean = conn { it.execute("UPDATE notes SET pinned = ? WHERE id = ?", if (pinned) 1 else 0, id) == 1 }

fun Store.noteBodies(): List<String> = conn { it.query("SELECT body FROM notes") { r -> r.string(0) } }

/** Live notes in the same project other than `(kind, title)`, newest first: `(id, title, body)`. */
fun Store.admitCandidates(
    project: String?,
    kind: String,
    title: String,
    limit: UInt,
): List<Triple<Int, String, String>> =
    conn { db ->
        db.query(
            "SELECT id, title, body FROM notes WHERE retired IS NULL AND $NOT_SESSION AND NOT (kind = ? AND title = ?) AND ${projectIs(project)} ORDER BY id DESC LIMIT ?",
            *(listOf<Any?>(kind, title) + listOfNotNull(project) + limit.toLong()).toTypedArray(),
        ) { Triple(it.int(0), it.string(1), it.string(2)) }
    }

fun Store.getNoteBody(id: Int): String? = conn { it.stringOf("SELECT body FROM notes WHERE id = ?", id) }

fun Store.searchNotes(
    query: String,
    limit: UInt,
) = searchNotesIn(query, limit, null)

/** FTS5 phrase search re-ranked by retention (and a project boost); at most `limit` hits. */
fun Store.searchNotesIn(
    query: String,
    limit: UInt,
    project: String?,
): List<NoteHit> {
    val q = ftsPhraseQuery(query) ?: return emptyList()
    val window = maxOf(limit.toLong() * RANK_WINDOW.toLong(), limit.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())

    data class R(
        val id: Int,
        val title: String,
        val snippet: String,
        val pinned: Int,
        val uses: Int,
        val ts: Long,
        val lastUsed: Long?,
        val project: String?,
    )
    val rows =
        conn { db ->
            db.query(
                "SELECT n.id, n.title, substr(n.body, 1, 120), n.pinned, n.uses, n.ts, n.last_used, n.project FROM notes_fts f JOIN notes n ON n.id = f.rowid " +
                    "WHERE notes_fts MATCH ? AND n.retired IS NULL ORDER BY bm25(notes_fts) LIMIT ?",
                q,
                window,
            ) {
                R(it.int(0), it.string(1), it.string(2), it.int(3), it.int(4), it.long(5), it.longOrNull(6), it.stringOrNull(7))
            }
        }
    val now = unixNow()
    return rows
        .mapIndexed { rank, r ->
            val kept = retentionScore(r.pinned != 0, r.uses, ageDays(now, r.ts), daysSince(now, r.lastUsed))
            val home = project != null && r.project == project
            Triple(rankBlend(rank, kept) * if (home) PROJECT_BOOST else 1.0, rank, NoteHit(r.id, r.title, r.snippet))
        }.sortedWith(compareByDescending<Triple<Double, Int, NoteHit>> { it.first }.thenBy { it.second })
        .take(limit.toInt())
        .map { it.third }
}
