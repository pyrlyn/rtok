// A lossless TOML document for editing tables and arrays of tables (`[[a.b]]`): every line the edit does not touch,
// comments and blank lines included, is written back byte for byte. Values are read as scalars or raw text; the
// document is never reformatted. This is not a validator: callers parse with a real TOML reader when they need one.
package tomlaot

class TomlAotException(
    message: String,
) : Exception(message)

/** What a dotted path names in the document. */
enum class Shape { Absent, Table, ArrayOfTables, Value }

class TomlDoc private constructor(
    private val blocks: MutableList<TomlTable>,
    private var trailingNewline: Boolean,
) {
    /** The `[[path]]` tables in file order. */
    fun arrayOfTables(path: String): List<TomlTable> = blocks.filter { it.arrayElement && it.path == path }

    /** The `[path]` table, or null. */
    fun table(path: String): TomlTable? = blocks.firstOrNull { !it.arrayElement && it.path == path && it.headerAt >= 0 }

    /** What [path] names: a header of either kind, a key in its parent table, or nothing. */
    fun shape(path: String): Shape {
        val parent = path.substringBeforeLast('.', "")
        val leaf = path.substringAfterLast('.')
        val holder = if (parent.isEmpty()) blocks.first() else table(parent)
        return when {
            blocks.any { it.arrayElement && it.path == path } -> Shape.ArrayOfTables
            blocks.any { it.headerAt >= 0 && (it.path == path || it.path.startsWith("$path.")) } -> Shape.Table
            holder?.keys()?.any { it == leaf || it.startsWith("$leaf.") } == true -> Shape.Value
            blocks.first().keys().any { it == path || it.startsWith("$path.") } -> Shape.Value
            else -> Shape.Absent
        }
    }

    /** Drop the `[[path]]` tables [keep] rejects, each with its decor; returns how many went. */
    fun retain(
        path: String,
        keep: (TomlTable) -> Boolean,
    ): Int {
        val gone = blocks.filter { it.arrayElement && it.path == path && !keep(it) }
        blocks.removeAll(gone.toSet())
        return gone.size
    }

    /** Drop the `[path]` table and every table under it (`[path.x]`, `[[path.y]]`), each with its decor; false when there is none. */
    fun removeTable(path: String): Boolean {
        if (table(path) == null) return false
        blocks.removeAll { it.headerAt >= 0 && (it.path == path || it.path.startsWith("$path.")) }
        return true
    }

    /** Append a `[[path]]` table after the last one (or at the end), with [entries] in order. */
    fun push(
        path: String,
        entries: List<Pair<String, TomlValue>>,
    ): TomlTable {
        requireShape(path, Shape.ArrayOfTables)
        val at = blocks.indexOfLast { it.arrayElement && it.path == path }.let { if (it < 0) blocks.size else it + 1 }
        return insertBlock(at, path, true, entries)
    }

    /** The `[path]` table, created (before the first table under it, else at the end) when absent. */
    fun ensureTable(path: String): TomlTable {
        table(path)?.let { return it }
        requireShape(path, Shape.Table)
        val at = blocks.indexOfFirst { it.path.startsWith("$path.") }.let { if (it < 0) blocks.size else it }
        return insertBlock(at, path, false, emptyList())
    }

    private fun requireShape(
        path: String,
        want: Shape,
    ) {
        val have = shape(path)
        if (have != Shape.Absent && have != want) {
            throw TomlAotException("`$path` is not ${if (want == Shape.ArrayOfTables) "an array of tables" else "a table"}")
        }
    }

    private fun insertBlock(
        at: Int,
        path: String,
        array: Boolean,
        entries: List<Pair<String, TomlValue>>,
    ): TomlTable {
        val header = if (array) "[[${pathText(path)}]]" else "[${pathText(path)}]"
        val before = blocks.subList(0, at).flatMap { it.text() }
        val gap = if (before.isNotEmpty() && before.last().isNotBlank()) listOf("") else emptyList()
        val t = TomlTable(path, array, (gap + header).toMutableList(), gap.size)
        entries.forEach { (k, v) -> t[k] = v }
        blocks.add(at, t)
        if (at == blocks.lastIndex) trailingNewline = true
        return t
    }

    private fun pathText(path: String) = path.split('.').joinToString(".") { TomlTable.bareOrQuoted(it) }

    override fun toString(): String {
        val lines = blocks.flatMap { it.text() }
        if (lines.isEmpty() || lines == listOf("")) return ""
        return lines.joinToString("\n") + if (trailingNewline) "\n" else ""
    }

    companion object {
        /** Split [text] into the preamble and one block per header; decor before a header belongs to that header. */
        fun parse(text: String): TomlDoc {
            val trailing = text.endsWith("\n")
            val all = (if (trailing) text.dropLast(1) else text).split("\n").let { if (text.isEmpty()) emptyList() else it }
            val heads = headerLines(all)
            val blocks = mutableListOf<TomlTable>()
            var cut = 0
            var prev: Pair<Int, Pair<String, Boolean>>? = null
            for ((row, h) in heads) {
                var start = row
                while (start > cut && Scan.decor(all[start - 1])) start--
                blocks += block(all, cut, start, prev)
                prev = row to h
                cut = start
            }
            blocks += block(all, cut, all.size, prev)
            return TomlDoc(blocks, trailing)
        }

        private fun block(
            all: List<String>,
            from: Int,
            to: Int,
            head: Pair<Int, Pair<String, Boolean>>?,
        ) = TomlTable(head?.second?.first.orEmpty(), head?.second?.second == true, all.subList(from, to).toMutableList(), head?.let { it.first - from } ?: -1)

        /** Header lines outside multi-line values. */
        private fun headerLines(all: List<String>): List<Pair<Int, Pair<String, Boolean>>> {
            val out = mutableListOf<Pair<Int, Pair<String, Boolean>>>()
            var i = 0
            while (i < all.size) {
                val kc = Scan.key(all[i])
                if (kc != null) {
                    i = Scan.valueEnd(all, i, kc.second).first + 1
                    continue
                }
                Scan.header(all[i])?.let { out += i to it }
                i++
            }
            return out
        }
    }
}
