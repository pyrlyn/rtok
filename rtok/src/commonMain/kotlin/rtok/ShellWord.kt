// Port of `skip_word` in src/plugins/mod.rs; shared by measure and guard.
package rtok

/**
 * Skips one shell word (bare, or with `'…'` / `"…"` segments) and returns what follows it,
 * left-trimmed. An unterminated quote yields `null` so the caller fails open.
 */
fun skipWord(s: String): String? {
    var quote: Char? = null
    for ((i, c) in s.withIndex()) {
        val q = quote
        when {
            q != null -> if (c == q) quote = null
            c == '\'' || c == '"' -> quote = c
            c.isAsciiWhitespace() -> return s.substring(i).trimStart(::isAsciiWs)
        }
    }
    return if (quote == null) "" else null
}

private fun isAsciiWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'
private fun Char.isAsciiWhitespace() = isAsciiWs(this)
