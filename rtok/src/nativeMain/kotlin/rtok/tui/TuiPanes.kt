// The panes of `rtok tui` that sit over a page (view.rs `config_page` filter, the Calls expand pane).
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import tui.Buffer
import tui.Rect
import tui.Size
import tui.Span
import tui.Style
import tui.block
import tui.paragraph
import tui.rows

private const val CALL_DETAIL = 9
private const val CALL_DETAIL_WITH_EXPAND = 8
private const val SESSION_DETAIL = 8

/** view.rs `render_sessions`: the list, with the selected session's pane (T60.3) under it while Enter has it open. */
internal fun sessions(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    clock: (Double) -> String,
) {
    val visible = app.sessionRows()
    val rows = visible.map { sessionRow(it, clock) }
    val detail = if (app.sessionsDetail) visible.getOrNull(app.cursor)?.s("id")?.let { sessionDetail(app.snapshot, it, clock) } else null
    if (detail == null) {
        grid(b, r, app, SESSION_COLS, rows)
        return
    }
    val (list, below) = r.rows(Size.Fill(), Size.Fixed(SESSION_DETAIL))
    grid(b, list, app, SESSION_COLS, rows)
    b.paragraph(b.block(below, "session", ACCENT), detail.mapIndexed { i, l -> listOf(Span(l, if (i < SESSION_HEAD) Style() else MUTED)) })
}

private const val SESSION_HEAD = 4

/** view.rs `config_page`: the text, or `/filter` over the grep hits while a filter is open or set. */
internal fun pageLines(
    app: TuiApp,
    body: String,
): List<String> {
    val f = app.configFilter
    return if (app.page != "config" || (!f.typing && f.text.isEmpty())) body.lines() else listOf("/${f.text}") + f.apply(body).lines()
}

/** view.rs `calls_page`: the ledger, with the expand pane (T60.4) under it while one is open. */
internal fun calls(
    b: Buffer,
    r: Rect,
    app: TuiApp,
    rows: List<List<String>>,
) {
    val pane = app.expand
    val row = if (app.callsDetail) (app.snapshot["calls"] as? JsonArray)?.getOrNull(app.cursor) as? JsonObject else null
    val areas =
        when {
            row != null && pane != null -> r.rows(Size.Fill(), Size.Fixed(CALL_DETAIL_WITH_EXPAND), Size.Fill())
            row != null -> r.rows(Size.Fill(), Size.Fixed(CALL_DETAIL))
            pane != null -> r.rows(Size.Fill(), Size.Fill())
            else -> listOf(r)
        }
    grid(b, areas[0], app, CALL_COLS, rows)
    if (row != null) {
        val (title, lines) = callDetail(row, app.snapshot["ref_ids"] as? JsonObject)
        b.paragraph(b.block(areas[1], title, ACCENT), lines.map { listOf(Span(it)) })
    }
    if (pane == null) return
    val below = areas.last()
    val f = pane.filter
    val inner = b.block(below, if (f.typing) "expand ${pane.id}  /${f.text}" else "expand ${pane.id}", ACCENT)
    val body = pane.text().lines()
    b.paragraph(inner, body.map { listOf(Span(it)) }, pane.scroll.coerceAtMost(body.size))
}
