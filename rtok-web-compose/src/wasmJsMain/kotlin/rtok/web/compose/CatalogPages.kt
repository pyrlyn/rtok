// Plugins and Usage in Compose: the plugin catalogue with switches and detail (`?sel=`), and `rtok agents usage`.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.web.ui.PLUGIN_SHOW
import rtok.web.ui.UsageLine
import rtok.web.ui.compact
import rtok.web.ui.flag
import rtok.web.ui.fmt
import rtok.web.ui.matchesPlugin
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.pct
import rtok.web.ui.periodKind
import rtok.web.ui.pluginToggle
import rtok.web.ui.savedOf
import rtok.web.ui.str
import rtok.web.ui.strings
import rtok.web.ui.unpricedTokens
import rtok.web.ui.usageLines
import rtok.web.ui.usageSource
import rtok.web.ui.usd
import rtok.web.ui.wholePct

private const val NO_SAVING = "n/a"
private const val NO_ROWS = "Unknown"

/** A switch: the server owns the state, a click only asks. */
@Composable
private fun Switch(
    app: AppState,
    p: JsonObject,
) {
    val id = p.str("id") ?: return
    val on = p.flag("enabled")
    val pal = LocalPalette.current
    Box(
        Modifier
            .background(if (on) pal.ok.copy(alpha = 0.2f) else pal.border, RoundedCornerShape(99.dp))
            .clickable { app.send(pluginToggle(id, !on)) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Txt(if (on) "on" else "off", color = if (on) pal.ok else pal.muted, size = 12) }
}

private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.content ?: e?.toString() ?: ""

@Composable
private fun PluginDetail(
    app: AppState,
    p: JsonObject,
    modifier: Modifier,
) {
    val stats = p.obj("stats")
    val saved = savedOf(p)
    Panel(p.str("title") ?: "", p.str("id"), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(app, p)
            Txt(if (p.flag("enabled")) "enabled" else "disabled")
            p.strings("surfaces").forEach { Pill("info", it) }
        }
        Muted(p.str("summary") ?: "")
        (p["fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }.forEach { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(text(f.getOrNull(0)))
                Txt(text(f.getOrNull(1)))
            }
        }
        val rows = stats?.num("rows") ?: 0.0
        if (p.flag("saves_tokens") && stats != null) {
            val before = stats.num("est_before") ?: 0.0
            Txt("rows ${fmt(stats.num("rows"))} · est ${compact(before)} → ${compact(stats.num("est_after"))}")
            Txt("saved " + if (rows > 0 && saved != null) "${compact(saved)} (${pct(saved / before, 0)})" else NO_ROWS)
        } else {
            Muted(if (p.flag("saves_tokens")) "no Measurement rows yet" else "does not record Measurement rows")
        }
        Muted("""toggle sends {"set":{"key":"plugins.${p.str("id")}.enabled"}}""")
    }
}

/** The Plugins page. */
val PluginsPage =
    PageView { snap, app ->
        val plugins = snap.objects("plugins")
        if (plugins.isEmpty()) {
            Panel("plugins") { Muted("No plugins in this frame") }
            return@PageView
        }
        val rows = plugins.filter { matchesPlugin(it, app.param("show", "all"), app.param("q", "")) }
        val selected = plugins.firstOrNull { it.str("id") == app.param("sel", "") } ?: plugins.first()
        Toolbar {
            SearchBox(app, "id, title, summary")
            Toggles(app, "show", PLUGIN_SHOW.map { it.first })
            Muted("${rows.size} shown")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("plugins", "catalogue", Modifier.weight(1f)) {
                Table(
                    rows,
                    listOf(
                        Col<JsonObject>("on", 0.5f) { Switch(app, it) },
                        Col("plugin", 3f) {
                            Box(Modifier.clickable { app.setParam("sel", it.str("id") ?: "", "") }) {
                                Txt((it.str("title") ?: it.str("id") ?: "") + "  " + it.strings("surfaces").joinToString(" · "), bold = true)
                            }
                        },
                        Col("rows") { Txt(fmt(it.obj("stats")?.num("rows"))) },
                        Col("saved") {
                            val s = savedOf(it)
                            when {
                                s == null || !it.flag("saves_tokens") -> Muted(NO_SAVING)
                                (it.obj("stats")?.num("rows") ?: 0.0) > 0 -> Txt(compact(s))
                                else -> Muted(NO_ROWS)
                            }
                        },
                    ),
                    "No plugin matches. Clear the filter or pick another group.",
                )
            }
            PluginDetail(app, selected, Modifier.weight(1f))
        }
    }

