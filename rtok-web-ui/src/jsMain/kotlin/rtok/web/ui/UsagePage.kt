// Port of web/src/pages/Usage.tsx: `rtok agents usage` as KPIs, per-agent (or per-model) and per-period
// tables with share bars, the unpriced-models alert and the skipped hosts.
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

private fun FlowContent.usageKpis(r: JsonObject) {
    val t = r.obj("totals") ?: JsonObject(emptyMap())
    val unpriced = r.num("unpriced_models") ?: 0.0
    div("grid kpis") {
        kpi(
            "tokens",
            compact(t.num("tokens")),
            "in ${compact(t.num("input"))} · out ${compact(t.num("output"))} · " +
                "cache ${compact((t.num("cache_read") ?: 0.0) + (t.num("cache_write") ?: 0.0))}",
        )
        kpi("estimated cost", usd(t.num("cost_usd")), if (unpriced > 0) "incomplete: ${fmt(unpriced)} unpriced" else "at [stats.prices] \$/MTok")
        kpi("sessions", fmt(t.num("sessions")), "")
        kpi("daily rows", fmt(t.num("daily_rows")), "agent-days with usage")
        t.num("saved_tokens")?.let { s ->
            kpi("rtok saved", compact(s), t.num("saved_usd")?.let { "≈ ${usd(it)}" } ?: "no price for an estimate")
        }
    }
}

private fun FlowContent.unpricedAlert(r: JsonObject) {
    val n = (r.num("unpriced_models") ?: 0.0).toInt()
    if (n <= 0) return
    panel("cost is incomplete", "rtok agents usage --unpriced", cls = "warn") {
        p("warn") {
            +"${if (n == 1) "1 model has" else "$n models have"} no price in [stats.prices], so "
            +"${compact(unpricedTokens(r))} tokens are not in the estimate."
        }
        ul("plain") {
            attributes["aria-label"] = "unpriced models"
            r.objects("unpriced").forEach { u ->
                li {
                    pill("warn", "no price")
                    b { +" ${u.str("model")} " }
                    span("muted") { +"${u.str("host")} · ${compact(u.num("tokens"))} tokens" }
                }
            }
        }
    }
}

private fun lineColumns(r: JsonObject): List<Column<UsageLine>> {
    val byModel = r["models"] is kotlinx.serialization.json.JsonArray
    val cols =
        mutableListOf<Column<UsageLine>>(
            Column(if (byModel) "model" else "agent") { l -> b { +l.label } },
            Column("tokens", right = true) { l ->
                span {
                    attributes["title"] = fmt(l.tokens)
                    +compact(l.tokens)
                }
            },
            Column("est. cost", right = true) { l ->
                if (l.cost == null) {
                    span("muted") {
                        attributes["title"] = "no price in [stats.prices]"
                        +"-"
                    }
                } else {
                    b { +usd(l.cost) }
                }
            },
        )
    if (!byModel && r.str("source") == "both") {
        cols += Column("via rtok", right = true) { l -> +compact(l.throughRtok) }
        cols += Column("coverage", right = true) { l -> +wholePct(l.coverage) }
    }
    if (!byModel && r.str("source") != "logs") {
        cols += Column("saved", right = true) { l -> +compact(l.savedTokens) }
        cols += Column("saved est.", right = true) { l -> span("ok") { +usd(l.savedUsd) } }
    }
    return cols
}

private fun FlowContent.periods(r: JsonObject) {
    val kind = periodKind(r)
    val rows = r.objects("periods")
    val max = maxOf(1.0, rows.maxOfOrNull { it.num("tokens") ?: 0.0 } ?: 0.0)
    panel("${kind.lowercase()} totals", if (kind == "Daily") "--daily" else "--monthly") {
        dataTable(
            "${kind.lowercase()} usage",
            rows,
            listOf(
                Column(if (kind == "Daily") "day" else "month") { p -> b { +(p.str("period") ?: "") } },
                Column("tokens", right = true) { p -> +compact(p.num("tokens")) },
                Column("est. cost", right = true) { p -> +usd(p.num("cost_usd")) },
                Column("share") { p ->
                    val share = (p.num("tokens") ?: 0.0) / max
                    shareBar(share, "${p.str("period")}: ${fmt(p.num("tokens"))} tokens, ${wholePct(share)} of the busiest period")
                },
            ),
            "No periods. Nothing falls in the window.",
        )
    }
}

private fun FlowContent.report(r: JsonObject) {
    p("muted small") { +"${usageSource(r.str("source"))} · up to ${r.str("through")} (${r.str("tz")})" }
    usageKpis(r)
    unpricedAlert(r)
    val byModel = r["models"] is kotlinx.serialization.json.JsonArray
    div("split") {
        panel(if (byModel) "per model" else "per agent", "agents usage") {
            dataTable(if (byModel) "usage per model" else "usage per agent", usageLines(r), lineColumns(r), "No usage rows. Run an agent first.")
        }
        periods(r)
    }
    val skipped = r.objects("skipped")
    if (skipped.isNotEmpty()) {
        panel("skipped hosts", "files exist but could not be read") {
            ul("plain") {
                attributes["aria-label"] = "unreadable hosts"
                skipped.forEach { s ->
                    li {
                        pill("warn", s.str("reason") ?: "")
                        b { +" ${s.str("host")} " }
                        code { +(s.str("path") ?: "") }
                    }
                }
            }
        }
    }
}

/** The Usage page. */
val UsagePage =
    PageView { out, snap, _ ->
        val page = snap.obj("agent_usage")
        val text = page?.str("text").orEmpty()
        val r = page?.obj("report")
        when {
            r == null -> {
                out.panel("usage", "rtok agents usage") { p("muted") { +text.trim() } }
            }

            r["through"] == null || r["through"] is kotlinx.serialization.json.JsonNull -> {
                out.panel("usage") { p("muted") { +"No usage recorded. ${text.trim()}" } }
            }

            else -> {
                out.report(r)
            }
        }
    }
