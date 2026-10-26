// Port of crates/rtok-store/src/graph_events.rs (T329.15): start / progress / end events per graph
// tool call, read by id cursor. The table is the channel between processes.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package rtok.store

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Rows kept; only a burst larger than this between two polls could lose rows. */
const val GRAPH_EVENTS_KEEP = 5000L
/** Free text (a symbol, an error) is clipped to this many characters. */
const val GRAPH_TEXT_MAX = 200

@Serializable
enum class EventPhase(val str: String) {
    @SerialName("start") Start("start"), @SerialName("progress") Progress("progress"), @SerialName("end") End("end");
    companion object { fun parse(s: String) = entries.firstOrNull { it.str == s } }
}

/** One `graph` measurements row of the call, with the columns `rtok stats` sums. */
@Serializable
data class MeasurementSample(
    val id: Int, val kind: String,
    @SerialName("before_bytes") val beforeBytes: Long, @SerialName("after_bytes") val afterBytes: Long,
    @SerialName("est_before") val estBefore: Int, @SerialName("est_after") val estAfter: Int,
    /** Set when the answer was cut and archived; absent in rows written before T329.33 (serde `default`), always written. */
    @SerialName("ref_id") @EncodeDefault(EncodeDefault.Mode.ALWAYS) val refId: String? = null,
)

/** One event of a graph call; `id` and `tsMs` are the store's. */
@Serializable
data class GraphEvent(
    val id: Long = 0, @SerialName("ts_ms") val tsMs: Long = 0, val call: String = "", val phase: EventPhase = EventPhase.Start,
    val session: String = "", val tool: String = "", val target: String? = null, val project: String? = null,
    /** `lsp`, `tags` or `text` (end events). */
    val backend: String? = null, val ok: Boolean = false, val error: String? = null,
    /** Elapsed milliseconds (end events). */
    val ms: Double? = null, val done: UInt? = null, val total: UInt? = null,
    @SerialName("answer_tokens") val answerTokens: UInt? = null,
    val samples: List<MeasurementSample> = emptyList(),
    val symbols: UInt? = null,
    @SerialName("symbols_returned") val symbolsReturned: UInt? = null,
    @SerialName("files_touched") val filesTouched: UInt? = null,
    @SerialName("projects_hit") val projectsHit: UInt? = null,
) {
    companion object { fun new(call: String, phase: EventPhase, session: String, tool: String) = GraphEvent(call = call, phase = phase, session = session, tool = tool, ok = true) }
}

private val samplesSerializer = ListSerializer(MeasurementSample.serializer())
private val samplesJson = Json { ignoreUnknownKeys = true }
private const val EVENT_COLS = "id, ts_ms, call, phase, session, tool, target, project, backend, ok, error, ms, done, total, answer_tokens, rows_json, symbols, symbols_returned, files_touched, projects_hit"

private fun clip(s: String?) = s?.let { val cps = it.codePoints(); if (cps.size <= GRAPH_TEXT_MAX) it else cps.take(GRAPH_TEXT_MAX).joinToString("") }
private fun String.codePoints(): List<String> { val out = ArrayList<String>(); var i = 0
    while (i < length) { val n = if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1; out += substring(i, i + n); i += n }; return out }
private fun UInt?.toColumn(): Int? = this?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt()
private fun Row.unsigned(i: Int): UInt? = intOrNull(i)?.takeIf { it >= 0 }?.toUInt()

@OptIn(ExperimentalTime::class)
private fun unixMs() = Clock.System.now().toEpochMilliseconds()

/** `null` for a phase this build does not know, so a newer writer cannot break an older reader. */
private fun event(r: Row): GraphEvent? {
    val phase = EventPhase.parse(r.string(3)) ?: return null
    val samples = r.stringOrNull(15)?.let { runCatching { samplesJson.decodeFromString(samplesSerializer, it) }.getOrNull() } ?: emptyList()
    return GraphEvent(r.long(0), r.long(1), r.string(2), phase, r.string(4), r.string(5), r.stringOrNull(6), r.stringOrNull(7), r.stringOrNull(8), r.int(9) != 0,
        r.stringOrNull(10), r.doubleOrNull(11), r.unsigned(12), r.unsigned(13), r.unsigned(14), samples, r.unsigned(16), r.unsigned(17), r.unsigned(18), r.unsigned(19))
}

/** Append `e` stamped with the time, trim to the newest [GRAPH_EVENTS_KEEP] rows; the new id. */
fun Store.insertGraphEvent(e: GraphEvent): Long {
    val rowsJson = if (e.samples.isEmpty()) null else Json.encodeToString(samplesSerializer, e.samples)
    return conn { db ->
        val id = db.queryOne("INSERT INTO graph_events (${EVENT_COLS.removePrefix("id, ")}) VALUES (${inList(19)}) RETURNING id",
            unixMs(), e.call, e.phase.str, e.session, e.tool, clip(e.target), clip(e.project), clip(e.backend), if (e.ok) 1 else 0, clip(e.error), e.ms,
            e.done.toColumn(), e.total.toColumn(), e.answerTokens.toColumn(), rowsJson, e.symbols.toColumn(), e.symbolsReturned.toColumn(), e.filesTouched.toColumn(), e.projectsHit.toColumn()) { it.long(0) }!!
        db.execute("DELETE FROM graph_events WHERE id <= ?", id - GRAPH_EVENTS_KEEP)
        id
    }
}

/** Up to `limit` events above `cursor`, oldest first (one primary-key range scan). */
fun Store.graphEventsAfter(cursor: Long, limit: Long): List<GraphEvent> = conn {
    it.query("SELECT $EVENT_COLS FROM graph_events WHERE id > ? ORDER BY id ASC LIMIT ?", cursor, limit, map = ::event).filterNotNull()
}

/** The newest event id, 0 for an empty table. */
fun Store.graphEventHead(): Long = conn { it.longOf("SELECT max(id) FROM graph_events") ?: 0 }

/** The newest measurements id, 0 for an empty ledger. */
fun Store.measurementHead(): Int = conn { it.longOf("SELECT max(id) FROM measurements")?.toInt() ?: 0 }

/** The `graph` rows `session` wrote after measurement `after`, in write order. */
fun Store.graphMeasurementsAfter(session: String, after: Int): List<MeasurementSample> = conn {
    it.query("SELECT id, kind, before_bytes, after_bytes, est_before, est_after, ref_id FROM measurements WHERE id > ? AND session = ? AND plugin = 'graph' ORDER BY id ASC", after, session) { r ->
        MeasurementSample(r.int(0), r.string(1), r.long(2), r.long(3), r.int(4), r.int(5), r.stringOrNull(6))
    }
}
