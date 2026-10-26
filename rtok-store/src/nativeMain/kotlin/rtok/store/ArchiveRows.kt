// Port of crates/rtok-store/src/archive_rows.rs (+ the archive helpers in lib.rs).
package rtok.store

import platform.posix.unlink
import rtok.db.*
import rtok.plugin.sdk.ArchiveDecision
import rtok.plugin.sdk.Measurement
import rtok.sys.*

private class Spill(
    val json: String?,
    val arch: String?,
    val bytes: Long,
    val sha: String?,
    val path: String?,
    val created: Boolean,
    val raw: ByteArray?,
)

/** `(text, sha, raw bytes when not UTF-8)`. */
internal fun inlineBody(body: ByteArray): Triple<String, String, ByteArray?> {
    val sha = hexSha256(body)
    val s = body.decodeUtf8OrNull()
    return if (s != null) Triple(s, sha, null) else Triple(body.decodeToString(), sha, body.copyOf())
}

internal fun insertArchiveRow(
    db: Db,
    sha: String,
    session: String,
    bytes: Long,
    path: String,
    agentId: String?,
) {
    db.execute(
        "INSERT INTO archive (id, session, bytes, path, sha256, agent_id) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET agent_id = excluded.agent_id, ts = ? WHERE archive.session = ? AND archive.agent_id = ?",
        sha,
        session,
        bytes,
        path,
        sha,
        agentId,
        unixNow(),
        session,
        FORGOTTEN_CONTEXT,
    )
}

internal fun markExpandedDb(
    db: Db,
    archiveId: String,
): Int = db.execute("UPDATE archive_decisions SET expanded_ts = ? WHERE archive_id = ? AND expanded_ts IS NULL", unixNow(), archiveId)

internal fun insertMeasurementDb(
    db: Db,
    session: String,
    m: Measurement,
    once: String?,
) {
    val onceKey = once?.let { "$it|${m.plugin}|${m.kind}|${m.refId ?: ""}" }
    if (m.beforeBytes > Long.MAX_VALUE.toULong()) throw StoreException("measurement before_bytes")
    if (m.afterBytes > Long.MAX_VALUE.toULong()) throw StoreException("measurement after_bytes")
    if (m.estBefore > Int.MAX_VALUE.toUInt()) throw StoreException("measurement est_before")
    if (m.estAfter > Int.MAX_VALUE.toUInt()) throw StoreException("measurement est_after")
    db.execute(
        "INSERT INTO measurements (session, plugin, kind, before_bytes, after_bytes, est_before, est_after, ref_id, call_id, once_key) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (once_key) DO NOTHING",
        session,
        m.plugin,
        m.kind,
        m.beforeBytes.toLong(),
        m.afterBytes.toLong(),
        m.estBefore.toInt(),
        m.estAfter.toInt(),
        m.refId,
        m.callId,
        onceKey,
    )
}

/** Save a call's request/response: inline up to `inlineCap` bytes, else to `archiveDir` (or byte count only). */
fun Store.insertCallIo(
    callId: Int,
    request: ByteArray?,
    response: ByteArray?,
    inlineCap: Int,
    archiveDir: String?,
) {
    val req = request?.let { if (storeRaw) it else Sanitize.body(it) }
    writeCallIo(callId, req, null, response, inlineCap, archiveDir)
}

/** A hook body: session fields split into `hook_sessions` unless raw or over the cap (T433). */
fun Store.insertHookCallIo(
    callId: Int,
    stdin: ByteArray,
    response: ByteArray?,
    inlineCap: Int,
) {
    val split = if (storeRaw || stdin.size > inlineCap) null else HookFields.split(stdin)
    if (split != null) {
        writeCallIo(callId, split.first, split.second, response, inlineCap, null)
    } else {
        insertCallIo(callId, stdin, response, inlineCap, null)
    }
}

