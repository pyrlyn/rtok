// The line-level pieces of the reader: one content line, comments, `key:` detection and block scalar bodies.
package yamlaot

/** A content line: its 1-based number, indent and text with the comment cut. */
internal class Line(
    val no: Int,
    val indent: Int,
    val text: String,
)

internal object Syntax {
    /** [raw] as a [Line], or null for a blank or comment-only line. */
    fun line(
        no: Int,
        raw: String,
    ): Line? {
        val body = raw.trimStart(' ')
        val content = body.substring(0, commentAt(body)).trimEnd()
        return if (content.isEmpty()) null else Line(no, raw.length - body.length, content)
    }

    /** Where a `#` comment starts in [s] (at the start or after a blank, outside quotes), or its length. */
    fun commentAt(s: String): Int {
        var quote: Char? = null
        var i = 0
        var at = s.length
        while (i < s.length && at == s.length) {
            val c = s[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> if (opensQuote(s, i)) quote = c
                c == '#' && (i == 0 || s[i - 1].isWhitespace()) -> at = i
            }
            i++
        }
        return at
    }

    private fun opensQuote(
        s: String,
        i: Int,
    ) = i == 0 || s[i - 1].isWhitespace() || s[i - 1] in "[{,:-"

    fun isDocStart(l: Line) = l.indent == 0 && (l.text == "---" || l.text.startsWith("--- "))

    fun isItem(t: String) = t == "-" || t.startsWith("- ")

    /** `key` and the text after `key:` (trimmed), or null when [l] is not a mapping entry. */
    fun keyOf(l: Line): Pair<String, String>? {
        val t = l.text
        val (key, at) =
            when {
                t.first() == '"' || t.first() == '\'' -> quotedKey(t, l.no)
                t.first() in "[{#" || isItem(t) -> null
                else -> plainKey(t)
            } ?: return null
        val after = t.substring(at).trimStart()
        val rest = after.removePrefix(":")
        val entry = after.startsWith(":") && (rest.isEmpty() || rest.first().isWhitespace())
        return if (entry) key.trim() to rest.trim() else null
    }

    private fun quotedKey(
        t: String,
        no: Int,
    ): Pair<String, Int>? {
        val end = Flow(t, no).end(0)
        val key = if (end < 0) null else Flow(t.substring(0, end), no).all() as? YamlValue.Str
        return key?.let { it.value to end }
    }

    /** A plain key: the text before the first `: ` (or a final `:`). */
    private fun plainKey(t: String): Pair<String, Int>? = t.indices.firstOrNull { t[it] == ':' && (it + 1 == t.length || t[it + 1] == ' ') }?.let { t.substring(0, it) to it }

    /** One past the last raw line of a block scalar whose header sits on 1-based line [no] under [indent]. */
    fun blockEnd(
        raw: List<String>,
        no: Int,
        indent: Int,
    ): Int {
        var end = no
        while (end < raw.size && (raw[end].isBlank() || raw[end].length - raw[end].trimStart(' ').length > indent)) end++
        return end
    }

    /** `|` / `>` with optional chomping (`-`, `+`) and indent digit, applied to the raw [body] lines. */
    fun blockScalar(
        header: String,
        indent: Int,
        body: List<String>,
    ): String {
        val mods = header.substring(1).trim()
        val explicit = mods.firstOrNull { it.isDigit() }?.digitToInt()
        val cut = explicit?.let { indent + it } ?: body.firstOrNull { it.isNotBlank() }?.let { it.length - it.trimStart(' ').length } ?: 0
        val content = body.map { if (it.length >= cut) it.substring(cut) else "" }
        val kept = content.dropLastWhile { it.isEmpty() }
        val text = if (header.first() == '|') kept.joinToString("\n") else fold(kept)
        val tail =
            when {
                '-' in mods || kept.isEmpty() -> ""
                '+' in mods -> "\n".repeat(content.size - kept.size + 1)
                else -> "\n"
            }
        return text + tail
    }

    /** `>`: adjacent lines joined by a space, a blank line kept as a newline. */
    private fun fold(lines: List<String>): String =
        buildString {
            for ((i, line) in lines.withIndex()) {
                when {
                    line.isEmpty() -> append('\n')
                    i > 0 && lines[i - 1].isNotEmpty() -> append(' ').append(line)
                    else -> append(line)
                }
            }
        }
}
