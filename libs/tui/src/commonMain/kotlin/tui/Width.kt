// Display width of text in terminal columns: wide East Asian and emoji code points take two, combining marks none.
package tui

private const val SPACE = 0x20
private const val DEL = 0x7F
private const val SUPP = 0x10000
private const val HIGH = 0xD800
private const val LOW = 0xDC00
private const val SHIFT = 10
private const val LOW_MASK = 0x3FF

private val WIDE =
    listOf(
        0x1100..0x115F,
        0x2E80..0x303E,
        0x3041..0x33FF,
        0x3400..0x4DBF,
        0x4E00..0x9FFF,
        0xA000..0xA4CF,
        0xAC00..0xD7A3,
        0xF900..0xFAFF,
        0xFE30..0xFE4F,
        0xFF00..0xFF60,
        0xFFE0..0xFFE6,
        0x1F300..0x1F64F,
        0x1F900..0x1F9FF,
        0x20000..0x2FFFD,
        0x30000..0x3FFFD,
    )
private val ZERO = listOf(0x0300..0x036F, 0x200B..0x200F, 0x20D0..0x20FF, 0xFE00..0xFE0F)

/** Columns one code point takes: 0, 1 or 2. */
fun cpWidth(cp: Int): Int =
    when {
        cp < SPACE || cp == DEL -> 0
        ZERO.any { cp in it } -> 0
        WIDE.any { cp in it } -> 2
        else -> 1
    }

/** The code points of [s] (surrogate pairs joined). */
fun codePoints(s: String): List<Int> {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            out += SUPP + ((c.code - HIGH) shl SHIFT) + (s[i + 1].code - LOW)
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out
}

/** The text of one code point. */
fun cpString(cp: Int): String =
    if (cp < SUPP) {
        cp.toChar().toString()
    } else {
        val v = cp - SUPP
        charArrayOf((HIGH + (v shr SHIFT)).toChar(), (LOW + (v and LOW_MASK)).toChar()).concatToString()
    }

/** Display width of [s]. */
fun width(s: String): Int = codePoints(s).sumOf(::cpWidth)

/** [s] cut to at most [cols] columns, with `…` when cut. */
fun truncate(
    s: String,
    cols: Int,
): String {
    if (cols <= 0 || width(s) <= cols) return if (cols <= 0) "" else s
    val sb = StringBuilder()
    var w = 0
    for (cp in codePoints(s)) {
        val cw = cpWidth(cp)
        if (w + cw > cols - 1) break
        sb.append(cpString(cp))
        w += cw
    }
    return sb.append('…').toString()
}

/** [s] padded with spaces (or cut) to exactly [cols] columns; [right] aligns it to the right. */
fun fit(
    s: String,
    cols: Int,
    right: Boolean = false,
): String {
    val t = truncate(s, cols)
    val pad = " ".repeat((cols - width(t)).coerceAtLeast(0))
    return if (right) pad + t else t + pad
}