internal fun Store.writeCallIo(
    callId: Int,
    request: ByteArray?,
    hookFields: String?,
    response: ByteArray?,
    inlineCap: Int,
    archiveDir: String?,
) {
    val session = callSession(callId)
    val req = spill(request, inlineCap, archiveDir)
    val res = spill(response, inlineCap, archiveDir)
    val created = listOfNotNull(req.path.takeIf { req.created }, res.path.takeIf { res.created })
    conn { db ->
        try {
            db.transaction("IMMEDIATE") {
                if (req.arch != null && req.path != null) insertArchiveRow(db, req.arch, session, req.bytes, req.path, null)
                if (res.arch != null && res.path != null) insertArchiveRow(db, res.arch, session, res.bytes, res.path, null)
                val hookSession = hookFields?.let { HookFields.upsert(db, it) }
                db.execute(
                    "INSERT INTO call_io (call_id, request_bytes, response_bytes, request_sha256, response_sha256, request_json, response_json, " +
                        "request_archive, response_archive, request_raw, response_raw, hook_session_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    callId,
                    req.bytes,
                    res.bytes,
                    req.sha,
                    res.sha,
                    req.json,
                    res.json,
                    req.arch,
                    res.arch,
                    req.raw,
                    res.raw,
                    hookSession,
                )
            }
        } catch (e: Throwable) {
            for (p in created) {
                val referenced =
                    try {
                        (db.longOf("SELECT count(*) FROM archive WHERE id = ?", p.substringAfterLast('/')) ?: 0) > 0
                    } catch (_: Throwable) {
                        true
                    }
                if (!referenced) unlink(p)
            }
            throw e
        }
    }
}

private fun spill(
    body: ByteArray?,
    cap: Int,
    archiveDir: String?,
): Spill {
    if (body == null) return Spill(null, null, 0, null, null, false, null)
    val n = body.size.toLong()
    if (body.size <= cap) {
        val (text, sha, raw) = inlineBody(body)
        return Spill(text, null, n, sha, null, false, raw)
    }
    if (archiveDir == null) return Spill(null, null, n, null, null, false, null)
    val sha = hexSha256(body)
    val (path, created) = writeArchiveFile(archiveDir, sha, body)
    return Spill(null, sha, n, sha, path, created, null)
}

fun Store.putArchive(
    session: String,
    body: ByteArray,
    dir: String,
): String = putArchiveFor(session, body, dir, null)

fun Store.putArchiveFor(
    session: String,
    body: ByteArray,
    dir: String,
    agentId: String?,
): String {
    val sha = hexSha256(body)
    val (path, created) = writeArchiveFile(dir, sha, body)
    try {
        conn { insertArchiveRow(it, sha, session, body.size.toLong(), path, agentId) }
    } catch (e: Throwable) {
        if (created) unlink(path)
        throw e
    }
    return sha
}

/** `(id, turns since archived)` when `sha` was archived in `session` for `agentId` (null = main context). */
fun Store.archiveInSession(
    session: String,
    sha: String,
    agentId: String?,
): Pair<String, ULong>? =
    conn { db ->
        val turns = "(SELECT count(*) FROM measurements WHERE measurements.session = archive.session AND measurements.ts > archive.ts)"
        val q = "SELECT archive.id, $turns FROM archive WHERE archive.id = ? AND archive.session = ? AND "
        val row =
            if (agentId != null) {
                db.queryOne(q + "archive.agent_id = ?", sha, session, agentId) { it.string(0) to it.longOrNull(1) }
            } else {
                db.queryOne(q + "archive.agent_id IS NULL", sha, session) { it.string(0) to it.longOrNull(1) }
            }
        row?.let { it.first to maxOf(it.second ?: 0, 0).toULong() }
    }

fun Store.archiveDecision(
    session: String,
    toolUseId: String,
): ArchiveDecision? =
    conn { db ->
        db.queryOne("SELECT archive_id, pointer, expanded_ts IS NOT NULL FROM archive_decisions WHERE session = ? AND tool_use_id = ?", session, toolUseId) {
            ArchiveDecision(it.string(0), it.string(1), it.bool(2))
        }
    }

fun Store.putArchiveDecision(
    toolUseId: String,
    archiveId: String,
    session: String,
    pointer: String,
) = conn {
    it.execute("INSERT OR IGNORE INTO archive_decisions (tool_use_id, archive_id, session, pointer) VALUES (?, ?, ?, ?)", toolUseId, archiveId, session, pointer)
    Unit
}

