// Port of src/tui/view.rs, first slice: tabs, the current page and the key line, drawn into a tui.Buffer.
// Tables for plugins, calls, sessions and logs; the CLI text pages verbatim; pages whose backend is not
// ported say so.
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tui.Buffer
import tui.Col
import tui.Color
import tui.Rect
import tui.Size
import tui.Span
import tui.Style
import tui.block
import tui.follow
import tui.paragraph
import tui.rows
import tui.sparkline
import tui.table
import tui.tabs

private const val DIGIT_TABS = 9
private const val SPARK_LABEL = 14
internal val ACCENT = Style(fg = Color.Cyan)
internal val MUTED = Style(dim = true)
private val FAIL = Style(fg = Color.Red)

private val COMMANDS =
    mapOf(
        "doctor" to "rtok doctor",
        "stats" to "rtok stats",
        "skills" to "rtok skills list",
        "services" to "rtok demon status",
        "worktrees" to "rtok worktree list",
        "tasks" to "rtok tasks",
    )

/** app.rs `KEYS` of the pages that edit, after the global ones in the key line. */
private val PAGE_KEYS =
    mapOf(
        "plugins" to "Space/Enter toggle",
        "hosts" to "c clear safe junk",
        "calls" to "Enter/z detail · e expand · / filter expand",
        "sessions" to "Enter detail · l live only",
        "config" to "/ filter",
        "graph" to "s select · l link · u unlink · b both · Enter pick · y confirm · n/Esc decline · c compare · e export · v view export · d drill · i re-index · x remove · f freeze calls · w window · a/t/o filter calls",
    )

