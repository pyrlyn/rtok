// Port of src/tui/view.rs `render_graph` and src/tui/health.rs (T476, T481): the Graph tab's project registry with
// its keys above the index health text. The health numbers, levels, reasons and fixes are the server's, already in
// the snapshot's rows; this only paints them, coloured by the row's `level` as the web page does.
package rtok.tui

import kotlinx.serialization.json.JsonPrimitive
import rtok.plugins.graph.Level
import rtok.plugins.graph.ProjectRow
import rtok.plugins.graph.Score
import rtok.web.ScopeRow
import rtok.web.scopeNames
import tui.Buffer
import tui.Col
import tui.Color
import tui.Rect
import tui.Size
import tui.Span
import tui.Style
import tui.follow
import tui.paragraph
import tui.rows
import tui.table

/** Reasons shown under the table; the text below is for the rest. */
private const val MAX_REASONS = 3

/** Seven rows and the header: the table scrolls to the cursor beyond that. */
private const val TABLE_MAX = 8
private const val NOTE_MAX = 3
private const val LIVE_MAX = 18

/** The metrics, the filters and three feed rows: below that the feed would be cut to nothing. */
private const val LIVE_MIN = 12
private const val HEALTH_COL = 5
private const val SCOPE_COL = 6

private val GRAPH_COLS = listOf(Col("", 1), Col("id", 4), Col("project"), Col("state", 12), Col("links", 5), Col("health", 8), Col("scope", 5))
private val WARN = Style(fg = Color.Yellow)

private fun color(level: Level): Style =
    when (level) {
        Level.Good -> Style(fg = Color.Green)
        Level.Warn -> WARN
        Level.Bad, Level.Missing -> Style(fg = Color.Red)
        Level.Indexing -> MUTED
    }

private fun word(level: Level) = level.name.lowercase()

/** health.rs `cell`: the number, or the word where there is no number to read. */
internal fun healthCell(p: ProjectRow): String {
    val h = p.health
    return if (h.level == Level.Indexing || h.level == Level.Missing || h.score == null) word(h.level) else h.score.toString()
}

/** health.rs `detail`: the cursor project's level and three components, then each reason with its fix. */
internal fun healthDetail(p: ProjectRow): List<List<Span>> {
    val h = p.health
    val c = h.components
    val head =
        listOf(
            Span("${p.name} ${word(h.level)}", color(h.level)),
            Span("  freshness ${two(c.freshness)}  backend ${two(c.backend)}  links ${two(c.links)}"),
        )
    return listOf(head) + h.reasons.take(MAX_REASONS).map { listOf(Span("  ${it.component.name.lowercase()}: ${it.text} -> ${it.fix}")) }
}

private const val HUNDREDTHS = 100

/** Rust's `{:.2}`. */
private fun two(x: Double): String {
    val t = kotlin.math.round(x * HUNDREDTHS).toLong()
    return "${t / HUNDREDTHS}.${(t % HUNDREDTHS).toString().padStart(2, '0')}"
}

/** The Graph tab: with no registered project there is nothing for the keys to act on, so only the text shows. */
internal fun graphPage(
    b: Buffer,
    whole: Rect,
    app: TuiApp,
    clock: (Double) -> String,
) {
    if (app.compare.render(b, whole) || app.exporter.render(b, whole)) return
    val rows = projectEntries(app.snapshot)
    // The live calls pane takes half the body, capped, so the index health stays readable.
    val (r, calls) = whole.rows(Size.Fill(), Size.Fixed((whole.h / 2).coerceAtMost(LIVE_MAX).coerceAtLeast(LIVE_MIN)))
    val scope = scopeNames(rows.map { ScopeRow(it.id, it.name, it.selected, it.links.map { l -> l.to }) }, app.drill.project)
    app.live.render(b, calls, scope, app.drill.drawn, clock)
    val textLines = (app.snapshot["graph"] as? JsonPrimitive)?.content?.lines()
    if (rows.isEmpty()) {
        if (textLines == null) graphMissing(b, r) else text(b, r, app, textLines)
        return
    }
    val cursor = app.projects.cursor(rows)
    val detail = healthDetail(rows[cursor])
    val note = app.projects.note(rows).ifEmpty { app.drill.note }
    // A remove's plan wraps: its rows are counted, up to three, as view.rs does.
    val noteLines = note.chunked(r.w.coerceAtLeast(1)).take(NOTE_MAX).ifEmpty { listOf("") }
    val parts =
        r.rows(Size.Fixed((rows.size + 1).coerceAtMost(TABLE_MAX)), Size.Fixed(detail.size), Size.Fixed(noteLines.size), Size.Fill())
    val (table, breakdown, noteArea) = parts
    val body = parts[3]
    val cells =
        rows.map { p ->
            listOf(
                if (p.selected) "*" else "",
                p.id.toString(),
                p.name,
                p.state,
                p.links.size.toString(),
                healthCell(p),
                p.scopeHealth?.toString() ?: "-",
            )
        }
    b.table(table, GRAPH_COLS, cells, cursor, follow(cursor, 0, table.h - 1)) { i, c ->
        when (c) {
            HEALTH_COL -> color(rows[i].health.level)
            SCOPE_COL -> rows[i].scopeHealth?.let { color(Score.levelOf(it)) }
            else -> null
        }
    }
    b.paragraph(breakdown, detail)
    b.paragraph(noteArea, noteLines.map { listOf(Span(it, WARN)) })
    if (textLines == null) graphMissing(b, body) else b.paragraph(body, textLines.map { listOf(Span(it)) })
}

private fun graphMissing(
    b: Buffer,
    r: Rect,
) = b.paragraph(r, listOf(listOf(Span("graph did not answer this tick — `rtok graph status` has the details", MUTED))))
