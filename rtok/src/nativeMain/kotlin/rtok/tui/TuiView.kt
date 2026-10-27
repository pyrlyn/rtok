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
    page(b, inner, app, clock)
    if (app.help) helpOverlay(b, body, app)
    val err = app.snapshot.s("error")
    // The page's own rows lead so they survive a narrow cut; the globals follow (view.rs hints, T60.8).
    val keys = (pageKeys(app.page) + keysFor("")).joinToString(" · ") { (k, d) -> "$k $d" }
    val status = app.pluginStatus.takeIf { app.page == "plugins" && it.isNotEmpty() }
    when {
        err != null -> b.put(foot.x, foot.y, "store: $err", FAIL, foot.w)

        status != null -> b.put(foot.x, foot.y, status, ACCENT, foot.w)

        // view.rs: the loading mark leads the key line while a model read is on the worker.
        app.loading -> b.put(foot.x, foot.y, "◌ loading  $keys", tui.Style(fg = tui.Color.Yellow), foot.w)

        else -> b.put(foot.x, foot.y, keys, MUTED, foot.w)
    }
}

private fun page(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    clock: (Double) -> String,
) {
    val junk = if (app.page == "hosts") app.actions?.let { app.junk.panel(it) } else null
    val fix = if (app.page == "doctor") app.doctorFix.lines() else null
    when {
        // view.rs: the open checklist replaces the report; the cursor's row (two lines an item) stays in view.
        fix != null -> b.paragraph(r, fix, follow(1 + 2 * app.doctorFix.cursor, 0, r.h).coerceAtMost((fix.size - r.h).coerceAtLeast(0)))

        app.page == "doctor" && app.actions != null -> doctor(b, r, app)

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

/**
 * view.rs `doctor` (T15.6): the same text `rtok doctor` prints, from the report the snapshot's cache holds (no second
 * probe); section heads (unindented lines) are bold. The snapshot carries the report as JSON for the web page, so the
 * terminal reads the cached `Report` itself.
 */
private fun doctor(
    b: Buffer,
    r: Rect,
    app: TuiApp,
) {
    val (report, pending) = rtok.model.DoctorPage.read(app.actions!!.cfg)
    val lines =
        when {
            report != null -> report.toText().lines().map { listOf(Span(it, if (it.startsWith(" ")) tui.Style.Default else tui.Style(bold = true))) }
            pending -> listOf(listOf(Span("running doctor…", MUTED)))
            else -> listOf(listOf(Span("doctor did not answer this tick — `rtok doctor` has the details", MUTED)))
        }
    b.paragraph(r, lines, follow(app.cursor, 0, r.h).coerceAtMost((lines.size - r.h).coerceAtLeast(0)))
}
