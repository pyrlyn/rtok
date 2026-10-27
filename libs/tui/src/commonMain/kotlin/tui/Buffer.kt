// A screen of styled cells, and the ANSI that turns one frame into the next.
package tui

/** One screen cell: its text (empty for the right half of a wide character) and style. */
data class Cell(
    val text: String = " ",
    val style: Style = Style.Default,
)

/** A rectangle in cells. */
data class Rect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    val right get() = x + w
    val bottom get() = y + h

    /** This rect shrunk by [n] on every side. */
    fun inner(n: Int = 1) = Rect(x + n, y + n, (w - 2 * n).coerceAtLeast(0), (h - 2 * n).coerceAtLeast(0))
}

/** A [w]×[h] grid of cells; writes outside it are clipped. */
class Buffer(
    val w: Int,
    val h: Int,
) {
    private val cells = Array(w * h) { Cell() }

    val area get() = Rect(0, 0, w, h)

    operator fun get(
        x: Int,
        y: Int,
    ): Cell = cells[y * w + x]

    /** Writes [s] from ([x], [y]), at most [max] columns; returns the column after the last one written. */
    fun put(
        x: Int,
        y: Int,
        s: String,
        style: Style = Style.Default,
        max: Int = w - x,
    ): Int {
        if (y !in 0 until h) return x
        var cx = x
        val limit = minOf(w, x + max)
        for (cp in codePoints(s).filter { cpWidth(it) > 0 }) {
            val cw = cpWidth(cp)
            if (cx + cw > limit) break
            if (cx >= 0) {
                cells[y * w + cx] = Cell(cpString(cp), style)
                if (cw == 2) cells[y * w + cx + 1] = Cell("", style)
            }
            cx += cw
        }
        return cx
    }

    /** Paints [r] with [style] (blank cells). */
    fun fill(
        r: Rect,
        style: Style = Style.Default,
    ) {
        for (yy in maxOf(0, r.y) until minOf(h, r.bottom)) {
            for (xx in maxOf(0, r.x) until minOf(w, r.right)) cells[yy * w + xx] = Cell(" ", style)
        }
    }

    /** The rows as plain text (tests and snapshots). */
    fun lines(): List<String> = (0 until h).map { y -> (0 until w).joinToString("") { x -> this[x, y].text } }

    /** The ANSI that changes [prev] (null: an unknown screen) into this buffer; positions are 1-based. */
    fun diff(prev: Buffer?): String {
        val full = prev == null || prev.w != w || prev.h != h
        val sb = StringBuilder()
        if (full) sb.append("\u001b[0m\u001b[2J")
        var cur: Style? = null
        var at = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = this[x, y]
                if (c.text.isEmpty() || (!full && prev!![x, y] == c)) continue
                val idx = y * w + x
                if (idx != at) sb.append("\u001b[${y + 1};${x + 1}H")
                if (c.style != cur) {
                    sb.append(c.style.sgr())
                    cur = c.style
                }
                sb.append(c.text)
                at = idx + maxOf(1, width(c.text))
            }
        }
        if (cur != null) sb.append("\u001b[0m")
        return sb.toString()
    }
}
