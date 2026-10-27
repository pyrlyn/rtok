// Port of web/src/pages/model.ts `overview` and its helpers: everything the Overview page shows, derived once
// from one snapshot.
package rtok.web.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

val SURFACES = listOf("hook", "mcp", "proxy")

/** One time bucket of calls, by surface, with the failed count. */
data class Bucket(
    val t: Double,
    val bySurface: MutableMap<String, Int> = SURFACES.associateWith { 0 }.toMutableMap(),
    var err: Int = 0,
) {
    val total get() = bySurface.values.sum()
}

/** A field of [o] as a list of objects ([] when absent). */
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

/** A field of [o] as an object, or null. */
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private const val BUCKETS = 24
private const val MIN_SPAN = 60.0
private const val EMPTY_SPAN = 3600.0

/** Calls over the window the frame carries, in [n] equal buckets (model.ts `callBuckets`). */
fun callBuckets(
    calls: List<JsonObject>,
    nowSec: Double,
    n: Int = BUCKETS,
): Pair<List<Bucket>, Double> {
    val ts = calls.mapNotNull { it.num("ts") }
    val t1 = ts.maxOrNull() ?: nowSec
    val t0 = ts.minOrNull() ?: (nowSec - EMPTY_SPAN)
    val step = max(MIN_SPAN, t1 - t0 + 1) / n
    val buckets = List(n) { Bucket(t0 + it * step) }
    for (c in calls) {
        val b = c.num("ts")?.let { t -> buckets.getOrNull(min(n - 1, floor((t - t0) / step).toInt())) } ?: continue
        val s = c.str("surface")
        if (s in SURFACES) b.bySurface[s!!] = b.bySurface.getValue(s) + 1
        if (!c.flag("ok")) b.err++
    }
    return buckets to step
}

/** A Doctor row derived from the report (model.ts `Check`). */
data class Check(
    val st: String,
    val label: String,
    val detail: String,
)

/** Doctor rows from `Report` (model.ts `doctorChecks`); null is a failed probe unless [pending]. */
fun doctorChecks(
    d: JsonObject?,
    pending: Boolean,
): List<Check> =
    when {
        d == null && pending -> listOf(Check("skip", "doctor probe", "running doctor…"))
        d == null -> listOf(Check("fail", "doctor probe", "doctor did not answer this tick; `rtok doctor` has the details"))
        else -> reportChecks(d)
    }

private fun String?.orIfBlank(alt: String) = if (isNullOrEmpty()) alt else this

private fun reportChecks(d: JsonObject): List<Check> {
    val hooks = d.num("hooks_total") ?: 0.0
    val mcp = d.objects("mcp")
    val checks =
        mutableListOf(
            Check(
                if (hooks > 0) "pass" else "fail",
                "hooks installed",
                "${fmt(hooks)} hooks across ${d.obj("hooks_by_event")?.size ?: 0} events",
            ),
            Check(
                if (mcp.isNotEmpty()) "pass" else "warn",
                "MCP servers",
                mcp.joinToString(", ") { "${it.str("name")} (${fmt(it.num("tools"))} tools)" }.ifEmpty { "none probed" },
            ),
            Check(if (d.str("proxy").isNullOrEmpty()) "warn" else "pass", "Anthropic proxy chain", d.str("proxy").orIfBlank("ANTHROPIC_BASE_URL not set")),
            Check(if (d.str("proxy_openai").isNullOrEmpty()) "skip" else "pass", "OpenAI proxy chain", d.str("proxy_openai").orIfBlank("not configured")),
        )
    if (d.flag("mcp_tool_search_disabled")) {
        val s = d.obj("mcp_tool_search")
        checks += Check("warn", "MCP tool search", "${s?.str("state")} (${s?.str("source") ?: "default"})")
    }
    listOf("bash_max_output_length" to "BASH_MAX_OUTPUT_LENGTH", "auto_compact_window" to "autoCompactWindow").forEach { (k, label) ->
        val v = d.str(k)
        checks += Check(if (v.isNullOrEmpty()) "skip" else "pass", label, v.orIfBlank("(unset)"))
    }
    checks += extraChecks(d)
    return checks
}

