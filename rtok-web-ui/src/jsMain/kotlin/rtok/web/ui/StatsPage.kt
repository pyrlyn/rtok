// Port of web/src/pages/Stats.tsx: the `stats` Snapshot field (`rtok stats --price` plus `--cache` as one text) parsed
// into KPIs, the cost-per-model and cache-health tables and the busts, with the raw text one toggle away, plus the
// savings trend from `usage.savings`.
@file:Suppress("MagicNumber")

package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject

private fun n(v: String?) = v?.toDoubleOrNull()

private fun money(v: Double?) = if (v == null) "-" else fixed(v, 2)

private fun FlowContent.statsKpis(v: StatsView) {
    val a = v.totals
    val u = v.usage
    val t = costTotals(v)
    p("muted small") {
        +"${fmt(n(a["lines"]))} transcript lines · ${a["malformed"] ?: "?"} malformed · ${a["no_checkpoint"] ?: "?"} sessions without checkpoint"
    }
    div("grid kpis") {
        kpi("sessions", fmt(n(a["sessions"])), "${a["compact"] ?: "?"} compact · ${a["checkpoint"] ?: "?"} checkpoint")
        kpi("cache hit", u["hit"] ?: "-", "read ${compact(n(u["cache_read"]))}")
        kpi("input", compact(n(u["input"])), "cache create ${compact(n(u["cache_create"]))}")
        kpi("output", compact(n(u["output"])), "median ctx ${compact(n(u["median_context"]))}")
        kpi("cost", "$${fixed(t.cost, 2)}", "at [stats.prices] $/MTok")
        kpi("saved", "$${fixed(t.saved, 2)}", "cache reads priced")
    }
}

private fun FlowContent.priced(v: StatsView) {
    panel("cost per model", "stats --price", null, "span7") {
        dataTable(
            "cost per model",
            v.priced,
            listOf(
                Column("model") { r -> b { +r.model } },
                Column("input", right = true) { r -> +compact(r.input) },
                Column("output", right = true) { r -> +compact(r.output) },
                Column("cost $", right = true) { r -> if (r.cost == null) span("muted") { +"-" } else b { +money(r.cost) } },
                Column("saved $", right = true) { r -> span(if (r.saved == null) "muted" else "delta") { +money(r.saved) } },
            ),
            "No priced usage rows. Run the proxy to record usage.",
        )
        p("muted small") {
            code { +"-" }
            +" = no price row; rtok never guesses a price"
        }
    }
}

private fun FlowContent.health(v: StatsView) {
    panel("cache health", "stats --cache", null, "span5") {
        dataTable(
            "cache health",
            v.health,
            listOf(
                Column("session") { r -> b("mono") { +r.session.take(13) } },
                Column("turns", right = true) { r -> +fmt(r.turns) },
                Column("cache read", right = true) { r -> +compact(r.cacheRead) },
                Column("busts", right = true) { r ->
                    val b = r.busts ?: 0.0
                    if (b > 0) pill("warn", fmt(b)) else span("muted") { +"0" }
                },
            ),
            "No cache health rows. Run `rtok proxy` first.",
        )
        if (v.busts.isNotEmpty()) {
            ul("plain") {
                attributes["aria-label"] = "cache busts"
                v.busts.forEach { x ->
                    li {
                        pill("warn", "bust")
                        +" turn ${x.turn} · cause "
                        b { +x.cause }
                        span("muted") { +" · cache create ${compact(x.cacheCreate)} · read ${compact(x.cacheRead)}" }
                    }
                }
            }
        }
    }
}

/** The Stats page. */
val StatsPage =
    PageView { out, snap, app ->
        out.textPage(app, snap, "stats", "rtok stats --price", "`rtok stats --price` plus `rtok stats --cache`, as one report") { text ->
            val v = parseStats(text)
            statsKpis(v)
            div("grid wide") {
                priced(v)
                health(v)
            }
            otherLines(v.other)
        }
        (snap["usage"] as? JsonObject)?.let { out.savingsTrend(it.objects("savings")) }
    }
