// The table rows of `rtok tui`'s list pages (view.rs plugins, calls and sessions tables) and number formatting.
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tui.Col

private const val THOUSAND = 1000.0
private const val MILLION = 1_000_000.0
private const val SESSION_ID = 12
private const val TENTHS = 10

internal fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

internal fun JsonObject.on(k: String) = (this[k] as? JsonPrimitive)?.content.let { it == "true" || it == "1" }

/** `1.2k` / `3.4M`, `-` for null (format.ts `compact`). */
fun compact(n: Double?): String =
    when {
        n == null -> "-"
        kotlin.math.abs(n) >= MILLION -> "${oneDecimal(n / MILLION)}M"
        kotlin.math.abs(n) >= THOUSAND -> "${oneDecimal(n / THOUSAND)}k"
        else -> n.toLong().toString()
    }

internal fun oneDecimal(x: Double): String {
    val t = kotlin.math.round(x * TENTHS).toLong()
    return "${t / TENTHS}.${kotlin.math.abs(t % TENTHS)}"
}

internal val PLUGIN_COLS = listOf(Col("on", 3), Col("plugin", 18), Col("surfaces", 14), Col("rows", 8, true), Col("saved", 8, true), Col("summary"))

internal fun pluginRow(p: JsonObject): List<String> {
    val st = p["stats"] as? JsonObject
    val saved = st?.let { (it.n("est_before") ?: 0.0) - (it.n("est_after") ?: 0.0) }
    return listOf(
        if (p.on("enabled")) "●" else "○",
        p.s("title") ?: p.s("id").orEmpty(),
        (p["surfaces"] as? JsonArray).orEmpty().joinToString(" ") { (it as JsonPrimitive).content },
        compact(st?.n("rows")),
        if (p.on("saves_tokens")) compact(saved) else "n/a",
        p.s("summary").orEmpty(),
    )
}

internal val CALL_COLS = listOf(Col("time", 8), Col("surface", 7), Col("name"), Col("ms", 8, true), Col("tokens", 8, true), Col("ok", 4))

internal fun callRow(
    c: JsonObject,
    clock: (Double) -> String,
): List<String> {
    val tokens = c.n("input")?.let { it + (c.n("cache_create") ?: 0.0) + (c.n("cache_read") ?: 0.0) + (c.n("output") ?: 0.0) }
    return listOf(
        c.n("ts")?.let(clock) ?: "-",
        c.s("surface") ?: "-",
        c.s("name") ?: c.s("plugin") ?: "Unknown",
        c.n("ms")?.let { oneDecimal(it) } ?: "-",
        compact(tokens),
        if (c.on("ok")) "ok" else "FAIL",
    )
}

internal val SESSION_COLS = listOf(Col("status", 6), Col("session", 12), Col("host", 10), Col("project"), Col("tokens", 8, true), Col("last", 8))

internal fun sessionRow(
    s: JsonObject,
    clock: (Double) -> String,
): List<String> =
    listOf(
        if (s["ended_at"] == null || s["ended_at"] is JsonNull) "live" else "ended",
        s.s("id").orEmpty().take(SESSION_ID),
        s.s("host") ?: "-",
        s.s("project") ?: "-",
        compact(listOf("input", "cache_create", "cache_read", "output").sumOf { s.n(it) ?: 0.0 }),
        s.n("last_activity")?.let(clock) ?: "-",
    )
