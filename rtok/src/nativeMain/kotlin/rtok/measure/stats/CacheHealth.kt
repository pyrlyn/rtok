// Port of src/measure/cache.rs, `rtok stats --cache` (plan T5.5): per-session prompt-cache health from the proxy's
// `usage` rows. A bust is a turn whose `cache_creation_input_tokens` exceeds [BUST_CREATE_TOKENS] while
// `cache_read_input_tokens` fell below the previous turn's (OpenAI: any cache_read drop, T11.6); its cause is a changed
// `tools` array, a changed `system` prompt, or `unknown`, read from the request bodies the proxy recorded.
@file:Suppress("MagicNumber")

package rtok.measure.stats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.Col
import rtok.measure.field
import rtok.store.Store
import rtok.store.UsageRow
import rtok.store.callIoRequest
import rtok.store.hexSha256
import rtok.store.usageRows
import rtok.store.usageSessions
import rtok.table

/** `cache_creation_input_tokens` above this, with a `cache_read` drop, is a bust. */
const val BUST_CREATE_TOKENS = 20_000L

/** One API request as the cache saw it; [bust] is `tools` | `system` | `unknown` when it busted the cache. */
data class Turn(
    val callId: Long?,
    val input: Long,
    val cacheCreate: Long,
    val cacheRead: Long,
    val bust: String?,
) : Row {
    override fun toJson() =
        JsonObject(
            linkedMapOf("call_id" to json(callId), "input" to json(input), "cache_create" to json(cacheCreate), "cache_read" to json(cacheRead), "bust" to json(bust)),
        )
}

data class SessionHealth(
    val session: String,
    val turns: List<Turn>,
    val busts: Int,
    val cacheRead: Long,
    val cacheCreate: Long,
) : Row {
    override fun toJson() =
        JsonObject(
            linkedMapOf(
                "session" to json(session),
                "turns" to JsonArray(turns.map { it.toJson() }),
                "busts" to json(busts),
                "cache_read" to json(cacheRead),
                "cache_create" to json(cacheCreate),
            ),
        )
}

/** Fingerprints of the request parts whose change busts the cache. */
private data class Shape(
    val tools: String? = null,
    val system: String? = null,
)

private fun shape(body: ByteArray): Shape {
    val v: JsonElement =
        runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true))
        }.getOrNull() ?: return Shape()

    fun fp(key: String) = v.field(key)?.let { hexSha256(SerdeJson.compact(it).encodeToByteArray()) }
    return Shape(fp("tools"), fp("system"))
}

private fun cause(
    prev: Shape?,
    cur: Shape?,
): String =
    when {
        prev != null && cur != null && prev.tools != cur.tools -> "tools"
        prev != null && cur != null && prev.system != cur.system -> "system"
        else -> "unknown"
    }

object CacheHealth {
    /** Turns in request order with busts attributed; [body] is consulted only for the two requests around a bust. */
    fun analyse(
        rows: List<UsageRow>,
        body: (Long) -> ByteArray?,
    ): List<Turn> {
        fun load(id: Long?) = id?.let(body)?.let(::shape)
        var prev: UsageRow? = null
        return rows.map { r ->
            val p = prev
            val openai = r.api == "openai_chat" || r.api == "openai_responses"
            val busted = p != null && r.cacheRead < p.cacheRead && (openai || r.cacheCreate > BUST_CREATE_TOKENS)
            val bust = if (busted) cause(load(p!!.callId), load(r.callId)) else null
            prev = r
            Turn(r.callId, r.input, r.cacheCreate, r.cacheRead, bust)
        }
    }

    /** Every session with usage rows, oldest first. */
    fun report(store: Store): List<SessionHealth> =
        store.usageSessions().map { session ->
            val rows = store.usageRows(session).reversed()
            val turns = analyse(rows) { id -> id.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { runCatching { store.callIoRequest(it.toInt()) }.getOrNull() } }
            SessionHealth(session, turns, turns.count { it.bust != null }, turns.sumOf { it.cacheRead }, turns.sumOf { it.cacheCreate })
        }

    fun toJsonText(report: List<SessionHealth>): String = SerdeJson.pretty(JsonArray(report.map { it.toJson() }))

    /** The `rtok stats --cache` table, then one line per bust. */
    fun table(report: List<SessionHealth>): String =
        buildString {
            val cols = listOf(Col.left(40), Col.right(6), Col.right(12), Col.right(12), Col.right(6))
            val rows = mutableListOf(listOf("session", "turns", "cache_read", "cache_create", "busts"))
            for (h in report) rows += listOf(h.session, h.turns.size.toString(), h.cacheRead.toString(), h.cacheCreate.toString(), h.busts.toString())
            append(table(cols, rows))
            for (h in report) {
                h.turns.forEachIndexed { i, t ->
                    t.bust?.let { append("  bust turn ${i + 1} cause=$it cache_create=${t.cacheCreate} cache_read=${t.cacheRead}\n") }
                }
            }
            if (report.isEmpty()) append("no usage rows (run `rtok proxy` first)\n")
        }
}
