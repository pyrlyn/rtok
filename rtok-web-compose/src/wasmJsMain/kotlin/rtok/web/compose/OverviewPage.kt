// The Overview page in Compose: alerts, saved tokens, KPI tiles, calls over time, savings and recent sessions.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.web.ui.OverviewModel
import rtok.web.ui.SURFACES
import rtok.web.ui.compact
import rtok.web.ui.fixed
import rtok.web.ui.fmt
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.pct
import rtok.web.ui.str

private const val SEC = 1000.0
private const val TURNS = 40
private const val SESSION_ID = 8

/** The Overview page. */
val OverviewPage =
    PageView { snap, _ ->
        val o = OverviewModel(snap, nowMs() / SEC)
        val p = LocalPalette.current
        val colors = listOf(p.accent, p.ok, p.warn)
        (o.usage["alerts"] as? JsonArray)?.forEach { a ->
            Panel("alert") { Txt((a as? JsonPrimitive)?.content ?: a.toString(), color = p.warn) }
        }
        Panel("Δ saved tok", "est ${compact(o.estBefore)} → ${compact(o.estAfter)} · ${o.measured.size} plugins with Measurement rows") {
            Txt("Δ ${compact(o.saved)}  ·  ${pct(o.deltaPct)}", size = 24, bold = true)
            ShareBar(if (o.estBefore > 0) o.estAfter / o.estBefore else 0.0)
        }
        val turns = (o.usage["turns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }.orEmpty().takeLast(TURNS)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("input tok", compact(o.u("input")), "ctx ${compact(o.ctx)} incl. cache") { Sparkline(turns, "ctx tokens per turn") }
            Kpi("output tok", compact(o.u("output")), "cache create ${compact(o.u("cache_create"))}")
            Kpi("cache hit", pct(o.cacheHit), "read ${compact(o.u("cache_read"))} of ctx")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("calls", fmt(o.calls.size.toDouble()), "${o.failed} failed · p95 ${o.p95?.let { "${fixed(it, 0)} ms" } ?: "-"}") {
                StackedBars(o.buckets.map { listOf(it.total.toDouble()) }, listOf(p.accent), "calls per bucket", Modifier.fillMaxWidth().then(Modifier))
            }
            Kpi("live sessions", "${o.live} / ${o.sessions.size}", "${o.hosts} hosts") { Sparkline(o.liveSeries.map { it.toDouble() }, "sessions alive") }
            Kpi("plugins on", "${o.enabled} / ${o.plugins.size}", "${o.plugins.size - o.enabled} disabled")
        }
        Panel("calls over time", "${o.calls.size} calls · by surface") {
            StackedBars(o.buckets.map { b -> SURFACES.map { b.bySurface.getValue(it).toDouble() } }, colors, "calls per bucket by surface")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SURFACES.forEachIndexed { i, s ->
                    val (n, err) = o.bySurface.getValue(s)
                    Txt("● $s $n${if (err > 0) " · $err failed" else ""}", color = colors[i])
                }
            }
        }
        SavingsTrend(o.usage.objects("savings"))
        Panel("savings by plugin", "Σ est before − after, measured plugins only") {
            val max = o.measured.maxOfOrNull { it.saved }?.coerceAtLeast(1.0) ?: 1.0
            Table(
                o.measured,
                listOf(
                    Col("plugin") { Txt(it.plugin.str("id") ?: "", bold = true) },
                    Col("rows") { Txt(fmt(it.plugin.obj("stats")?.num("rows"))) },
                    Col("saved") { Txt(compact(it.saved)) },
                    Col("share", 2f) { ShareBar(it.saved / max) },
                ),
                "No measured savings yet. Plugins fill this in as they record Measurement rows.",
            )
        }
        Panel("doctor", "${o.checks.count { it.st == "pass" }}/${o.checks.size} pass") {
            o.checks.forEach { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(c.st, c.st)
                    Txt(c.label, bold = true)
                    Muted(c.detail)
                }
            }
        }
        Panel("recent sessions") { RecentTable(o.recent) }
    }

@Composable
fun SavingsTrend(days: List<JsonObject>) {
    val rows = days.sumOf { it.num("rows") ?: 0.0 }
    Panel("savings trend", "${days.size} days · ${fmt(rows)} Measurement rows") {
        if (days.none { it.num("saved") != null }) {
            Muted("No Measurement rows in the window yet.")
        } else {
            Sparkline(days.map { it.num("saved") }, "tokens saved per day")
            Muted("${days.first().str("day") ?: ""} … ${days.last().str("day") ?: ""}")
        }
    }
}

@Composable
private fun RecentTable(rows: List<JsonObject>) =
    Column {
        Table(
            rows,
            listOf(
                Col("session") { Txt(it.str("id")?.take(SESSION_ID) ?: "") },
                Col("host") { Txt(it.str("host") ?: "-") },
                Col("model") { Txt(it.str("model") ?: "-") },
                Col("project", 2f) { Txt(it.str("project") ?: "-") },
                Col("state") { if (it.num("ended_at") == null) Pill("ok", "live") else Pill("info", "ended") },
            ),
            "No sessions yet. A session appears with its first hook.",
        )
    }
