// Port of web/src/pages/Graph.tsx and Projects.tsx: health alerts, the project registry with select,
// re-index, remove (plan first, then confirmed) and links, and the `rtok graph status` body. The 3D
// explorer, compare and export follow in later steps.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.UL
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject

private fun FlowContent.alerts(rows: List<JsonObject>) {
    val all =
        rows.flatMap { it.objects("alerts") }.map {
            GraphAlert(it.str("root") ?: "", it.str("project") ?: "", it.str("kind") ?: "", it.str("detail") ?: "", it.num("since") ?: 0.0)
        }
    if (all.isEmpty()) return
    panel("graph alerts", "${all.size} raised", cls = "warn") {
        ul("plain") {
            attributes["aria-label"] = "graph alerts"
            groupAlerts(all).forEach { g ->
                li {
                    pill("warn", ALERT_WORDS[g.kind] ?: g.kind)
                    span { +" ${describeGroup(g) { hms(it).take(HH_MM) }}" }
                }
            }
        }
    }
}

private const val HH_MM = 5

private fun FlowContent.action(
    app: App,
    label: String,
    request: String,
    disabled: Boolean = false,
) = button {
    if (disabled) attributes["disabled"] = "disabled"
    onClickFunction = { app.send(request) }
    +label
}

private fun UL.projectRow(
    app: App,
    p: JsonObject,
) {
    val id = p.num("id")?.toLong() ?: return
    val state = p.str("state") ?: ""
    val (tone, hint) = projectState(state)
    li(if (p.flag("selected")) "row on" else "row") {
        button(classes = "link") {
            attributes["aria-pressed"] = p.flag("selected").toString()
            if (p.flag("missing")) attributes["disabled"] = "disabled"
            onClickFunction = { app.send(projectRequest("select", mapOf("project" to id))) }
            b { +(p.str("name") ?: "") }
        }
        span {
            attributes["title"] = hint
            pill(tone, state)
        }
        p.obj("health")?.let { h ->
            val level = h.str("level") ?: levelOf(h.num("score") ?: 0.0)
            +" "
            healthRing(h.num("score"), level, "health ${healthScore(p)?.let { fixed(it, 0) } ?: level}")
        }
        +" "
        button(classes = "link") {
            onClickFunction = { GraphDrill.go(openProject(id.toLong())) }
            +"drill →"
        }
        span("muted small") { +" ${p.str("origin") ?: ""}" }
    }
}

private fun FlowContent.current(
    app: App,
    p: JsonObject,
) {
    val id = p.num("id")?.toLong() ?: return
    val (tone, hint) = projectState(p.str("state") ?: "")
    div("current") {
        attributes["aria-label"] = "current project"
        p {
            b { +(p.str("name") ?: "") }
            +" "
            pill(tone, p.str("state") ?: "")
            span("muted small") { +" $hint" }
        }
        p("muted small") { +(p.str("root") ?: "") }
        p.str("error")?.let { p("fail small") { +it } }
        p.obj("index")?.let { ix ->
            div("grid kpis") {
                kpi("rows", fmt(ix.num("rows")), "")
                kpi("files", fmt(ix.num("files")), "")
                kpi("pending", fmt(ix.num("pending")), if ((ix.num("pending") ?: 0.0) > 0) "stale" else "fresh")
            }
        }
        div("toolbar") {
            action(app, "re-index", projectRequest("reindex", mapOf("project" to id)), p.flag("missing"))
            action(app, "plan removal", projectRequest("remove", mapOf("project" to id, "confirmed" to false)))
            action(app, "remove from registry", projectRequest("remove", mapOf("project" to id, "confirmed" to true)))
        }
        with(GraphDrill) { plan(id) }
        links(app, p, id)
    }
}

private fun FlowContent.links(
    app: App,
    p: JsonObject,
    id: Long,
) {
    val links = p.objects("links")
    if (links.isEmpty()) {
        p("muted") { +"${p.str("name")} is not linked to another project." }
    } else {
        ul("plain") {
            attributes["aria-label"] = "links"
            links.forEach { l ->
                li {
                    b { +(l.str("name") ?: "") }
                    +" "
                    pill("info", l.str("kind") ?: "")
                    l.str("reason")?.let { span("muted small") { +" $it" } }
                    +" "
                    action(app, "unlink", projectRequest("unlink", mapOf("from" to id, "to" to (l.num("to")?.toLong() ?: 0), "both" to false)))
                }
            }
        }
    }
}

private fun FlowContent.projects(
    app: App,
    rows: List<JsonObject>,
) {
    panel("projects", "${rows.size} registered") {
        if (rows.isEmpty()) {
            p("muted") { +"No projects. Run `rtok graph projects add <path>`." }
            return@panel
        }
        ul("plain") {
            attributes["aria-label"] = "projects"
            rows.forEach { projectRow(app, it) }
        }
        val sel = rows.firstOrNull { it.flag("selected") }
        if (sel == null) p("muted") { +"No project selected." } else current(app, sel)
    }
}

private fun FlowContent.graphBody(v: GraphView) {
    val scoped = v.dead.any { it.project != null }
    p("muted small") { +"root ${v.root}" }
    div("grid kpis") {
        kpi("rows", fmt(v.rows?.toDouble()), "tree-sitter-tags defs + refs")
        kpi("files", fmt(v.files?.toDouble()), "")
        kpi("pending", fmt(v.pending.size.toDouble()), if (v.pending.isEmpty()) "index is fresh" else "stale since last index")
        kpi("watch", if (v.watch) "on" else "off", "indexed ${v.indexedAt}")
    }
    div("split") {
        panel("pending files") {
            if (v.pending.isEmpty()) p("muted") { +"Nothing pending" }
            ul("plain") {
                v.pending.forEach { f ->
                    li {
                        pill("warn", "pending")
                        f.project?.let { pill("info", it) }
                        +" ${f.path}"
                    }
                }
            }
        }
        panel("dead symbols", "${v.dead.size} unreferenced") {
            val cols = mutableListOf<Column<DeadSymbol>>()
            if (scoped) cols += Column("project") { r -> r.project?.let { pill("info", it) } }
            cols += Column("symbol") { r -> b { +r.name } }
            cols += Column("kind") { r -> pill("muted", r.kind) }
            cols += Column("location") { r -> span("muted") { +"${r.path}:${r.line}" } }
            dataTable("dead symbols", v.dead, cols, "No unreferenced definitions")
            v.deadNote?.let { p("muted small") { +it } }
        }
    }
    otherLines(v.other)
}

/** The Graph page. */
val GraphPage =
    PageView { out, snap, app ->
        val rows = snap.objects("projects")
        out.alerts(rows)
        out.projects(app, rows)
        val drill = GraphDrill.state()
        if (drill != null) {
            with(GraphDrill) { out.drill(drill, rows.firstOrNull { it.num("id")?.toLong()?.toString() == drill.project }) }
            return@PageView
        }
        if (rows.isNotEmpty()) out.liveCalls()
        if (snap["graph"] == null || snap["graph"] is kotlinx.serialization.json.JsonNull) {
            out.panel("graph") {
                p("muted") { +"Graph page off. The graph feature is not built in, or the store read failed (Snapshot.graph = null)." }
            }
        } else {
            out.textPage(app, snap, "graph", "rtok graph status", "`rtok graph status` index health plus `rtok graph dead`") { text ->
                graphBody(parseGraph(text))
            }
        }
    }
