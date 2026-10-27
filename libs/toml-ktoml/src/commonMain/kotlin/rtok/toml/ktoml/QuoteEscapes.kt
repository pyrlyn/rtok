// A text pass before ktoml: ktoml splits arrays of inline tables at a `}` or `,` that follows an escaped quote inside a
// basic string (`[{ x = "\"}" }]` fails to parse; Rust `toml` reads it). `\u0022` is the same character to TOML and
// does not confuse ktoml, so every `\"` inside a basic string is rewritten to it. Lines and columns of everything
// outside strings are unchanged.
package rtok.toml.ktoml

private const val QUOTE_ESCAPE = "\\u0022"
private const val TRIPLE = 3

/** At most two quotes may sit just before a multi-line string's closing `"""` (`""""` ends in a quote). */
private const val MAX_CLOSING_RUN = 5

/** [text] with every `\"` in a basic (single- or multi-line) string replaced by `\u0022`. */
internal fun escapeQuotesForKtoml(text: String): String = if (!text.contains("\\\"")) text else QuoteScanner(text).run()

private class QuoteScanner(
    private val text: String,
) {
    private val out = StringBuilder(text.length + 16)
    private var i = 0

    fun run(): String {
        while (i < text.length) {
            when {
                text[i] == '#' -> copyThrough("\n", 0)
                text.startsWith("\'\'\'", i) -> copyThrough("\'\'\'", TRIPLE)
                text[i] == '\'' -> copyThrough("\'", 1)
                text.startsWith("\"\"\"", i) -> basic(multiline = true)
                text[i] == '"' -> basic(multiline = false)
                else -> take(1)
            }
        }
        return out.toString()
    }

    private fun take(count: Int) {
        val end = minOf(i + count, text.length)
        out.append(text, i, end)
        i = end
    }

    /** Copies the opener ([skip] chars), then everything through the next [end] (or the rest). */
    private fun copyThrough(
        end: String,
        skip: Int,
    ) {
        val at = text.indexOf(end, i + skip)
        val stop = if (at < 0) text.length else at + end.length
        out.append(text, i, stop)
        i = stop
    }

    private fun basic(multiline: Boolean) {
        take(if (multiline) TRIPLE else 1)
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                if (text[i + 1] == '"') out.append(QUOTE_ESCAPE) else out.append(text, i, i + 2)
                i += 2
            } else if (multiline && text.startsWith("\"\"\"", i)) {
                var end = i + TRIPLE
                while (end < text.length && end < i + MAX_CLOSING_RUN && text[end] == '"') end++
                take(end - i)
                return
            } else {
                take(1)
                if (!multiline && (c == '"' || c == '\n')) return
            }
        }
    }
}
