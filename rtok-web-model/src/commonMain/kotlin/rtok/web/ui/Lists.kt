// Port of the list pages' filters in web/src/pages (model.ts parseLog/matchesLog/matchesSession, Calls.tsx
// matchesCall, model.ts tokensOf).
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** Σ tokens a call put through the model; null when the call carried no usage (model.ts `tokensOf`). */
fun tokensOf(c: JsonObject): Double? = c.num("input")?.let { it + (c.num("cache_create") ?: 0.0) + (c.num("cache_read") ?: 0.0) + (c.num("output") ?: 0.0) }

private fun hay(vararg parts: String?) = parts.joinToString(" ") { it ?: "null" }.lowercase()

/** Calls.tsx `matchesCall`: surface (`all` or one), result (`any`, `ok`, `failed`) and a text query. */
fun matchesCall(
    c: JsonObject,
    surface: String,
    result: String,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (surface == "all" || c.str("surface") == surface) &&
        (result == "any" || (result == "ok") == c.flag("ok")) &&
        (q.isEmpty() || q in hay(c.str("name"), c.str("plugin"), c.str("session"), c.str("kind"), c.str("model")))
}

/** model.ts `matchesSession`. */
fun matchesSession(
    s: JsonObject,
    liveOnly: Boolean,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (!liveOnly || s.num("ended_at") == null) &&
        (q.isEmpty() || q in hay(s.str("id"), s.str("host"), s.str("model"), s.str("project"), s.str("provider")))
}

/** One log line (model.ts `LogLine`); [i] is its position in the newest-first frame. */
data class LogLine(
    val i: Int,
    val raw: String,
    val ts: String,
    val level: String,
    val source: String,
    val name: String,
    val msg: String,
)

private val LOG_RE = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (\S+) ([^/\s]+)/([^:]+): (.*)$""")

/** A line not in the `ts LEVEL source/name: msg` shape is kept whole, as info (model.ts `parseLog`). */
fun parseLog(
    raw: String,
    i: Int,
): LogLine {
    val m = LOG_RE.find(raw) ?: return LogLine(i, raw, "", "info", "", "", raw)
    val (ts, level, source) = m.groupValues.drop(1)
    val (name, msg) = m.groupValues.takeLast(2)
    return LogLine(i, raw, ts, level.lowercase(), source, name, msg)
}

val LEVELS = listOf("all", "info", "warn", "error")

/** model.ts `matchesLog`. */
fun matchesLog(
    l: LogLine,
    level: String,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (level == "all" || l.level == level) && (q.isEmpty() || q in l.raw.lowercase())
}
