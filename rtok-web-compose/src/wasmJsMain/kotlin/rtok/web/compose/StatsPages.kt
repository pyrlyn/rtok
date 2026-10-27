// Stats and Skills in Compose: `rtok stats --price` + `--cache` parsed into KPIs and tables, and the skills
// listing with a detail panel (`?q=`, `?never=`, `?sel=`).
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.HealthRow
import rtok.web.ui.LONG_BODY_BYTES
import rtok.web.ui.LONG_DESC_CHARS
import rtok.web.ui.PricedRow
import rtok.web.ui.SkillRow
import rtok.web.ui.StatsView
import rtok.web.ui.compact
import rtok.web.ui.costTotals
import rtok.web.ui.fixed
import rtok.web.ui.fmt
import rtok.web.ui.matchesSkill
import rtok.web.ui.objects
import rtok.web.ui.parseStats
import rtok.web.ui.skillRow
import rtok.web.ui.skillWarnings
import rtok.web.ui.str
import kotlin.math.round

private fun n(v: String?) = v?.toDoubleOrNull()

private fun money(v: Double?) = if (v == null) "-" else fixed(v, 2)

/** A page whose snapshot field is not sent yet. */
@Composable
fun Pending(
    field: String,
    command: String,
) = Panel(field, "not ready") { Muted("The server sends no $field data yet. `$command` has it on the command line.") }

@Composable
private fun StatsKpis(v: StatsView) {
    val a = v.totals
    val u = v.usage
    val t = costTotals(v)
    Muted("${fmt(n(a["lines"]))} transcript lines · ${a["malformed"] ?: "?"} malformed · ${a["no_checkpoint"] ?: "?"} sessions without checkpoint")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Kpi("sessions", fmt(n(a["sessions"])), "${a["compact"] ?: "?"} compact · ${a["checkpoint"] ?: "?"} checkpoint")
        Kpi("cache hit", u["hit"] ?: "-", "read ${compact(n(u["cache_read"]))}")
        Kpi("input", compact(n(u["input"])), "cache create ${compact(n(u["cache_create"]))}")
        Kpi("output", compact(n(u["output"])), "median ctx ${compact(n(u["median_context"]))}")
        Kpi("cost", "$${fixed(t.cost, 2)}", "at [stats.prices] $/MTok")
        Kpi("saved", "$${fixed(t.saved, 2)}", "cache reads priced")
    }
}

/** The Stats page. */
val StatsPage =
    PageView { snap, app ->
        TextPage(app, snap, "stats", "rtok stats --price", "`rtok stats --price` plus `rtok stats --cache`, as one report") { text ->
            val v = parseStats(text)
            StatsKpis(v)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel("cost per model", "stats --price", Modifier.weight(7f)) {
                    Table(
                        v.priced,
                        listOf(
                            Col<PricedRow>("model", 2f) { Txt(it.model, bold = true) },
                            Col("input") { Txt(compact(it.input)) },
                            Col("output") { Txt(compact(it.output)) },
                            Col("cost $") { r -> if (r.cost == null) Muted("-") else Txt(money(r.cost), bold = true) },
                            Col("saved $") { r -> if (r.saved == null) Muted("-") else Txt(money(r.saved), color = LocalPalette.current.ok) },
                        ),
                        "No priced usage rows. Run the proxy to record usage.",
                    )
                    Muted("- = no price row; rtok never guesses a price")
                }
                Panel("cache health", "stats --cache", Modifier.weight(5f)) {
                    Table(
                        v.health,
                        listOf(
                            Col<HealthRow>("session", 2f) { Txt(it.session.take(13)) },
                            Col("turns") { Txt(fmt(it.turns)) },
                            Col("cache read") { Txt(compact(it.cacheRead)) },
                            Col("busts") { r -> (r.busts ?: 0.0).let { if (it > 0) Pill("warn", fmt(it)) else Muted("0") } },
                        ),
                        "No cache health rows. Run `rtok proxy` first.",
                    )
                    v.busts.forEach { x ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("warn", "bust")
                            Txt("turn ${x.turn} · cause ${x.cause}")
                            Muted("cache create ${compact(x.cacheCreate)} · read ${compact(x.cacheRead)}")
                        }
                    }
                }
            }
            if (v.other.isNotEmpty()) Panel("other lines", "not charted yet, verbatim") { Txt(v.other.joinToString("\n")) }
        }
        (snap["usage"] as? JsonObject)?.let { SavingsTrend(it.objects("savings")) }
    }

@Composable
private fun SkillDetail(
    r: SkillRow,
    modifier: Modifier,
) = Panel(r.name, r.source, modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val warnings = skillWarnings(r)
        if (warnings.isEmpty()) Pill("ok", "ok") else warnings.forEach { Pill(if (it == "never invoked") "muted" else "warn", it) }
    }
    Txt("source ${r.source}")
    Txt("desc chars ${fmt(r.descChars.toDouble())}  ≈ ${fmt(round(r.descChars / 4.0))} tok per request")
    Txt("body bytes ${fmt(r.bodyBytes.toDouble())}")
    Txt("invocations ${fmt(r.invocations.toDouble())} · resident ${fmt(r.resident.toDouble())}")
    Txt("last invoked ${r.lastInvoked}")
    Muted("tokens per request use chars / 4, the estimate research.md §10.2 documents")
}

/** The Skills page. */
val SkillsPage =
    PageView { snap, app ->
        val page = snap["skills"] as? JsonObject
        if (page == null) {
            Pending("skills", "rtok skills list")
            return@PageView
        }
        val all = page.objects("rows").map(::skillRow)
        val rows = all.filter { matchesSkill(it, app.param("never", "all") == "never invoked only", app.param("q", "")) }
        val selected = all.firstOrNull { it.name == app.param("sel", "") } ?: all.firstOrNull()
        Toolbar {
            SearchBox(app, "name or source")
            Toggles(app, "never", listOf("all", "never invoked only"))
            Muted("${rows.size} of ${all.size}")
        }
        if (all.isEmpty()) {
            Panel("skills") { Muted("No skills listed. The host lists no skills for this session yet.") }
            return@PageView
        }
        page.str("header")?.takeIf { it.isNotEmpty() }?.let { Muted(it) }
        val p = LocalPalette.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("skills", "listed by the host", Modifier.weight(1f)) {
                Table(
                    rows,
                    listOf(
                        Col<SkillRow>("skill", 3f) { r ->
                            Box(Modifier.clickable { app.setParam("sel", r.name, "") }) {
                                Txt("${r.name}  ${r.source}", color = if (r.never) p.muted else p.fg, bold = true)
                            }
                        },
                        Col("desc") { Txt(fmt(it.descChars.toDouble()), color = if (it.descChars > LONG_DESC_CHARS) p.warn else null) },
                        Col("body") { Txt("${compact(it.bodyBytes.toDouble())} B", color = if (it.bodyBytes > LONG_BODY_BYTES) p.warn else null) },
                        Col("calls") { Txt(fmt(it.invocations.toDouble())) },
                    ),
                    "No skill matches. Clear the filter to see every skill.",
                )
            }
            selected?.let { SkillDetail(it, Modifier.weight(1f)) }
        }
    }
