// Port of crates/rtok-store/src/otel.rs: export marks and the rows the OTel exporter reads.
package rtok.store

import rtok.db.*

const val SEMANTIC_CACHE_HIT_KIND = "semantic_cache_hit"

data class CallUsage(
    val api: String,
    val input: Long,
    val cacheCreate: Long,
    val cacheRead: Long,
    val output: Long,
)

data class CallMeasurement(
    val plugin: String,
    val kind: String,
    val beforeBytes: Long,
    val afterBytes: Long,
    val estBefore: Int,
    val estAfter: Int,
    val refId: String?,
)

data class CallDetail(
    val io: CallIo? = null,
    val usage: CallUsage? = null,
    val tokens: List<TokenRow> = emptyList(),
    val measurements: List<CallMeasurement> = emptyList(),
    val host: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

data class TokenTotal(
    val model: String?,
    val api: String,
    val input: Long,
    val cacheCreate: Long,
    val cacheRead: Long,
    val output: Long,
)

data class SavedTotal(
    val plugin: String,
    val kind: String,
    val saved: Long,
)

data class CallTotal(
    val surface: String,
    val kind: String,
    val ok: Int,
    val n: Long,
)

private fun clamp(id: Long): Int = id.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun hostSlug(
    db: Db,
    id: Int,
) = db.stringOf("SELECT slug FROM hosts WHERE id = ?", id)

fun Store.hostSlug(id: Int): String? = conn { hostSlug(it, id) }

/** `(calls, logs)` not yet exported. */
fun Store.otelPending(): Pair<Long, Long> {
    val c = otelMark("calls")
    val l = otelMark("logs")
    return conn { db -> db.longOf("SELECT count(*) FROM calls WHERE id > ?", clamp(c))!! to db.longOf("SELECT count(*) FROM logs WHERE id > ?", clamp(l))!! }
}

fun Store.lastLog(source: String): LogRow? = conn { it.queryOne("SELECT ${LogRow.COLS} FROM logs WHERE source = ? ORDER BY id DESC LIMIT 1", source) { r -> LogRow.of(r) } }

/** Usage per (model, api), skipping calls served from the semantic cache. */
fun Store.otelTokenTotals(): List<TokenTotal> =
    conn { db ->
        db.query(
            "SELECT usage.model, usage.api, SUM(usage.input), SUM(usage.cache_create), SUM(usage.cache_read), SUM(usage.output) FROM usage " +
                "WHERE NOT EXISTS (SELECT 1 FROM measurements WHERE measurements.call_id = usage.call_id AND measurements.kind = ?) " +
                "GROUP BY usage.model, usage.api ORDER BY usage.model ASC, usage.api ASC",
            SEMANTIC_CACHE_HIT_KIND,
        ) {
            TokenTotal(it.stringOrNull(0), it.string(1), it.longOrNull(2) ?: 0, it.longOrNull(3) ?: 0, it.longOrNull(4) ?: 0, it.longOrNull(5) ?: 0)
        }
    }

fun Store.otelSavedTotals(): List<SavedTotal> = measurementTotals().map { SavedTotal(it.plugin, it.kind, it.estBefore - it.estAfter) }

fun Store.otelCallTotals(): List<CallTotal> =
    conn { db ->
        db.query("SELECT surface, kind, ok, count(*) FROM calls GROUP BY surface, kind, ok ORDER BY surface ASC, kind ASC, ok ASC") { CallTotal(it.string(0), it.string(1), it.int(2), it.long(3)) }
    }

fun Store.otelFirstTs(): Long = conn { it.longOf("SELECT min(ts) FROM calls") ?: 0 }

fun Store.otelMark(stream: String): Long = conn { it.longOf("SELECT mark FROM otel_export WHERE stream = ?", stream) ?: 0 }

fun Store.otelAdvance(
    stream: String,
    mark: Long,
) = conn {
    it.execute("INSERT INTO otel_export (stream, mark) VALUES (?, ?) ON CONFLICT (stream) DO UPDATE SET mark = ?", stream, mark, mark)
    Unit
}

fun Store.callsAfter(
    after: Long,
    limit: Long,
): List<Call> = conn { it.query("SELECT ${Call.COLS} FROM calls WHERE id > ? ORDER BY id ASC LIMIT ?", clamp(after), limit) { r -> Call.of(r) } }

fun Store.logsAfter(
    after: Long,
    limit: Long,
): List<LogRow> = conn { it.query("SELECT ${LogRow.COLS} FROM logs WHERE id > ? ORDER BY id ASC LIMIT ?", clamp(after), limit) { r -> LogRow.of(r) } }

fun Store.sessionsEndedAfter(ts: Long): List<Session> = conn { it.query("SELECT ${Session.COLS} FROM sessions WHERE ended_at >= ? ORDER BY ended_at ASC", ts) { r -> Session.of(r) } }

/** `(end_seq, session)` for sessions ended after sequence `after`, in the order they ended. */
fun Store.sessionsPendingExport(
    after: Long,
    limit: Long,
): List<Pair<Long, Session>> =
    conn { db ->
        db.query("SELECT sessions.end_seq, ${Session.COLS} FROM sessions WHERE end_seq > ? ORDER BY end_seq ASC LIMIT ?", after, limit) { it.long(0) to Session.of(it, 1) }
    }

/** Mark a session ended and give it the next export sequence; the same second again is a no-op. */
fun Store.endSession(
    id: String,
    endedAt: Long,
) = conn { db ->
    db.transaction("IMMEDIATE") {
        val seq = (db.longOf("SELECT max(end_seq) FROM sessions") ?: 0) + 1
        db.execute("UPDATE sessions SET ended_at = ?, end_seq = ? WHERE id = ? AND (ended_at IS NULL OR ended_at != ?)", endedAt, seq, id, endedAt)
    }
    Unit
}

fun Store.callDetail(call: Call): CallDetail =
    conn { db ->
        val io =
            db.queryOne("SELECT ${CallIo.COLS}, hook_sessions.fields FROM call_io LEFT JOIN hook_sessions ON hook_sessions.id = call_io.hook_session_id WHERE call_io.call_id = ?", call.id) {
                val io = CallIo.of(it)
                io.copy(requestJson = io.requestJson?.let { j -> HookFields.rebuild(j, it.stringOrNull(9)) })
            }
        val usage =
            db.queryOne("SELECT api, input, cache_create, cache_read, output FROM usage WHERE call_id = ? ORDER BY id DESC LIMIT 1", call.id) {
                CallUsage(it.string(0), it.long(1), it.long(2), it.long(3), it.long(4))
            }
        val tokens = db.query("SELECT ${TokenRow.COLS} FROM tokens WHERE call_id = ? ORDER BY id ASC", call.id) { TokenRow.of(it) }
        val ms =
            db.query("SELECT plugin, kind, before_bytes, after_bytes, est_before, est_after, ref_id FROM measurements WHERE call_id = ? ORDER BY id ASC", call.id) {
                CallMeasurement(it.string(0), it.string(1), it.long(2), it.long(3), it.int(4), it.int(5), it.stringOrNull(6))
            }
        CallDetail(
            io,
            usage,
            tokens,
            ms,
            call.hostId?.let { hostSlug(db, it) },
            call.providerId?.let { db.stringOf("SELECT slug FROM providers WHERE id = ?", it) },
            call.modelId?.let { db.stringOf("SELECT slug FROM models WHERE id = ?", it) },
        )
    }
