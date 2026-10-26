// Port of crates/rtok-store/src/observations.rs and archive_summaries.rs.
package rtok.store

import rtok.db.*
import rtok.plugin.sdk.NewObservation
import rtok.plugin.sdk.ObsHit

private const val DEDUP_SECS = 5L
private const val OBS_COLS = "observations.id, observations.title, observations.session_id, substr(observations.narrative, 1, 120)"

private fun hit(r: Row) = ObsHit(r.int(0), r.string(1), r.string(2), r.string(3))

fun Store.insertObservation(obs: NewObservation) = insertObservationOnce(obs, null)

/** New observation id; null when the same dedup key landed in this session in the last 5 s, or `once` was seen. */
fun Store.insertObservationOnce(
    obs: NewObservation,
    once: String?,
): Int? =
    conn { db ->
        val since = unixNow() - DEDUP_SECS
        if (db.longOf("SELECT id FROM observations WHERE session_id = ? AND dedup = ? AND ts >= ? LIMIT 1", obs.sessionId, obs.dedup, since) != null) return@conn null
        db.transaction {
            val id =
                db.queryOne(
                    "INSERT INTO observations (session_id, project, obs_type, title, narrative, dedup, call_key) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (call_key) DO NOTHING RETURNING id",
                    obs.sessionId,
                    obs.project,
                    obs.obsType,
                    obs.title,
                    obs.narrative,
                    obs.dedup,
                    once,
                ) { it.int(0) }
                    ?: return@transaction null
            for (p in obs.files) db.execute("INSERT OR IGNORE INTO observation_files (observation_id, path) VALUES (?, ?)", id, p)
            id
        }
    }

fun Store.setObservationTs(
    session: String,
    ts: Long,
) = conn {
    it.execute("UPDATE observations SET ts = ? WHERE session_id = ?", ts, session)
    Unit
}

fun Store.searchObservations(
    project: String?,
    query: String,
    limit: UInt,
): List<ObsHit> {
    val q = ftsPhraseQuery(query) ?: return emptyList()
    return conn { db ->
        db.query(
            "SELECT o.id, o.title, o.session_id, substr(o.narrative, 1, 120) FROM observations_fts f JOIN observations o ON o.id = f.rowid " +
                "WHERE observations_fts MATCH ? AND (? IS NULL OR o.project IS NULL OR o.project = ?) ORDER BY bm25(observations_fts) LIMIT ?",
            q,
            project,
            project,
            maxOf(limit, 1u).toLong(),
        ) { hit(it) }
    }
}

fun Store.observationsForFiles(
    project: String?,
    paths: List<String>,
    limit: UInt,
): List<ObsHit> {
    if (paths.isEmpty()) return emptyList()
    return conn { db ->
        val marks = inList(paths.size)
        db.query(
            "SELECT $OBS_COLS FROM observations WHERE observations.id IN (SELECT observation_id FROM observation_files WHERE path IN ($marks))" +
                (if (project != null) " AND (observations.project IS NULL OR observations.project = ?)" else "") + " ORDER BY observations.id DESC LIMIT ?",
            *(paths + listOfNotNull(project) + maxOf(limit, 1u).toLong()).toTypedArray(),
        ) { hit(it) }
    }
}

fun Store.recentObservations(
    sessionId: String,
    limit: UInt,
): List<ObsHit> =
    conn { db ->
        db.query("SELECT $OBS_COLS FROM observations WHERE session_id = ? ORDER BY id DESC LIMIT ?", sessionId, maxOf(limit, 1u).toLong()) { hit(it) }
    }

fun Store.observationNarrative(id: Int): String? = conn { it.stringOf("SELECT narrative FROM observations WHERE id = ?", id) }

fun Store.archiveSummary(
    archiveId: String,
    version: String,
): String? = conn { it.stringOf("SELECT summary FROM archive_summaries WHERE archive_id = ? AND version = ?", archiveId, version) }

fun Store.archiveSummaries(): List<Pair<String, String>> = conn { it.query("SELECT archive_id, summary FROM archive_summaries ORDER BY archive_id, version") { r -> r.string(0) to r.string(1) } }

fun Store.putArchiveSummary(
    archiveId: String,
    version: String,
    summary: String,
): Boolean =
    conn {
        it.execute("INSERT OR IGNORE INTO archive_summaries (archive_id, version, summary) VALUES (?, ?, ?)", archiveId, version, summary) > 0
    }
