// Flow collections and quoted scalars (`[a, "b"]`, `{k: v}`, `'it''s'`, `"\u00e9"`) read from one string.
package yamlaot

internal class Flow(
    private val s: String,
    private val line: Int,
) {
    private var i = 0

    fun fail(why: String): Nothing = throw YamlAotException("line $line: $why")

    /** The whole string as one value; anything left over is an error. */
    fun all(): YamlValue {
        val v = value()
        skipSpace()
        if (i < s.length) fail("unexpected `${s.substring(i)}`")
        return v
    }

    /** Where a quoted or flow value that starts at [from] ends in [s] (exclusive), or -1 while it is still open. */
    fun end(from: Int): Int {
        i = from
        return runCatching { value() }.fold({ i }, { -1 })
    }

    private fun skipSpace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): YamlValue {
        skipSpace()
        if (i >= s.length) return YamlValue.Null
        return when (s[i]) {
            '[' -> YamlValue.Seq(mutableListOf<YamlValue>().also { items -> collection(']') { items += value() } })
            '{' -> YamlValue.Map(LinkedHashMap<String, YamlValue>().also { m -> collection('}') { entry(m) } })
            '"', '\'' -> YamlValue.Str(quoted())
            else -> YamlValue.plain(plain(stopAtColon = false))
        }
    }

    /** The items of a collection opened at [i], each read by [item], up to [close]. */
    private fun collection(
        close: Char,
        item: () -> Unit,
    ) {
        i++
        var more = true
        while (more) {
            skipSpace()
            if (i >= s.length) fail("unclosed collection")
            more = s[i] != close
            if (more) {
                item()
                more = separator(close)
            }
        }
        i++
    }

    /** One `key: value` (or a bare `key`) of a flow mapping. */
    private fun entry(into: MutableMap<String, YamlValue>) {
        val key = if (s[i] == '"' || s[i] == '\'') quoted() else plain(stopAtColon = true)
        skipSpace()
        val colon = i < s.length && s[i] == ':'
        if (colon) i++
        skipSpace()
        val empty = !colon || i >= s.length || s[i] in ",}"
        into[key] = if (empty) YamlValue.Null else value()
    }

    /** After an item: `,` (true, more may follow) or the closer (false). */
    private fun separator(close: Char): Boolean {
        skipSpace()
        if (i >= s.length) fail("unclosed collection")
        return when (s[i]) {
            ',' -> true.also { i++ }
            close -> false
            else -> fail("expected `,` or `$close`")
        }
    }

    private fun plain(stopAtColon: Boolean): String {
        val start = i
        while (i < s.length && !plainEnds(s, i, start, stopAtColon)) i++
        return s.substring(start, i).trim()
    }

    /** A single- (`''` escapes `'`) or double-quoted (backslash escapes) string at [i]. */
    private fun quoted(): String {
        val q = s[i++]
        val out = StringBuilder()
        var open = true
        while (open) {
            if (i >= s.length) fail("unclosed `$q`")
            val c = s[i++]
            when {
                c == q && q == '\'' && s.getOrNull(i) == '\'' -> out.append('\'').also { i++ }
                c == q -> open = false
                c == '\\' && q == '"' -> escape(out)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun escape(out: StringBuilder) {
        if (i >= s.length) fail("unclosed `\"`")
        val e = s[i++]
        ESCAPES[e]?.let {
            out.append(it)
            return
        }
        val width = HEX_WIDTHS[e] ?: fail("bad escape `\\$e`")
        val code = s.substring(i, minOf(s.length, i + width)).takeIf { it.length == width }?.toIntOrNull(HEX) ?: fail("bad `\\$e` escape")
        i += width
        if (code < SUPPLEMENT) {
            out.append(code.toChar())
        } else {
            out.append(Char.MIN_HIGH_SURROGATE + ((code - SUPPLEMENT) shr TEN)).append(Char.MIN_LOW_SURROGATE + ((code - SUPPLEMENT) and LOW_MASK))
        }
    }

    private companion object {
        const val HEX = 16
        const val TEN = 10
        const val SUPPLEMENT = 0x10000
        const val LOW_MASK = 0x3ff
        const val INDICATORS = ",[]{}"
        val ESCAPES =
            mapOf(
                '0' to "\u0000",
                'a' to "\u0007",
                'b' to "\b",
                't' to "\t",
                '\t' to "\t",
                'n' to "\n",
                'v' to "\u000b",
                'f' to "\u000c",
                'r' to "\r",
                'e' to "\u001b",
                ' ' to " ",
                '"' to "\"",
                '/' to "/",
                '\\' to "\\",
                'N' to "\u0085",
                '_' to "\u00a0",
            )
        val HEX_WIDTHS = mapOf('x' to 2, 'u' to 4, 'U' to 8)

        fun plainEnds(
            s: String,
            i: Int,
            start: Int,
            stopAtColon: Boolean,
        ): Boolean {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            return c in INDICATORS ||
                (c == ':' && (stopAtColon || next == null || next.isWhitespace() || next in INDICATORS)) ||
                (c == '#' && i > start && s[i - 1].isWhitespace())
        }
    }
}
