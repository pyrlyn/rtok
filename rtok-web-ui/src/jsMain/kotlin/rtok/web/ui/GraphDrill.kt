// Port of graph3d/DrillView.tsx, DrillPanel.tsx, ComparePanel.tsx, ExportMenu.tsx and ImportedView.tsx: the Graph
// page's drill-down (state in the URL), compare with change marks, JSON/SVG export and the imported view, plus the
// projects' remove dry run (`project_plan`). PNG export waits on a decision (docs/web.md).
package rtok.web.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.input
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.FileReader
import org.w3c.files.get

private const val DRILL_LIMIT = 400
private const val PLAN_KEY = "project"

/** The drill-down's replies across renders. */
object GraphDrill {
    internal var app: App? = null
    private var asked: String? = null
    private var graph: DGraph? = null
    internal var diff: JsonObject? = null
    internal var imported: Pair<String, JsonObject>? = null
    private var plan: Pair<String, String>? = null
    internal var compareRef = "HEAD"

    fun install(a: App) {
        app = a
        Replies.on("graph") { f ->
            graph = parseDrillGraph(f.body.obj("graph"))
            a.refresh()
        }
        Replies.on("diff") { f ->
            diff = f.body.obj("diff")
            a.refresh()
        }
        Replies.on("export") { f -> f.body.obj("file")?.let(::download) }
        Replies.on("imported") { f ->
            imported = (f.body.str("name") ?: "") to (f.body.obj("export") ?: JsonObject(emptyMap()))
            a.refresh()
        }
        Replies.on("project_plan") { f ->
            plan = (f.body[PLAN_KEY]?.toString()?.trim('"') ?: "") to (f.body.str("plan") ?: "")
            a.refresh()
        }
    }

    /** The drill state of the current URL. */
    fun state(): DrillState? {
        val q = URL(window.location.href).searchParams
        val keys = listOf("p", "x", "fp", "fn", "d")
        return parseDrill(keys.associateWith { k -> q.getAll(k).toList() })
    }

    /** Moves the drill to [s] (null: the overview) with a history entry, so back steps through it. */
    fun go(s: DrillState?) {
        val url = URL(window.location.href)
        listOf("p", "x", "fp", "fn", "d").forEach { url.searchParams.delete(it) }
        drillSearch(s).forEach { (k, v) -> url.searchParams.append(k, v) }
        window.history.pushState(null, "", url.href)
        if (s == null) diff = null
        app?.refresh()
    }

    /** Asks for the frame of [s] once per distinct request, and again when the index moved ([version]). */
    fun ask(
        s: DrillState,
        version: String,
    ) {
        val req = drillRequest(s, DRILL_LIMIT)
        if (asked == req + version) return
        asked = req + version
        app?.send(req)
    }

    private fun download(file: JsonObject) {
        val el = document.createElement("a") as HTMLAnchorElement
        el.href = "data:${file.str("mime")};base64,${file.str("data")}"
        el.download = file.str("name") ?: "graph"
        el.click()
    }

    /** The remove dry run of [id], when one came back. */
    fun planFor(id: Long): String? = plan?.takeIf { it.first == id.toString() }?.second

    fun FlowContent.drill(
        s: DrillState,
        row: JsonObject?,
    ) {
        val name = row?.str("name") ?: "project ${s.project}"
        val ix = row?.obj("index")
        ask(s, "${ix?.num("rows")}/${ix?.num("files")}/${ix?.num("pending")}/${ix?.str("indexed_at")}")
        div("toolbar") {
            attributes["aria-label"] = "breadcrumb"
            breadcrumb(s, name).forEach { c ->
                if (c.overview || c.to != null) {
                    a("#", classes = "go") {
                        onClickFunction = { e ->
                            e.preventDefault()
                            go(c.to)
                        }
                        +c.label
                    }
                    +" / "
                } else {
                    b { +c.label }
                }
            }
        }
        val g = graph?.takeIf { it.project.toString() == s.project }
        if (g == null) {
            p("muted") { +"loading the drill-down…" }
            return
        }
        val dp = diffProjectOf(diff, g.name)
        val m = markGraph(g, dp, s.expand)
        panel("drill-down", "${g.nodes.size} nodes · ${g.edges.size} edges" + if (g.more > 0) " · ${g.more} more" else "") {
            if (g.state != "ok") p("warn small") { +"index ${g.state}" }
            if (g.partial) p("muted small") { +"partial: capped at $DRILL_LIMIT nodes" }
            focusTools(s)
            drillScene(m)
            nodeList(s, g, m)
        }
        compare(s, dp)
        exportImport(s)
    }

    private fun FlowContent.focusTools(s: DrillState) {
        val f = s.focus ?: return
        div("toolbar") {
            span("muted") { +"focus ${f.name} · depth " }
            for (d in 1..DEPTH_MAX) {
                button(classes = if (d == s.depth) "on" else "") {
                    onClickFunction = { go(s.copy(depth = d)) }
                    +d.toString()
                }
            }
        }
    }

    private fun FlowContent.nodeList(
        s: DrillState,
        g: DGraph,
        m: Marked,
    ) {
        ul("plain") {
            attributes["aria-label"] = "drill nodes"
            m.graph.nodes.forEach { n ->
                li {
                    m.nodes[n.id]?.let { pill(CHANGE_TONE[it] ?: "muted", "${CHANGE_MARK[it]} $it") }
                    pill("muted", n.kind)
                    +" "
                    button(classes = "link") {
                        onClickFunction = {
                            when {
                                n.kind == "file" -> go(toggleFile(s, n.path))
                                n.project != g.project -> go(openSymbol(n.project, DrillFocus(n.path, n.label)))
                                else -> go(s.copy(focus = DrillFocus(n.path, n.label)))
                            }
                        }
                        +n.label
                    }
                    a(editorLink(g.root, n.path, n.line) { js("encodeURIComponent")(it) as String }, classes = "muted small") {
                        +" ${n.path}${if (n.line > 0) ":${n.line}" else ""}"
                    }
                }
            }
        }
    }

    /** The remove dry-run text for a project, when there is one. */
    fun FlowContent.plan(id: Long) {
        val text = planFor(id) ?: return
        div("plan") {
            p("warn small") { +"Removal plan (dry run); nothing changed yet:" }
            pre { code { +text } }
        }
    }
}