private fun objects(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

/** Draws the whole screen for [app] into [b]; [clock] formats unix seconds for the time columns. */
fun draw(
    b: Buffer,
    app: TuiApp,
    clock: (Double) -> String,
) {
    val (top, body, foot) = b.area.rows(Size.Fixed(1), Size.Fill(), Size.Fixed(1))
    b.tabs(top, TABS.mapIndexed { i, t -> if (i < DIGIT_TABS) "${i + 1} ${t.first}" else t.first }, app.tab)
    val inner = b.block(body, app.page, ACCENT)
    if (app.help) help(b, inner) else page(b, inner, app, clock)
    val err = app.snapshot.s("error")
    val keys = "q quit · ←/→ tab · 1-9 jump · ↑/↓ row · r refresh · ? help" + (PAGE_KEYS[app.page]?.let { " · $it" } ?: "")
    val status = app.pluginStatus.takeIf { app.page == "plugins" && it.isNotEmpty() }
    when {
        err != null -> b.put(foot.x, foot.y, "store: $err", FAIL, foot.w)

        status != null -> b.put(foot.x, foot.y, status, ACCENT, foot.w)

        // view.rs: the loading mark leads the key line while a model read is on the worker.
        app.loading -> b.put(foot.x, foot.y, "◌ loading  $keys", tui.Style(fg = tui.Color.Yellow), foot.w)

        else -> b.put(foot.x, foot.y, keys, MUTED, foot.w)
    }
}

private fun help(
    b: Buffer,
    r: Rect,
) {
    val lines =
        listOf(
            "q, Esc, Ctrl+C   quit",
            "←/→, Tab         previous / next tab (wraps)",
            "1..9             jump to a tab",
            "↑/↓, j/k         move the row cursor; PgUp/PgDn, Home/End",
            "r                re-read the model now",
            "?                this help",
            "Space/Enter      plugins: toggle the plugin under the cursor",
            "c                hosts: clear safe junk (plan); y removes, n/Esc declines",
            "e                calls: expand the archived output; / filters it, Esc/e closes",
            "Enter/z          calls: detail pane of the selected call",
            "l                sessions: live-only filter; Enter: detail pane",
            "/                config: filter entries; Esc/Enter stops typing",
            "s / l / u        graph: select, link to…, unlink from… the project under the cursor",
            "b, Enter         graph: both ways; pick the target",
            "y, n/Esc         graph: confirm the plan; decline",
            "d, i, x          graph: drill, re-index, remove…",
            "e, v             graph: export the graph to a file; view a saved export (read-only)",
            "f, w             graph: freeze / unfreeze the live calls; cycle the window (1, 5, 15 min)",
            "a, t, o          graph: filter the live calls by caller, tool, project",
            "c                graph: compare with a ref or a saved export; Enter runs, Esc closes",
        )
    b.paragraph(r, lines.map { listOf(Span(it)) })
}

private fun page(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    clock: (Double) -> String,
) {
    val junk = if (app.page == "hosts") app.actions?.let { app.junk.panel(it) } else null
    when {
        junk != null -> junkPanel(b, r, app, junk)
        app.page == "graph" -> graphPage(b, r, app, clock)
        else -> field(b, r, app, clock)
    }
}

private fun field(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    clock: (Double) -> String,
) {
    val v = app.snapshot[app.field]
    when {
        v == null || v is JsonNull -> notReady(b, r, app.page)
        app.page == "overview" && v is JsonObject -> overview(b, r, v)
        app.page == "plugins" -> grid(b, r, app, PLUGIN_COLS, objects(v).map(::pluginRow))
        app.page == "calls" -> calls(b, r, app, objects(v).map { callRow(it, clock) })
        app.page == "sessions" -> sessions(b, r, app, clock)
        app.page == "logs" -> text(b, r, app, (v as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content ?: it.toString() })
        v is JsonPrimitive -> text(b, r, app, pageLines(app, v.content))
        v is JsonObject && v["text"] is JsonPrimitive -> text(b, r, app, v.s("text").orEmpty().lines())
        else -> text(b, r, app, v.toString().lines())
    }
}

private fun notReady(
    b: Buffer,
    r: Rect,
    page: String,
) {
    val cmd = COMMANDS[page] ?: "rtok $page"
    b.paragraph(
        r,
        listOf(
            listOf(Span("not ready", Style(fg = Color.Yellow, bold = true))),
            listOf(Span("This page is not ported to the Kotlin build yet: the model sends no $page data.", MUTED)),
            listOf(Span("`$cmd` has it on the command line.", MUTED)),
        ),
    )
}

private fun overview(
    b: Buffer,
    r: Rect,
    o: JsonObject,
) {
    val turns = (o["turns"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
    val lines =
        listOf(
            listOf(Span("input ", MUTED), Span(compact(o.n("input"))), Span("   output ", MUTED), Span(compact(o.n("output")))),
            listOf(Span("cache read ", MUTED), Span(compact(o.n("cache_read"))), Span("   cache create ", MUTED), Span(compact(o.n("cache_create")))),
            listOf(Span("calls ", MUTED), Span(compact(o.n("calls"))), Span("   ctt ", MUTED), Span(compact(o.n("ctt")))),
            listOf(Span("ctx per turn ", MUTED), Span(sparkline(turns, (r.w - SPARK_LABEL).coerceAtLeast(0)), ACCENT)),
            emptyList(),
        ) +
            (o["alerts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.map { listOf(Span("! $it", Style(fg = Color.Yellow))) }
    b.paragraph(r, lines)
}

internal fun grid(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    cols: List<Col>,
    rows: List<List<String>>,
) {
    if (rows.isEmpty()) {
        b.paragraph(r, listOf(listOf(Span("No ${app.page} yet.", MUTED))))
        return
    }
    val visible = r.h - 1
    b.table(r, cols, rows, app.cursor, follow(app.cursor, 0, visible))
}

internal fun text(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    lines: List<String>,
) {
    val scroll = follow(app.cursor, 0, r.h).coerceAtMost((lines.size - r.h).coerceAtLeast(0))
    b.paragraph(r, lines.map { listOf(Span(it)) }, scroll)
}

private fun junkPanel(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    panel: Pair<Pair<String, String>, String>,
) {
    val (head, tone) = panel.first
    val color =
        when (tone) {
            "ok" -> Style(fg = Color.Green, bold = true)
            "warn" -> Style(fg = Color.Yellow, bold = true)
            else -> Style(dim = true, bold = true)
        }
    val lines = listOf(listOf(Span(head, color))) + panel.second.lines().map { listOf(Span(it)) }
    b.paragraph(r, lines, follow(app.cursor, 0, r.h).coerceAtMost((lines.size - r.h).coerceAtLeast(0)))
}
