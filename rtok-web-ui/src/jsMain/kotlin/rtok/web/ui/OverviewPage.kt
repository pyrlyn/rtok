// Port of web/src/pages/Overview.tsx and OverviewPanels.tsx: alerts, the saved-tokens headline, KPI tiles,
// calls over time, token mix, the savings trend, savings by plugin, doctor and recent sessions.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.js.Date

private val SURFACE_COLORS = listOf("var(--accent)", "var(--ok)", "var(--warn)")
private const val SEC = 1000.0
private const val TURNS = 40

/** The Overview page. */
val OverviewPage =
    PageView { out, snap, _ ->
        val o = OverviewModel(snap, Date.now() / SEC)
        out.div("stack") {
            (o.usage["alerts"] as? JsonArray)?.forEach { a ->
                div("card alert") {
                    attributes["role"] = "alert"
                    pill("warn", "alert")
                    +" ${(a as? kotlinx.serialization.json.JsonPrimitive)?.content ?: a}"
                }
            }
            saved(o)
            div("grid kpis") { kpis(o) }
            div("grid wide") {
                panel("calls over time", "${o.calls.size} calls · by surface", "calls", "span8") {
                    stackedBars(o.buckets.map { b -> SURFACES.map { b.bySurface.getValue(it).toDouble() } }, SURFACE_COLORS, "calls per bucket by surface")
                    div("legend") {
                        SURFACES.forEachIndexed { i, s ->
                            val (n, err) = o.bySurface.getValue(s)
                            span {
                                unsafeDot(SURFACE_COLORS[i])
                                +"$s $n${if (err > 0) " · $err failed" else ""}"
                            }
                        }
                    }
                }
                val total = o.u("input") + o.u("cache_create") + o.u("cache_read") + o.u("output")
                panel("token mix", "${fmt(total)} tokens in the ledger window", null, "span4") {
                    mixBar(
                        listOf(
                            Triple("input", o.u("input"), "var(--accent)"),
                            Triple("cache create", o.u("cache_create"), "var(--warn)"),
                            Triple("cache read", o.u("cache_read"), "var(--ok)"),
                            Triple("output", o.u("output"), "var(--fg-muted)"),
                        ),
                    )
                }
                savingsTrend(o.usage.objects("savings"))
                panel("savings by plugin", "Σ est before − after, measured plugins only", "plugins", "span7") { savingsTable(o) }
                panel("doctor", "${o.checks.count { it.st == "pass" }}/${o.checks.size} pass", "doctor", "span5") {
                    o.checks.forEach { c ->
                        div("check") {
                            pill(toneOf(c.st), c.st)
                            b { +" ${c.label}" }
                            span("muted") { +" ${c.detail}" }
                        }
                    }
                }
                panel("recent sessions", null, "sessions", "span12") { recentTable(o.recent) }
            }
        }
    }

private fun FlowContent.unsafeDot(color: String) = consumer.onTagContentUnsafe { +"<i class=\"dot\" style=\"background:$color\"></i>" }

private fun FlowContent.saved(o: OverviewModel) {
    section("card saved") {
        attributes["aria-label"] = "tokens saved"
        div {
            p("kicker") { +"Δ saved tok" }
            p("big") {
                span("delta") { +"Δ" }
                +" ${compact(o.saved)}"
            }
            p("muted small") { +"est ${compact(o.estBefore)} → ${compact(o.estAfter)}" }
        }
        div {
            p("kicker") { +"Δtok %" }
            p("big accent") { +pct(o.deltaPct) }
            p("muted small") { +"${o.measured.size} plugins with Measurement rows" }
        }
        div("budget") {
            p("small") { +"est before" }
            shareBar(1.0, "est before ${compact(o.estBefore)}", "var(--fg-muted)")
            p("small") { +"after" }
            shareBar(if (o.estBefore > 0) o.estAfter / o.estBefore else 0.0, "after ${compact(o.estAfter)}", "var(--accent)")
        }
    }
}

private fun FlowContent.kpis(o: OverviewModel) {
    val turns = (o.usage["turns"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }.orEmpty().takeLast(TURNS)
    val perBucket = o.buckets.map { it.total.toDouble() }
    kpi("input tok", compact(o.u("input")), "ctx ${compact(o.ctx)} incl. cache") { sparkline(turns, "ctx tokens per turn") }
    kpi("output tok", compact(o.u("output")), "cache create ${compact(o.u("cache_create"))}")
    kpi("cache hit", pct(o.cacheHit), "read ${compact(o.u("cache_read"))} of ctx")
    kpi("calls", fmt(o.calls.size.toDouble()), "${o.failed} failed · p95 ${o.p95?.let { "${fixed(it, 0)} ms" } ?: "-"}") {
        miniBars(perBucket, "calls per bucket, ${o.buckets.firstOrNull()?.let { hms(it.t) } ?: ""} on")
    }
    kpi("live sessions", "${o.live} / ${o.sessions.size}", "${o.hosts} hosts") { sparkline(o.liveSeries.map { it.toDouble() }, "sessions alive over the calls window") }
    kpi("plugins on", "${o.enabled} / ${o.plugins.size}", "${o.plugins.size - o.enabled} disabled")
}

internal fun FlowContent.savingsTrend(days: List<JsonObject>) {
    val rows = days.sumOf { it.num("rows") ?: 0.0 }
    panel("savings trend", "${days.size} days · ${fmt(rows)} Measurement rows", null, "span12") {
        if (days.none { it.num("saved") != null }) {
            p("muted") { +"No Measurement rows in the window yet." }
        } else {
            gapLine(days.map { it.num("saved") }, "tokens saved per day, ${days.first().str("day")} to ${days.last().str("day")}")
            div("legend") {
                span { +(days.first().str("day") ?: "") }
                span { +(days.last().str("day") ?: "") }
            }
        }
    }
}

private fun FlowContent.savingsTable(o: OverviewModel) {
    val max = o.measured.maxOfOrNull { it.saved }?.coerceAtLeast(1.0) ?: 1.0
    dataTable(
        "savings by plugin",
        o.measured,
        listOf(
            Column("plugin") { b { +(it.plugin.str("id") ?: "") } },
            Column("rows", right = true) { +fmt(it.plugin.obj("stats")?.num("rows")) },
            Column("saved", right = true) { +compact(it.saved) },
            Column("share") { shareBar(it.saved / max, "${it.plugin.str("id")}: ${pct(it.saved / max, 0)} of the top plugin") },
        ),
        "No measured savings yet. Plugins fill this in as they record Measurement rows.",
    )
}

private fun FlowContent.recentTable(rows: List<JsonObject>) =
    dataTable(
        "recent sessions",
        rows,
        listOf(
            Column("session") { s -> +(s.str("id")?.take(SESSION_ID) ?: "") },
            Column("host") { s -> +(s.str("host") ?: "-") },
            Column("model") { s -> +(s.str("model") ?: "-") },
            Column("project") { s -> +(s.str("project") ?: "-") },
            Column("state") { s -> if (s.num("ended_at") == null) pill("ok", "live") else pill("info", "ended") },
            Column("last", right = true) { s -> +(s.num("last_activity")?.let(::hms) ?: "-") },
        ),
        "No sessions yet. A session appears with its first hook.",
    )

private const val SESSION_ID = 8
