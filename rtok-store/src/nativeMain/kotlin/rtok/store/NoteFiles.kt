// Port of crates/rtok-store/src/note_files.rs (T374): the files a note is about.
package rtok.store

import db.sqldelight.KitSqlDriver
import rtok.plugin.sdk.NoteHit
import rtok.store.queries.NoteFilesQueries

private const val MAX_READ_KEYS = 512L

/** Replace the files `noteId` is linked to with `paths` (root-relative). */
fun Store.setNoteFiles(
    noteId: Int,
    paths: List<String>,
) = conn { db ->
    val queries = NoteFilesQueries(KitSqlDriver(db))
    db.transaction {
        queries.deleteLinks(noteId.toLong())
        for (p in paths) queries.insertLink(noteId.toLong(), p)
    }
}

/** Live notes linked to any of `paths`: most-linked first, then newest. */
fun Store.notesForFiles(
    project: String?,
    paths: List<String>,
    limit: UInt,
): List<NoteHit> {
    if (paths.isEmpty()) return emptyList()
    return conn { db ->
        NoteFilesQueries(KitSqlDriver(db))
            .selectForFiles(paths, project, maxOf(limit, 1u).toLong()) { id, title, snippet -> NoteHit(id.toInt(), title, snippet) }
            .executeAsList()
    }
}

/** Raw read-cache keys of `session`, path order (capped). */
fun Store.readCacheKeys(session: String): List<String> =
    conn { db ->
        NoteFilesQueries(KitSqlDriver(db)).readCacheKeys(session, MAX_READ_KEYS).executeAsList()
    }