/** The Usage page. */
val UsagePage =
    PageView { snap, _ ->
        val page = snap.obj("agent_usage")
        val text = page?.str("text").orEmpty()
        val r = page?.obj("report")
        when {
            r == null -> Panel("usage", "rtok agents usage") { Muted(text.trim()) }
            r["through"] == null || r["through"] is JsonNull -> Panel("usage") { Muted("No usage recorded. ${text.trim()}") }
            else -> UsageReport(r)
        }
    }

@Composable
private fun UsageReport(r: JsonObject) {
    Muted("${usageSource(r.str("source"))} · up to ${r.str("through")} (${r.str("tz")})")
    UsageKpis(r)
    Unpriced(r)
    val byModel = r["models"] is JsonArray
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(if (byModel) "per model" else "per agent", "agents usage", Modifier.weight(1f)) {
            Table(usageLines(r), lineColumns(r, byModel), "No usage rows. Run an agent first.")
        }
        Periods(r, Modifier.weight(1f))
    }
    val skipped = r.objects("skipped")
    if (skipped.isNotEmpty()) {
        Panel("skipped hosts", "files exist but could not be read") {
            skipped.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("warn", s.str("reason") ?: "")
                    Txt(s.str("host") ?: "", bold = true)
                    Txt(s.str("path") ?: "")
                }
            }
        }
    }
}

private fun lineColumns(
    r: JsonObject,
    byModel: Boolean,
): List<Col<UsageLine>> {
    val cols =
        mutableListOf<Col<UsageLine>>(
            Col(if (byModel) "model" else "agent", 2f) { Txt(it.label, bold = true) },
            Col("tokens") { Txt(compact(it.tokens)) },
            Col("est. cost") { l -> l.cost?.let { Txt(usd(it), bold = true) } ?: Muted("-") },
        )
    if (!byModel && r.str("source") == "both") {
        cols += Col("via rtok") { Txt(compact(it.throughRtok)) }
        cols += Col("coverage") { Txt(wholePct(it.coverage)) }
    }
    if (!byModel && r.str("source") != "logs") {
        cols += Col("saved") { Txt(compact(it.savedTokens)) }
        cols += Col("saved est.") { Txt(usd(it.savedUsd), color = LocalPalette.current.ok) }
    }
    return cols
}

@Composable
private fun Periods(
    r: JsonObject,
    modifier: Modifier,
) {
    val kind = periodKind(r)
    val rows = r.objects("periods")
    val max = maxOf(1.0, rows.maxOfOrNull { it.num("tokens") ?: 0.0 } ?: 0.0)
    Panel("${kind.lowercase()} totals", if (kind == "Daily") "--daily" else "--monthly", modifier) {
        Table(
            rows,
            listOf(
                Col<JsonObject>(if (kind == "Daily") "day" else "month") { Txt(it.str("period") ?: "", bold = true) },
                Col("tokens") { Txt(compact(it.num("tokens"))) },
                Col("est. cost") { Txt(usd(it.num("cost_usd"))) },
                Col("share", 2f) { ShareBar((it.num("tokens") ?: 0.0) / max) },
            ),
            "No periods. Nothing falls in the window.",
        )
    }
}

@Composable
private fun UsageKpis(r: JsonObject) {
    val t = r.obj("totals") ?: JsonObject(emptyMap())
    val unpriced = r.num("unpriced_models") ?: 0.0
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Kpi(
            "tokens",
            compact(t.num("tokens")),
            "in ${compact(t.num("input"))} · out ${compact(t.num("output"))} · cache ${compact((t.num("cache_read") ?: 0.0) + (t.num("cache_write") ?: 0.0))}",
        )
        Kpi("estimated cost", usd(t.num("cost_usd")), if (unpriced > 0) "incomplete: ${fmt(unpriced)} unpriced" else "at [stats.prices] \$/MTok")
        Kpi("sessions", fmt(t.num("sessions")), "")
        Kpi("daily rows", fmt(t.num("daily_rows")), "agent-days with usage")
        t.num("saved_tokens")?.let { s -> Kpi("rtok saved", compact(s), t.num("saved_usd")?.let { "≈ ${usd(it)}" } ?: "no price for an estimate") }
    }
}

@Composable
private fun Unpriced(r: JsonObject) {
    val unpriced = r.num("unpriced_models") ?: 0.0
    val n = unpriced.toInt()
    if (n > 0) {
        Panel("cost is incomplete", "rtok agents usage --unpriced") {
            Txt(
                "${if (n == 1) "1 model has" else "$n models have"} no price in [stats.prices], so ${compact(unpricedTokens(r))} tokens are not in the estimate.",
                color = LocalPalette.current.warn,
            )
            r.objects("unpriced").forEach { u ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("warn", "no price")
                    Txt(u.str("model") ?: "", bold = true)
                    Muted("${u.str("host")} · ${compact(u.num("tokens"))} tokens")
                }
            }
        }
    }
}
