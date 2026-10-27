// Splitting a rect into rows or columns by constraints.
package tui

/** How much of the split one part takes. */
sealed interface Size {
    /** Exactly [n] cells (less when the space runs out). */
    data class Fixed(
        val n: Int,
    ) : Size

    /** [p] percent of the whole. */
    data class Percent(
        val p: Int,
    ) : Size

    /** An equal share of what the fixed and percent parts leave, at least [min]. */
    data class Fill(
        val min: Int = 0,
    ) : Size
}

private const val HUNDRED = 100

/** Lengths for [sizes] over [total] cells: fixed and percent first, fills share the rest, the last fill takes rounding. */
fun lengths(
    total: Int,
    sizes: List<Size>,
): List<Int> {
    val out = IntArray(sizes.size)
    var left = total
    sizes.forEachIndexed { i, s ->
        val n =
            when (s) {
                is Size.Fixed -> s.n
                is Size.Percent -> total * s.p / HUNDRED
                is Size.Fill -> 0
            }.coerceIn(0, left.coerceAtLeast(0))
        out[i] = n
        left -= n
    }
    val fills = sizes.indices.filter { sizes[it] is Size.Fill }
    if (fills.isNotEmpty() && left > 0) {
        val each = left / fills.size
        fills.forEachIndexed { k, i -> out[i] = if (k == fills.lastIndex) left - each * (fills.size - 1) else each }
    }
    return out.toList()
}

/** [r] cut into rows top to bottom. */
fun Rect.rows(vararg sizes: Size): List<Rect> {
    var yy = y
    return lengths(h, sizes.toList()).map { n -> Rect(x, yy, w, n).also { yy += n } }
}

/** [r] cut into columns left to right. */
fun Rect.cols(vararg sizes: Size): List<Rect> {
    var xx = x
    return lengths(w, sizes.toList()).map { n -> Rect(xx, y, n, h).also { xx += n } }
}