private fun extraChecks(d: JsonObject): List<Check> {
    val out = mutableListOf<Check>()
    val instr = d.obj("instructions")
    instr?.objects("rows")?.filter { it.flag("warn") }?.forEach {
        out += Check("warn", "instructions: ${it.str("name")}", "${fmt(it.num("tokens"))} tokens, ${it.str("path")}")
    }
    (instr?.get("duplicates") as? JsonArray)?.forEach { pair ->
        val p = pair as? JsonArray ?: return@forEach
        val text = p.getOrNull(0)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString() }
        val names = (p.getOrNull(1) as? JsonArray)?.joinToString(", ") { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString() }
        out += Check("warn", "duplicate instruction", "\"$text\" in ${names ?: p.getOrNull(1)}")
    }
    d.obj("skills")?.let { out += skillsCheck(it) }
    (d["overlaps"] as? JsonArray)?.forEach { out += Check("warn", "host overlap", (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()) }
    d.str("tools_rewrite_advice")?.takeIf { it.isNotEmpty() }?.let { out += Check("warn", "tools rewrite", it) }
    return out
}

private fun skillsCheck(skills: JsonObject): Check {
    val rows = skills.objects("rows")
    val warned = rows.filter { it.flag("warn_desc") || it.flag("warn_body") || it.flag("warn_never") }
    val detail =
        if (warned.isEmpty()) {
            "${rows.size} skills OK"
        } else {
            warned.joinToString(" · ") { r ->
                val why =
                    listOfNotNull(
                        "long description".takeIf { r.flag("warn_desc") },
                        "body > 8 KB".takeIf { r.flag("warn_body") },
                        "never invoked".takeIf { r.flag("warn_never") },
                    )
                "${r.str("name")}: ${why.joinToString(", ")}"
            }
        }
    return Check(if (warned.isEmpty()) "pass" else "warn", "skills audit", detail)
}

/** A measured plugin and what it saved. */
data class Saving(
    val plugin: JsonObject,
    val saved: Double,
)

/** Everything the Overview shows (model.ts `overview`). */
class OverviewModel(
    snap: JsonObject,
    nowSec: Double,
) {
    val usage = snap.obj("usage") ?: JsonObject(emptyMap())
    val calls = snap.objects("calls")
    val sessions = snap.objects("sessions")
    val plugins = snap.objects("plugins")
    private val measuredPlugins = plugins.filter { (it.obj("stats")?.num("est_before") ?: 0.0) > 0 }
    val estBefore = measuredPlugins.sumOf { it.obj("stats")?.num("est_before") ?: 0.0 }
    val estAfter = measuredPlugins.sumOf { it.obj("stats")?.num("est_after") ?: 0.0 }
    val saved = estBefore - estAfter
    val measured = measuredPlugins.map { Saving(it, savedOf(it) ?: 0.0) }.sortedByDescending { it.saved }
    val ctx = u("input") + u("cache_create") + u("cache_read")
    val deltaPct = if (estBefore != 0.0) saved / estBefore else Double.NaN
    val cacheHit = if (ctx != 0.0) u("cache_read") / ctx else Double.NaN
    val failed = calls.count { !it.flag("ok") }
    private val ms = calls.mapNotNull { it.num("ms") }.sorted()
    val p50 = quantile(P50)
    val p95 = quantile(P95)
    val buckets: List<Bucket>
    val step: Double

    init {
        val (b, s) = callBuckets(calls, nowSec)
        buckets = b
        step = s
    }

    val bySurface = SURFACES.associateWith { s -> calls.filter { it.str("surface") == s }.let { it.size to it.count { c -> !c.flag("ok") } } }
    val liveSeries =
        buckets.map { b ->
            sessions.count { s ->
                (s.num("started_at") ?: 0.0) <= b.t + step && (s.num("ended_at")?.let { it >= b.t } ?: true)
            }
        }
    val live = sessions.count { it.num("ended_at") == null }
    val hosts = sessions.map { it.str("host") }.toSet().size
    val enabled = plugins.count { it.flag("enabled") }
    val checks = doctorChecks(snap.obj("doctor"), snap.flag("doctor_pending"))
    val recent = sessions.sortedByDescending { it.num("last_activity") ?: 0.0 }.take(RECENT)

    fun u(key: String): Double = usage.num(key) ?: 0.0

    private fun quantile(q: Double): Double? = if (ms.isEmpty()) null else ms[min(ms.size - 1, floor(q * ms.size).toInt())]

    private companion object {
        const val P50 = 0.5
        const val P95 = 0.95
        const val RECENT = 6
    }
}

/** est_before − est_after, or null without stats (model.ts `savedOf`). */
fun savedOf(p: JsonObject): Double? = p.obj("stats")?.let { (it.num("est_before") ?: 0.0) - (it.num("est_after") ?: 0.0) }
