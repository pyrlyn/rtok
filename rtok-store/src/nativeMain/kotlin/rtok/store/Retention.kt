// Port of crates/rtok-store/src/retention.rs (+ the retention SQL from sql_ext.rs).
package rtok.store

import rtok.db.*

import rtok.sys.*

import platform.posix.unlink
import rtok.sys.isUnwalkableRoot

private fun cutoffDays(days: Long) = unixNow() - days * 86_400

/** Archive rows whose only call_io references are older than `cutoff` and carry no decision or read-cache row. */
internal fun doomedArchives(db: Db, cutoff: Long): List<Pair<String, String>> = db.query(
    "SELECT DISTINCT a.id, a.path FROM archive a " +
        "WHERE (a.id IN (SELECT request_archive FROM call_io " +
        "WHERE call_id IN (SELECT id FROM calls WHERE ts < ?) AND request_archive IS NOT NULL) " +
        "OR a.id IN (SELECT response_archive FROM call_io " +
        "WHERE call_id IN (SELECT id FROM calls WHERE ts < ?) AND response_archive IS NOT NULL)) " +
        "AND NOT EXISTS (SELECT 1 FROM call_io c " +
        "WHERE c.call_id NOT IN (SELECT id FROM calls WHERE ts < ?) " +
        "AND (c.request_archive = a.id OR c.response_archive = a.id)) " +
        "AND NOT EXISTS (SELECT 1 FROM archive_decisions d WHERE d.archive_id = a.id) " +
        "AND NOT EXISTS (SELECT 1 FROM read_cache r WHERE r.archive_id = a.id)", cutoff, cutoff, cutoff) { it.string(0) to it.string(1) }

internal fun purgeRelated(db: Db, cutoff: Long) {
    val old = "(SELECT id FROM calls WHERE ts < $cutoff)"
    db.execute("DELETE FROM logs WHERE ts < ? OR call_id IN $old", cutoff)
    db.execute("DELETE FROM tokens WHERE ts < ? OR call_id IN $old", cutoff)
    db.execute("DELETE FROM call_io WHERE call_id IN $old")
    db.execute("UPDATE usage SET call_id = NULL WHERE call_id IN $old")
    db.execute("UPDATE measurements SET call_id = NULL WHERE call_id IN $old")
    db.execute("UPDATE calls SET parent_id = NULL WHERE parent_id IN (SELECT old.id FROM calls AS old WHERE old.ts < ?)", cutoff)
}

internal fun clearHookBodies(db: Db, cutoff: Long, batch: Long): Int = db.execute(
    "UPDATE call_io SET request_json = NULL, response_json = NULL, request_raw = NULL, response_raw = NULL, hook_session_id = NULL " +
        "WHERE call_id IN (SELECT pending_io.call_id FROM call_io AS pending_io " +
        "WHERE pending_io.call_id IN (SELECT id FROM calls WHERE kind = 'hook' AND ts < ?) " +
        "AND (pending_io.request_json IS NOT NULL OR pending_io.response_json IS NOT NULL " +
        "OR pending_io.request_raw IS NOT NULL OR pending_io.response_raw IS NOT NULL) LIMIT ?)", cutoff, batch)

internal fun symbolRoots(db: Db): List<String> =
    (db.query("SELECT DISTINCT root FROM symbols") { it.string(0) } + db.query("SELECT DISTINCT root FROM symbol_stale") { it.string(0) } +
        db.query("SELECT DISTINCT root FROM extractor") { it.string(0) }).distinct().sorted()

internal fun deleteSymbolRoot(db: Db, root: String) {
    db.execute("DELETE FROM symbols WHERE root = ?", root); db.execute("DELETE FROM symbol_stale WHERE root = ?", root); db.execute("DELETE FROM extractor WHERE root = ?", root)
}

internal fun purgeArchive(db: Db, id: String) {
    db.execute("DELETE FROM archive_decisions WHERE archive_id = ?", id)
    db.execute("DELETE FROM archive_summaries WHERE archive_id = ?", id)
    db.execute("UPDATE read_cache SET archive_id = NULL WHERE archive_id = ?", id)
    db.execute("DELETE FROM archive WHERE id = ?", id)
}

fun Store.archivesPendingRetention(retainCallsDays: UInt): List<String> {
    if (retainCallsDays == 0u) return emptyList()
    return conn { db -> doomedArchives(db, cutoffDays(retainCallsDays.toLong())).map { it.second } }
}

fun Store.purgeCallsOlderThan(days: Long): Int {
    if (days <= 0) return 0
    val cutoff = cutoffDays(days)
    val (n, paths) = maintenance { db ->
        db.transaction("EXCLUSIVE") {
            val doomed = doomedArchives(db, cutoff)
            purgeRelated(db, cutoff)
            for ((id, _) in doomed) purgeArchive(db, id)
            db.execute("DELETE FROM calls WHERE ts < ?", cutoff) to doomed.map { it.second }
        }
    }
    for (p in paths) unlink(p)
    return n
}

fun Store.runRetention(retainCallsDays: UInt, retainHookBodiesDays: UInt): Int {
    val purged = purgeCallsOlderThan(retainCallsDays.toLong())
    clearHookBodiesOlderThan(retainHookBodiesDays.toLong())
    maintenance { HookFields.dropOrphans(it) }
    dropDeadSymbolRoots(rtok.sys.homeDir())
    maintenance { db -> db.query("PRAGMA incremental_vacuum") { }; Unit }
    return purged
}

fun Store.clearHookBodiesOlderThan(days: Long) = clearHookBodiesInBatches(days, HOOK_BODY_BATCH)

fun Store.clearHookBodiesInBatches(days: Long, batch: Long): Int {
    if (days <= 0) return 0
    val cutoff = cutoffDays(days); var total = 0
    while (true) { val n = maintenance { clearHookBodies(it, cutoff, batch) }; if (n == 0) return total; total += n }
}

/** Drop symbol roots that are no longer directories, or are `home` / `/` (T352, T356). */
fun Store.dropDeadSymbolRoots(home: String?): Int = maintenance { db ->
    val gone = symbolRoots(db).filter { r -> r.isNotEmpty() && (!isDir(r) || isUnwalkableRoot(r, home)) }
    for (r in gone) deleteSymbolRoot(db, r)
    gone.size
}

fun Store.autoVacuumMode(): Int = conn { db -> db.longOf("PRAGMA auto_vacuum")?.toInt() ?: 0 }

fun Store.convertToIncrementalVacuum(): Boolean = maintenance { db ->
    if ((db.longOf("PRAGMA auto_vacuum") ?: 0L) == 2L) return@maintenance false
    db.exec("PRAGMA auto_vacuum = INCREMENTAL"); db.exec("VACUUM"); true
}

fun Store.setQueryOnly() = conn { it.exec("PRAGMA query_only = ON") }
