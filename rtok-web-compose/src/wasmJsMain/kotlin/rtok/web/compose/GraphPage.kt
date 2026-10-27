// The Graph page in Compose: grouped health alerts, the project registry (select, re-index, removal plan then
// confirmed removal, links, health rings) and the `rtok graph status` body. Drill, compare, export, the removal
// dry run, the scene and live calls come in the next slices.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.ALERT_WORDS
import rtok.web.ui.DeadSymbol
import rtok.web.ui.GraphAlert
import rtok.web.ui.GraphView
import rtok.web.ui.describeGroup
import rtok.web.ui.fixed
import rtok.web.ui.flag
import rtok.web.ui.fmt
import rtok.web.ui.groupAlerts
import rtok.web.ui.healthScore
import rtok.web.ui.healthTone
import rtok.web.ui.levelOf
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.parseGraph
import rtok.web.ui.projectRequest
import rtok.web.ui.projectState
import rtok.web.ui.str

/** A health ring: the score's share of a circle in the level's tone. */
@Composable
fun HealthRing(
    score: Double?,
    level: String,
    title: String,
) {
    val p = LocalPalette.current
    val color = p.tone(healthTone(level)).takeIf { healthTone(level) != "muted" } ?: p.muted
    val frac = ((score ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat()
    Canvas(Modifier.size(22.dp).semantics { contentDescription = title }) {
        val st = Stroke(3.dp.toPx())
        val inset = st.width / 2
        val box = Size(size.width - st.width, size.height - st.width)
        drawArc(p.border, 0f, 360f, false, Offset(inset, inset), box, style = st)
        drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), box, style = st)
    }
}

@Composable
private fun Alerts(rows: List<JsonObject>) {
    val all =
        rows.flatMap { it.objects("alerts") }.map {
            GraphAlert(it.str("root") ?: "", it.str("project") ?: "", it.str("kind") ?: "", it.str("detail") ?: "", it.num("since") ?: 0.0)
        }
    if (all.isEmpty()) return
    Panel("graph alerts", "${all.size} raised") {
        groupAlerts(all).forEach { g ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("warn", ALERT_WORDS[g.kind] ?: g.kind)
                Txt(describeGroup(g) { hms(it).take(5) })
            }
        }
    }
}

@Composable
private fun ProjectRow(
    app: AppState,
    p: JsonObject,
) {
    val id = p.num("id")?.toLong() ?: return
    val state = p.str("state") ?: ""
    val (tone, _) = projectState(state)
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(!p.flag("missing")) { app.send(projectRequest("select", mapOf("project" to id))) }) {
            Txt(p.str("name") ?: "", color = if (p.flag("selected")) pal.accent else pal.fg, bold = true)
        }
        Pill(tone, state)
        p.obj("health")?.let { h ->
            val level = h.str("level") ?: levelOf(h.num("score") ?: 0.0)
            HealthRing(h.num("score"), level, "health ${healthScore(p)?.let { fixed(it, 0) } ?: level}")
        }
        Muted(p.str("origin") ?: "")
    }
}

@Composable
private fun Current(
    app: AppState,
    p: JsonObject,
) {
    val id = p.num("id")?.toLong() ?: return
    val (tone, hint) = projectState(p.str("state") ?: "")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(p.str("name") ?: "", bold = true)
        Pill(tone, p.str("state") ?: "")
        Muted(hint)
    }
    Muted(p.str("root") ?: "")
    p.str("error")?.let { Txt(it, color = LocalPalette.current.fail) }
    p.obj("index")?.let { ix ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("rows", fmt(ix.num("rows")), "")
            Kpi("files", fmt(ix.num("files")), "")
            Kpi("pending", fmt(ix.num("pending")), if ((ix.num("pending") ?: 0.0) > 0) "stale" else "fresh")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("re-index", !p.flag("missing")) { app.send(projectRequest("reindex", mapOf("project" to id))) }
        Action("plan removal") { app.send(projectRequest("remove", mapOf("project" to id, "confirmed" to false))) }
        Action("remove from registry") { app.send(projectRequest("remove", mapOf("project" to id, "confirmed" to true))) }
    }
    ProjectLinks(app, p, id)
}

@Composable
private fun Projects(
    app: AppState,
    rows: List<JsonObject>,
) = Panel("projects", "${rows.size} registered") {
    if (rows.isEmpty()) {
        Muted("No projects. Run `rtok graph projects add <path>`.")
        return@Panel
    }
    rows.forEach { ProjectRow(app, it) }
    val sel = rows.firstOrNull { it.flag("selected") }
    if (sel == null) Muted("No project selected.") else Current(app, sel)
}

@Composable
private fun GraphBody(v: GraphView) {
    Muted("root ${v.root}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Kpi("rows", fmt(v.rows?.toDouble()), "tree-sitter-tags defs + refs")
        Kpi("files", fmt(v.files?.toDouble()), "")
        Kpi("pending", fmt(v.pending.size.toDouble()), if (v.pending.isEmpty()) "index is fresh" else "stale since last index")
        Kpi("watch", if (v.watch) "on" else "off", "indexed ${v.indexedAt}")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("pending files", modifier = Modifier.weight(1f)) {
            if (v.pending.isEmpty()) Muted("Nothing pending")
            v.pending.forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("warn", "pending")
                    f.project?.let { Pill("info", it) }
                    Txt(f.path)
                }
            }
        }
        Panel("dead symbols", "${v.dead.size} unreferenced", Modifier.weight(2f)) {
            val cols = mutableListOf<Col<DeadSymbol>>()
            if (v.dead.any { it.project != null }) cols += Col("project") { r -> r.project?.let { Pill("info", it) } }
            cols += Col("symbol", 2f) { Txt(it.name, bold = true) }
            cols += Col("kind") { Pill("muted", it.kind) }
            cols += Col("location", 2f) { Muted("${it.path}:${it.line}") }
            Table(v.dead, cols, "No unreferenced definitions")
            v.deadNote?.let { Muted(it) }
        }
    }
    if (v.other.isNotEmpty()) Panel("other lines", "not charted yet, verbatim") { Txt(v.other.joinToString("\n")) }
}

/** The Graph page. */
val GraphPage =
    PageView { snap, app ->
        val rows = snap.objects("projects")
        Alerts(rows)
        Projects(app, rows)
        if (snap["graph"] == null || snap["graph"] is JsonNull) {
            Panel("graph") { Muted("Graph page off. The graph feature is not built in, or the store read failed (Snapshot.graph = null).") }
        } else {
            TextPage(app, snap, "graph", "rtok graph status", "`rtok graph status` index health plus `rtok graph dead`") { GraphBody(parseGraph(it)) }
        }
    }

@Composable
private fun ProjectLinks(
    app: AppState,
    p: JsonObject,
    id: Long,
) {
    val links = p.objects("links")
    if (links.isEmpty()) Muted("${p.str("name")} is not linked to another project.")
    links.forEach { l ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(l.str("name") ?: "", bold = true)
            Pill("info", l.str("kind") ?: "")
            l.str("reason")?.let { Muted(it) }
            Action("unlink") { app.send(projectRequest("unlink", mapOf("from" to id, "to" to (l.num("to")?.toLong() ?: 0), "both" to false))) }
        }
    }
}
