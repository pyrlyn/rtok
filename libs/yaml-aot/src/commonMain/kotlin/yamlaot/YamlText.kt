// Line-level editing for YAML that the user and the host both edit: a caller finds its own lines and swaps only those,
// so comments, ordering and quoting elsewhere stay byte for byte. Lines keep their own line ending.
package yamlaot

object YamlText {
    /** [raw] split after each `\n`, endings kept; an empty text has no lines. */
    fun lines(raw: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < raw.length) {
            val nl = raw.indexOf('\n', start)
            val end = if (nl < 0) raw.length else nl + 1
            out += raw.substring(start, end)
            start = end
        }
        return out
    }

    /** A scalar as YAML text: plain when that is unambiguous, else double-quoted (JSON escapes are YAML ones). */
    fun scalar(s: String): String {
        val plain = s.isNotEmpty() && s.all { it.isAsciiLetterOrDigit() || it in "_./-" } && YamlValue.plain(s) is YamlValue.Str
        return if (plain) s else quote(s)
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private const val HEX = 16
    private const val WIDTH = 4
    private const val LAST_CONTROL = 0x1f

    private fun quote(s: String) =
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\t' -> append("\\t")
                    c == '\r' -> append("\\r")
                    c.code <= LAST_CONTROL -> append("\\u").append(c.code.toString(HEX).padStart(WIDTH, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }

    /** The line without its ending. */
    fun text(line: String) = line.trimEnd('\n', '\r')

    fun indentOf(line: String) = text(line).length - text(line).trimStart(' ').length

    /** Blank or comment-only. */
    fun skippable(line: String) = text(line).trimStart().let { it.isEmpty() || it.startsWith("#") }

    fun eolOf(raw: String) = if ("\r\n" in raw) "\r\n" else "\n"

    /** [lines] with `[from, to)` swapped for [block]; a last kept line without a newline gets one first. */
    fun splice(
        lines: List<String>,
        from: Int,
        to: Int,
        block: String,
        eol: String,
    ): String =
        buildString {
            lines.subList(0, from).forEach(::append)
            if (block.isNotEmpty() && isNotEmpty() && !endsWith("\n")) append(eol)
            append(block)
            lines.subList(to, lines.size).forEach(::append)
        }

    /** A top-level `key:` and the lines of its block. */
    data class Block(
        /** The `key:` line. */
        val header: Int,
        /** What follows `key:` on its line, comment included, trimmed. */
        val tail: String,
        /** One past the last content line of the block. */
        val end: Int,
        /** The indent of the first child line (2 when there is none). */
        val childIndent: Int,
    )

    /**
     * The first top-level `key:` line and its block: the lines after it up to the next top-level line that is not a
     * `-` item (a list may sit at the key's own indent). Null when there is no such key.
     */
    fun block(
        lines: List<String>,
        key: String,
    ): Block? {
        val header = lines.indexOfFirst { text(it).startsWith("$key:") }
        if (header < 0) return null
        val stop = (header + 1 until lines.size).firstOrNull { !skippable(lines[it]) && indentOf(lines[it]) == 0 && !text(lines[it]).startsWith("-") } ?: lines.size
        val end = (header + 1 until stop).lastOrNull { !skippable(lines[it]) }?.plus(1) ?: (header + 1)
        val child = lines.subList(header + 1, end).firstOrNull { !skippable(it) }?.let(::indentOf) ?: 2
        return Block(header, text(lines[header]).substring(key.length + 1).trim(), end, child)
    }

    /** The last non-skippable line in `[from, to)`, or [from] when all are. */
    fun lastContent(
        lines: List<String>,
        from: Int,
        to: Int,
    ): Int = (to - 1 downTo from).firstOrNull { !skippable(lines[it]) } ?: from
}
