// One section of a document: the comment/blank lines before its header (its decor), the header, and its body. The
// preamble (root keys) is a block with no header. Edits change only the lines of the entry they touch.
package tomlaot

/** One `key = value` entry: its lines `[first, last]` and, on the last line, where the value text ends. */
internal data class Entry(
    val key: String,
    val first: Int,
    val col: Int,
    val last: Int,
    val end: Int,
)

/** A table in the document; for `[[path]]` one element of the array. */
class TomlTable internal constructor(
    /** The normalized header path, empty for the preamble. */
    val path: String,
    /** `[[path]]` rather than `[path]`. */
    val arrayElement: Boolean,
    internal val lines: MutableList<String>,
    /** Index of the header line in [lines] (the lines before it are decor); -1 for the preamble. */
    internal var headerAt: Int,
) {
    internal fun entries(): List<Entry> {
        val out = mutableListOf<Entry>()
        var i = headerAt + 1
        while (i < lines.size) {
            val kc = Scan.key(lines[i])
            if (kc == null) {
                i++
                continue
            }
            val (last, end) = Scan.valueEnd(lines, i, kc.second)
            out += Entry(kc.first, i, kc.second, last, end)
            i = last + 1
        }
        return out
    }

    /** The keys of this table, in file order. */
    fun keys(): List<String> = entries().map { it.key }

    /** How many keys this table holds. */
    val size: Int get() = entries().size

    fun containsKey(key: String) = entries().any { it.key == key }

    operator fun get(key: String): TomlValue? = entries().firstOrNull { it.key == key }?.let { e -> TomlValue.parse(text(e)) }

    fun str(key: String): String? = (get(key) as? TomlValue.Str)?.value

    fun int(key: String): Long? = (get(key) as? TomlValue.Int)?.value

    private fun text(e: Entry): String =
        if (e.first == e.last) {
            lines[e.first].substring(e.col, e.end)
        } else {
            (listOf(lines[e.first].substring(e.col)) + lines.subList(e.first + 1, e.last) + lines[e.last].substring(0, e.end)).joinToString("\n")
        }

    /** Set [key] in place (its comment stays), or append `key = value` after the last entry. */
    operator fun set(
        key: String,
        value: TomlValue,
    ) {
        val e = entries().firstOrNull { it.key == key }
        if (e != null) {
            val head = lines[e.first].substring(0, e.col)
            val tail = lines[e.last].substring(e.end)
            repeat(e.last - e.first) { lines.removeAt(e.first + 1) }
            lines[e.first] = head + value.render() + tail
            return
        }
        val after = entries().lastOrNull()?.last ?: headerAt
        lines.add(after + 1, "${bareOrQuoted(key)} = ${value.render()}")
    }

    internal fun text(): List<String> = lines

    companion object {
        private val BARE = Regex("^[A-Za-z0-9_-]+$")

        fun bareOrQuoted(key: String) = if (BARE.matches(key)) key else TomlValue.quote(key)
    }
}
