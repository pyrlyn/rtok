// Port of src/tui/app.rs `KEYS` / `keys_for` and view.rs `render_help` (T60.8): the one key table the footer and the
// `?` overlay render from, so no key hint is written twice. The Graph rows list the registry keys before the live
// calls ones (Rust lists them first) so the footer keeps the page's actions on a narrow screen. The Doctor fix and Skills rows join when those pages do.
package rtok.tui

import tui.Buffer
import tui.Color
import tui.Rect
import tui.Span
import tui.Style
import tui.block
import tui.paragraph
import tui.width

/** `(page, key, description)`; page `""` is global. */
val KEYS: List<Triple<String, String, String>> =
    listOf(
        Triple("", "q/Esc", "quit"),
        Triple("", "Left/Right", "switch tab"),
        Triple("", "1..9", "jump to tab"),
        Triple("", "?", "help"),
        Triple("", "r", "refresh now"),
        Triple("plugins", "↑/↓", "move cursor"),
        Triple("plugins", "Space/Enter", "toggle plugin"),
        Triple("calls", "↑/↓", "move selection"),
        Triple("calls", "Enter/z", "detail pane"),
        Triple("calls", "e", "expand archive"),
        Triple("calls", "/", "filter expand"),
        Triple("sessions", "↑/↓", "move selection"),
        Triple("sessions", "Enter", "detail pane"),
        Triple("sessions", "l", "live-only filter"),
        Triple("hosts", "c", "clear safe junk (plan)"),
        Triple("hosts", "y", "remove planned (confirm)"),
        Triple("hosts", "n/Esc", "decline"),
        Triple("config", "/", "filter entries"),
        Triple("graph", "↑/↓", "move cursor"),
        Triple("graph", "s", "select project"),
        Triple("graph", "l", "link to…"),
        Triple("graph", "u", "unlink from…"),
        Triple("graph", "b", "both ways"),
        Triple("graph", "Enter", "pick target"),
        Triple("graph", "y", "confirm plan"),
        Triple("graph", "n/Esc", "decline"),
        Triple("graph", "c", "compare"),
        Triple("compare", "type", "ref, PROJECT:REF or export path"),
        Triple("compare", "Enter", "run compare"),
        Triple("compare", "↑/↓ PgUp/PgDn", "scroll"),
        Triple("compare", "c", "compare again"),
        Triple("compare", "Esc", "close"),
        Triple("graph", "e", "export graph"),
        Triple("graph", "v", "view saved export"),
        Triple("graph", "d", "drill"),
        Triple("graph", "i", "re-index"),
        Triple("graph", "x", "remove…"),
        Triple("graph", "f", "freeze/unfreeze calls"),
        Triple("graph", "w", "calls window"),
        Triple("graph", "a", "filter calls by caller"),
        Triple("graph", "t", "filter calls by tool"),
        Triple("graph", "o", "filter calls by project"),
        Triple("export", "↑/↓", "choose field"),
        Triple("export", "Space", "change format, level, redact"),
        Triple("export", "type", "file to write, or export to view"),
        Triple("export", "Enter", "write / view"),
        Triple("export", "↑/↓ PgUp/PgDn", "scroll result"),
        Triple("export", "Esc", "close"),
    )

/** app.rs `keys_for`: globals first, then [page]'s own rows. */
fun keysFor(page: String): List<Pair<String, String>> = KEYS.filter { it.first.isEmpty() || it.first == page }.map { it.second to it.third }

/** The rows only [page] adds (the footer's page hint). */
fun pageKeys(page: String): List<Pair<String, String>> = KEYS.filter { it.first == page }.map { it.second to it.third }

/** view.rs `render_help`'s page: an open compare or export panel owns the Graph tab's keys. */
fun helpPage(app: TuiApp): String =
    when {
        app.page == "graph" && app.compare.isOpen -> "compare"
        app.page == "graph" && app.exporter.isOpen -> "export"
        else -> app.page
    }

private const val KEY_COL = 13
private const val TITLE_PAD = 12
private const val BORDER = 4

/** view.rs `render_help`: the keys of [helpPage] in a centred box over a cleared area of [r]. */
fun helpOverlay(
    b: Buffer,
    r: Rect,
    app: TuiApp,
) {
    val page = helpPage(app)
    val title = "keys — $page"
    val lines = keysFor(page).map { (k, d) -> listOf(Span(k.padEnd(KEY_COL), Style(fg = Color.Cyan, bold = true)), Span(d)) }
    val width =
        (
            maxOf(lines.maxOf { l -> l.sumOf { width(it.text) } }, width(title) + TITLE_PAD) + BORDER
        ).coerceAtMost(r.w)
    val height = (lines.size + 2).coerceAtMost(r.h)
    val box = Rect(r.x + (r.w - width) / 2, r.y + (r.h - height) / 2, width, height)
    b.fill(box)
    val inner = b.block(box, title, Style(fg = Color.Cyan))
    val closes = " ? closes "
    b.put(box.right - 1 - width(closes), box.bottom - 1, closes, Style(fg = Color.Gray))
    b.paragraph(inner, lines)
}
