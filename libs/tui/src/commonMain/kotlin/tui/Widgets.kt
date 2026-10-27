// Small widgets drawn straight into a [Buffer]: a bordered block, lines of spans, tabs, a table and a sparkline.
package tui

private const val TITLE_MIN = 4

/** A run of text in one style. */
data class Span(
    val text: String,
    val style: Style = Style.Default,
)

/** A line of spans. */
typealias Line = List<Span>

/** A rounded border with [title] in the top edge; returns the inner rect. */
fun Buffer.block(
    r: Rect,
    title: String = "",
    style: Style = Style.Default,
    titleStyle: Style = style.copy(bold = true),
): Rect {
    if (r.w < 2 || r.h < 2) return Rect(r.x, r.y, 0, 0)
    put(r.x, r.y, "╭" + "─".repeat(r.w - 2) + "╮", style)
    for (yy in r.y + 1 until r.bottom - 1) {
        put(r.x, yy, "│", style)
        put(r.right - 1, yy, "│", style)
    }
    put(r.x, r.bottom - 1, "╰" + "─".repeat(r.w - 2) + "╯", style)
    if (title.isNotEmpty() && r.w > TITLE_MIN) put(r.x + 2, r.y, truncate(" $title ", r.w - TITLE_MIN), titleStyle)
    return r.inner()
}

/** Draws [lines] into [r] from line [scroll]; each is cut at the right edge. */
fun Buffer.paragraph(
    r: Rect,
    lines: List<Line>,
    scroll: Int = 0,
) {
    lines.drop(scroll).take(r.h).forEachIndexed { i, line ->
        var x = r.x
        for (s in line) {
            if (x >= r.right) break
            x = put(x, r.y + i, s.text, s.style, r.right - x)
        }
    }
}

/** One row of tabs; [selected] is reversed. Returns nothing: tabs past the edge are cut. */
fun Buffer.tabs(
    r: Rect,
    titles: List<String>,
    selected: Int,
    style: Style = Style.Default,
    selectedStyle: Style = Style(reverse = true, bold = true),
) {
    var x = r.x
    titles.forEachIndexed { i, t ->
        if (x >= r.right) return
        x = put(x, r.y, " $t ", if (i == selected) selectedStyle else style, r.right - x)
        x = put(x, r.y, "│", style.copy(dim = true), r.right - x)
    }
}

/** A table column: header, width in cells (0: share the rest) and alignment. */
data class Col(
    val header: String,
    val width: Int = 0,
    val right: Boolean = false,
)

/** The widths of [cols] over [total] cells with one space between columns. */
fun colWidths(
    total: Int,
    cols: List<Col>,
): List<Int> =
    lengths(
        (total - (cols.size - 1)).coerceAtLeast(0),
        cols.map { if (it.width > 0) Size.Fixed(it.width) else Size.Fill() },
    )

/**
 * A header row and [rows] (cell texts) from row [scroll]; row [selected] is highlighted. [cellStyle] colours one cell
 * of an unselected row (row index, column index), null for the row's own style.
 */
fun Buffer.table(
    r: Rect,
    cols: List<Col>,
    rows: List<List<String>>,
    selected: Int = -1,
    scroll: Int = 0,
    header: Style = Style(bold = true, underline = true),
    highlight: Style = Style(reverse = true),
    cellStyle: (Int, Int) -> Style? = { _, _ -> null },
) {
    if (r.h <= 0) return
    val ws = colWidths(r.w, cols)

    fun row(
        y: Int,
        cells: List<String>,
        st: (Int) -> Style,
    ) {
        var x = r.x
        cols.forEachIndexed { i, c ->
            put(x, y, fit(cells.getOrElse(i) { "" }, ws[i], c.right), st(i), ws[i])
            x += ws[i] + 1
        }
    }
    row(r.y, cols.map { it.header }) { header }
    rows.drop(scroll).take(r.h - 1).forEachIndexed { i, cells ->
        val idx = i + scroll
        if (idx == selected) fill(Rect(r.x, r.y + 1 + i, r.w, 1), highlight)
        row(r.y + 1 + i, cells) { c -> if (idx == selected) highlight else cellStyle(idx, c) ?: Style.Default }
    }
}

private const val BARS = "▁▂▃▄▅▆▇█"

/** The last [cols] values as block bars scaled to their maximum. */
fun sparkline(
    values: List<Double>,
    cols: Int,
): String {
    val v = values.takeLast(cols)
    val max = v.maxOrNull()?.takeIf { it > 0 } ?: return " ".repeat(v.size)
    return v.joinToString("") { x -> BARS[((x / max) * (BARS.length - 1)).toInt().coerceIn(0, BARS.length - 1)].toString() }
}

/** The first row of [r] to show so that row [selected] stays visible, starting from [scroll]. */
fun follow(
    selected: Int,
    scroll: Int,
    visible: Int,
): Int =
    when {
        visible <= 0 -> 0
        selected < scroll -> selected.coerceAtLeast(0)
        selected >= scroll + visible -> selected - visible + 1
        else -> scroll
    }

/** [line] cut into rows of at most [cols] cells, spans keeping their style; an empty line stays one row. */
fun wrap(
    line: Line,
    cols: Int,
): List<Line> {
    if (cols <= 0) return listOf(line)
    val out = mutableListOf<Line>()
    var row = mutableListOf<Span>()
    var used = 0
    for (s in line) {
        val cur = StringBuilder()
        for (cp in codePoints(s.text)) {
            val w = cpWidth(cp)
            if (used + w > cols && used > 0) {
                if (cur.isNotEmpty()) row += Span(cur.toString(), s.style)
                out += row
                row = mutableListOf()
                cur.clear()
                used = 0
            }
            cur.append(cpString(cp))
            used += w
        }
        if (cur.isNotEmpty()) row += Span(cur.toString(), s.style)
    }
    out += row
    return out
}