/** `(id, tool or "-", bytes)` for the session's archived results, newest decision first, each id once. */
fun Store.sessionLiveArchives(session: String): List<Triple<String, String, Long>> =
    conn { db ->
        val seen = HashSet<String>()
        db
            .query(
                "SELECT archive.id, archive.tool, archive.bytes FROM archive_decisions INNER JOIN archive ON archive.id = archive_decisions.archive_id " +
                    "WHERE archive_decisions.session = ? ORDER BY archive_decisions.ts DESC, archive_decisions.tool_use_id DESC",
                session,
            ) {
                Triple(it.string(0), it.stringOrNull(1), it.long(2))
            }.filter { seen.add(it.first) }
            .map { (id, tool, bytes) -> Triple(id, tool?.takeIf { t -> t.isNotEmpty() } ?: "-", bytes) }
    }

fun Store.liveZonePointer(archiveId: String): String? =
    conn {
        it.stringOf("SELECT pointer FROM archive_decisions WHERE archive_id = ? ORDER BY tool_use_id, session LIMIT 1", archiveId)
    }

fun Store.markExpanded(archiveId: String): Int = conn { markExpandedDb(it, archiveId) }

fun Store.markExpandedRecorded(
    session: String,
    archiveId: String,
    m: Measurement,
): Int =
    conn { db ->
        db.transaction("IMMEDIATE") {
            val n = markExpandedDb(db, archiveId)
            if (n > 0) insertMeasurementDb(db, session, m, null)
            n
        }
    }

fun Store.archiveDecisionCounts(): Pair<Long, Long> =
    conn { db ->
        db.longOf("SELECT count(*) FROM archive_decisions")!! to db.longOf("SELECT count(*) FROM archive_decisions WHERE expanded_ts IS NOT NULL")!!
    }

/** The request body as sent: raw bytes, else inline JSON (hook fields spliced back), else the archive file. */
fun Store.callIoRequest(callId: Int): ByteArray? {
    data class R(
        val json: String?,
        val raw: ByteArray?,
        val arch: String?,
        val fields: String?,
    )
    val row =
        conn { db ->
            db.queryOne(
                "SELECT call_io.request_json, call_io.request_raw, call_io.request_archive, hook_sessions.fields FROM call_io " +
                    "LEFT JOIN hook_sessions ON hook_sessions.id = call_io.hook_session_id WHERE call_io.call_id = ?",
                callId,
            ) {
                R(it.stringOrNull(0), it.blobOrNull(1), it.stringOrNull(2), it.stringOrNull(3))
            }
        } ?: return null
    return when {
        row.raw != null -> row.raw
        row.json != null -> HookFields.rebuild(row.json, row.fields).encodeToByteArray()
        row.arch != null -> getArchive(row.arch, null)
        else -> null
    }
}

fun Store.archiveIdsWithPrefix(
    prefix: String,
    limit: Long,
): List<String> {
    if (prefix.isEmpty() || !prefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return emptyList()
    return conn { it.query("SELECT id FROM archive WHERE id LIKE ? LIMIT ?", prefix.lowercase() + "%", limit) { r -> r.string(0) } }
}

private fun candidates(
    id: String,
    dir: String?,
    stored: String,
): List<String> {
    val paths = ArrayList<String>()
    dir?.let { paths.add("$it/$id") }
    if (stored !in paths) paths.add(stored)
    return paths
}

/** The archived body: `dir/<id>` first (a moved store), then the stored path. */
fun Store.getArchive(
    id: String,
    dir: String?,
): ByteArray? {
    val stored = conn { it.stringOf("SELECT path FROM archive WHERE id = ?", id) } ?: return null
    for (p in candidates(id, dir, stored)) {
        try {
            return readBytes(p)
        } catch (e: IoException) {
            if (e.kind == IoException.Kind.NotFound) continue
            throw StoreException("$p: ${e.message}", e)
        }
    }
    return null
}

fun Store.archiveSize(
    id: String,
    dir: String?,
): ULong? {
    val (bytes, stored) = conn { db -> db.queryOne("SELECT bytes, path FROM archive WHERE id = ?", id) { it.long(0) to it.string(1) } } ?: return null
    return candidates(id, dir, stored).firstOrNull { exists(it) }?.let { maxOf(bytes, 0).toULong() }
}
