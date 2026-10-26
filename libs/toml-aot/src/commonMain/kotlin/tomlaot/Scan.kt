// Line scanning: where a `key = value` entry ends (multi-line strings and arrays included), where its comment starts,
// and which lines are table headers. Only what the editor needs; it never validates the document.
package tomlaot

internal object Scan {
    private val AOT = Regex("""^\s*\[\[\s*(.+?)\s*]]\s*(#.*)?$""")
    private val TABLE = Regex("""^\s*\[\s*([^\[].*?)\s*]\s*(#.*)?$""")
    private val KEY = Regex("""^\s*("(?:[^"\\]|\\.)*"|'[^']*'|[A-Za-z0-9_-]+)\s*=\s*""")

    /** `[[path]]` → (path, true), `[path]` → (path, false), anything else null. */
    fun header(line: String): Pair<String, Boolean>? =
        AOT.find(line)?.let { normalize(it.groupValues[1]) to true }
            ?: TABLE.find(line)?.let { normalize(it.groupValues[1]) to false }

    /** `a . "b"` → `a.b`. */
    fun normalize(path: String): String = splitDotted(path).joinToString(".")

    private fun splitDotted(path: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in path) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> cur.append(c)
                c == '"' || c == '\'' -> quote = c
                c == '.' -> parts += cur.toString().trim().also { cur.clear() }
                else -> cur.append(c)
            }
        }
        return parts + cur.toString().trim()
    }

    /** The key of an entry line (unquoted) and the column its value starts at; null for a non-entry line. */
    fun key(line: String): Pair<String, Int>? =
        KEY.find(line)?.let { m ->
            val k = m.groupValues[1]
            val bare = if (k.length >= 2 && (k[0] == '"' || k[0] == '\'')) k.substring(1, k.length - 1) else k
            bare to m.range.last + 1
        }

    /** True for a blank or comment-only line. */
    fun decor(line: String): Boolean = line.isBlank() || line.trimStart().startsWith("#")

    /**
     * The value starting at [lines]`[start]` column [col]: the last line it covers and, on that line, the column where
     * the value text ends (a trailing comment and whitespace come after).
     */
    fun valueEnd(
        lines: List<String>,
        start: Int,
        col: Int,
    ): Pair<Int, Int> {
        val st = State()
        var row = start
        var c = col
        while (row < lines.size) {
            val line = lines[row]
            val end = st.line(line, c)
            if (st.done()) return row to end
            row++
            c = 0
        }
        return lines.lastIndex to lines.last().length
    }

    private class State {
        var depth = 0
        var multi: String? = null

        fun done() = depth == 0 && multi == null

        /** Scan [line] from [from]; returns the end of value text on it (before a comment, trailing spaces trimmed). */
        fun line(
            line: String,
            from: Int,
        ): Int {
            var i = from
            var lastCode = from
            while (i < line.length && !(multi == null && line[i] == '#')) {
                val wasMulti = multi != null
                val next = step(line, i)
                if (wasMulti || !line[i].isWhitespace()) lastCode = next
                i = next
            }
            return lastCode
        }

        private fun step(
            line: String,
            i: Int,
        ): Int {
            multi?.let { return closeMulti(line, i, it) }
            val ch = line[i]
            return when {
                line.startsWith("\"\"\"", i) || line.startsWith("'''", i) -> {
                    multi = line.substring(i, i + TRIPLE)
                    i + TRIPLE
                }

                ch == '"' || ch == '\'' -> {
                    closeQuote(line, i)
                }

                ch == '[' || ch == '{' -> {
                    i + 1.also { depth++ }
                }

                ch == ']' || ch == '}' -> {
                    i + 1.also { depth-- }
                }

                else -> {
                    i + 1
                }
            }
        }

        private fun closeMulti(
            line: String,
            i: Int,
            ml: String,
        ): Int {
            val close = line.indexOf(ml, i)
            if (close < 0) return line.length
            multi = null
            return close + ml.length
        }

        private fun closeQuote(
            line: String,
            at: Int,
        ): Int {
            val q = line[at]
            var i = at + 1
            while (i < line.length && line[i] != q) i += if (q == '"' && line[i] == '\\') 2 else 1
            return minOf(i + 1, line.length)
        }
    }

    private const val TRIPLE = 3
}
