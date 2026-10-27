// The panes of `rtok tui` that sit over a page (view.rs `config_page` filter, the Calls expand pane).
package rtok.tui

import tui.Buffer
import tui.Rect
import tui.Size
import tui.Span
import tui.block
import tui.paragraph
import tui.rows

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
    if (pane == null) {
        grid(b, r, app, CALL_COLS, rows)
        return
    }
    val (list, below) = r.rows(Size.Fill(), Size.Fill())
    grid(b, list, app, CALL_COLS, rows)
    val f = pane.filter
    val inner = b.block(below, if (f.typing) "expand ${pane.id}  /${f.text}" else "expand ${pane.id}", ACCENT)
    val body = pane.text().lines()
    b.paragraph(inner, body.map { listOf(Span(it)) }, pane.scroll.coerceAtMost(body.size))
}
