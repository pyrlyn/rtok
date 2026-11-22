// Display width of text in terminal columns: wide East Asian and emoji code points take two, combining marks none.
package tui

private const val SPACE = 0x20
private const val DEL = 0x7F

/** The last C1 control: U+009B is a one-character CSI, which an 8-bit terminal obeys. */
private const val C1_END = 0x9F
private const val SUPP = 0x10000
private const val HIGH = 0xD800
private const val LOW = 0xDC00
private const val SHIFT = 10
private const val LOW_MASK = 0x3FF

private val WIDE =
    listOf(
        0x1100..0x115F,
        0x231A..0x231B,
        0x23E9..0x23EC,
        0x23F0..0x23F0,
        0x23F3..0x23F3,
        0x25FD..0x25FE,
        0x2614..0x2615,
        0x2648..0x2653,
        0x267F..0x267F,
        0x2693..0x2693,
        0x26A1..0x26A1,
        0x26AA..0x26AB,
        0x26BD..0x26BE,
        0x26C4..0x26C5,
        0x26CE..0x26CE,
        0x26D4..0x26D4,
        0x26EA..0x26EA,
        0x26F2..0x26F3,
        0x26F5..0x26F5,
        0x26FA..0x26FA,
        0x26FD..0x26FD,
        0x2705..0x2705,
        0x270A..0x270B,
        0x2728..0x2728,
        0x274C..0x274C,
        0x274E..0x274E,
        0x2753..0x2755,
        0x2757..0x2757,
        0x2795..0x2797,
        0x27B0..0x27B0,
        0x27BF..0x27BF,
        0x2B1B..0x2B1C,
        0x2B50..0x2B50,
        0x2B55..0x2B55,
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
        0x1F004..0x1F004,
        0x1F0CF..0x1F0CF,
        0x1F18E..0x1F18E,
        0x1F191..0x1F19A,
        0x1F200..0x1F251,
        0x1F300..0x1F64F,
        0x1F680..0x1F6FF,
        0x1F900..0x1F9FF,
        0x1FA70..0x1FAFF,
        0x20000..0x2FFFD,
        0x30000..0x3FFFD,
    )
private val ZERO = listOf(0x0300..0x036F, 0x200B..0x200F, 0x20D0..0x20FF, 0xFE00..0xFE0F)

/** Columns one code point takes: 0, 1 or 2. */
fun cpWidth(cp: Int): Int =
    when {
        cp < SPACE || cp in DEL..C1_END -> 0
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
