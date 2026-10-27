// The Graph page's drill-down in Compose: state in the URL (`p`, `x`, `fp`, `fn`, `d`, with history entries so back
// steps through it), the breadcrumb, focus depth, the 2D scene on Canvas (click a node), the node list with editor
// links, and the projects' removal dry run (`project_plan`).
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.CHANGE_MARK
import rtok.web.ui.CHANGE_TONE
import rtok.web.ui.DEPTH_MAX
import rtok.web.ui.DGraph
import rtok.web.ui.DNode
import rtok.web.ui.DrillFocus
import rtok.web.ui.DrillState
import rtok.web.ui.Marked
import rtok.web.ui.breadcrumb
import rtok.web.ui.diffProjectOf
import rtok.web.ui.drillRequest
import rtok.web.ui.drillSearch
import rtok.web.ui.edgeId
import rtok.web.ui.editorLink
import rtok.web.ui.forceLayout
import rtok.web.ui.markGraph
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.openSymbol
import rtok.web.ui.parseDrill
import rtok.web.ui.parseDrillGraph
import rtok.web.ui.str
import rtok.web.ui.toggleFile
import kotlin.math.hypot

private const val DRILL_LIMIT = 400
private val DRILL_KEYS = listOf("p", "x", "fp", "fn", "d")

@JsFun("(k) => new URL(window.location.href).searchParams.getAll(k).join('\\u0000')")
private external fun queryAll(key: String): String

@JsFun("(s) => encodeURIComponent(s)")
private external fun encode(s: String): String

/** The drill-down's replies and state across recompositions. */
class DrillController(
    private val app: AppState,
) {
    var graph by mutableStateOf<DGraph?>(null)
    var diff by mutableStateOf<JsonObject?>(null)
    var imported by mutableStateOf<Pair<String, JsonObject>?>(null)
    var plan by mutableStateOf<Pair<String, String>?>(null)
    var compareRef by mutableStateOf("HEAD")
    private var asked: String? = null

    init {
        app.on("graph") { graph = parseDrillGraph(it.body.obj("graph")) }
        app.on("diff") { diff = it.body.obj("diff") }
        app.on("imported") { imported = (it.body.str("name") ?: "") to (it.body.obj("export") ?: JsonObject(emptyMap())) }
        app.on("project_plan") { plan = (it.body["project"]?.toString()?.trim('"') ?: "") to (it.body.str("plan") ?: "") }
    }

    /** The drill state of the current URL. */
    fun state(): DrillState? {
        app.query.length
        return parseDrill(DRILL_KEYS.associateWith { k -> queryAll(k).split('\u0000').filter { it.isNotEmpty() } })
    }

    /** Moves the drill to [s] (null: the overview) with a history entry. */
    fun go(s: DrillState?) {
        val url =
            org.w3c.dom.url
                .URL(window.location.href)
        DRILL_KEYS.forEach { url.searchParams.delete(it) }
        drillSearch(s).forEach { (k, v) -> url.searchParams.append(k, v) }
        window.history.pushState(null, "", url.href)
        app.query = window.location.search
        if (s == null) diff = null
    }

    /** Asks for the frame of [s] once per distinct request and index version. */
    fun ask(
        s: DrillState,
        version: String,
    ) {
        val req = drillRequest(s, DRILL_LIMIT)
        if (asked == req + version) return
        asked = req + version
        app.send(req)
    }

    fun send(text: String) = app.send(text)

    /** The removal dry run of [id], when one came back. */
    fun planFor(id: Long): String? = plan?.takeIf { it.first == id.toString() }?.second
}

/** Opens the drill-down of project [id]. */
fun AppState.openDrill(id: Long) = drill.go(DrillState(id.toString()))

/** The removal dry-run text of project [id], when there is one. */
@Composable
fun RemovalPlan(
    app: AppState,
    id: Long,
) {
    val text = app.drill.planFor(id) ?: return
    Txt("Removal plan (dry run); nothing changed yet:", color = LocalPalette.current.warn)
    Txt(text, size = 12)
}

/** The drill view, when the URL has one; true when it took the page. */
@Composable
fun DrillView(
    app: AppState,
    rows: List<JsonObject>,
): Boolean {
    val c = app.drill
    val s = c.state() ?: return false
    val row = rows.firstOrNull { it.num("id")?.toLong()?.toString() == s.project }
    val ix = row?.obj("index")
    val version = "${ix?.num("rows")}/${ix?.num("files")}/${ix?.num("pending")}/${ix?.str("indexed_at")}"
    SideEffect { c.ask(s, version) }
    Breadcrumb(c, s, row?.str("name") ?: "project ${s.project}")
    DrillBody(c, s)
    return true
}

@Composable
private fun DrillBody(
    c: DrillController,
    s: DrillState,
) {
    val g = c.graph?.takeIf { it.project.toString() == s.project }
    if (g == null) {
        Muted("loading the drill-down…")
        return
    }
    val dp = diffProjectOf(c.diff, g.name)
    val m = markGraph(g, dp, s.expand)
    Panel("drill-down", "${g.nodes.size} nodes · ${g.edges.size} edges" + if (g.more > 0) " · ${g.more} more" else "") {
        if (g.state != "ok") Txt("index ${g.state}", color = LocalPalette.current.warn)
        if (g.partial) Muted("partial: capped at $DRILL_LIMIT nodes")
        FocusTools(c, s)
        NodeList(c, s, g, m)
    }
    DrillPanels(c, s, dp)
}

@Composable
private fun Breadcrumb(
    c: DrillController,
    s: DrillState,
    name: String,
) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    val p = LocalPalette.current
    breadcrumb(s, name).forEach { cr ->
        if (cr.overview || cr.to != null) {
            Box(Modifier.clickable { c.go(cr.to) }) { Txt(cr.label, color = p.accent) }
            Muted("/")
        } else {
            Txt(cr.label, bold = true)
        }
    }
}

@Composable
private fun FocusTools(
    c: DrillController,
    s: DrillState,
) {
    val f = s.focus ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted("focus ${f.name} · depth")
        for (d in 1..DEPTH_MAX) Action(d.toString(), d != s.depth) { c.go(s.copy(depth = d)) }
    }
}

/** A node picked in the list or the scene: a file toggles, a symbol is focused (in its own project). */
internal fun openIn(
    c: DrillController,
    s: DrillState,
    g: DGraph,
    n: DNode,
) = when {
    n.kind == "file" -> c.go(toggleFile(s, n.path))
    n.project != g.project -> c.go(openSymbol(n.project, DrillFocus(n.path, n.label)))
    else -> c.go(s.copy(focus = DrillFocus(n.path, n.label)))
}

@Composable
private fun NodeList(
    c: DrillController,
    s: DrillState,
    g: DGraph,
    m: Marked,
) {
    val uri = LocalUriHandler.current
    val p = LocalPalette.current
    m.graph.nodes.forEach { n ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            m.nodes[n.id]?.let { Pill(CHANGE_TONE[it] ?: "muted", "${CHANGE_MARK[it]} $it") }
            Pill("muted", n.kind)
            Box(Modifier.clickable { openIn(c, s, g, n) }) { Txt(n.label, color = p.accent) }
            Box(Modifier.clickable { uri.openUri(editorLink(g.root, n.path, n.line, ::encode)) }) {
                Muted("${n.path}${if (n.line > 0) ":${n.line}" else ""}")
            }
        }
    }
}
