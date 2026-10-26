// Port of the table half of src/render.rs (T25.2): the one padded-column table.
package rtok

/** A column floor; numbers right-align, text left-aligns. */
data class Col(
    val min: Int,
    val right: Boolean,
) {
    companion object {
        fun left(min: Int) = Col(min, false)

        fun right(min: Int) = Col(min, true)
    }
}

private fun String.chars(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        if (this[i].isHighSurrogate() && i + 1 < length) i++
        i++
        n++
    }
    return n
}

/** Every column as wide as its widest cell and never narrower than its floor, one space apart; row 0 is the header. */
fun table(
    cols: List<Col>,
    rows: List<List<String>>,
): String {
    val widths = cols.indices.map { j -> maxOf(cols[j].min, rows.maxOfOrNull { it.getOrNull(j)?.chars() ?: 0 } ?: 0) }
    return buildString {
        for (row in rows) {
            append(
                cols.indices.joinToString(" ") { j ->
                    val cell = row.getOrNull(j).orEmpty()
                    val pad = " ".repeat(maxOf(widths[j] - cell.chars(), 0))
                    if (cols[j].right) pad + cell else cell + pad
                },
            )
            append('\n')
        }
    }
}
