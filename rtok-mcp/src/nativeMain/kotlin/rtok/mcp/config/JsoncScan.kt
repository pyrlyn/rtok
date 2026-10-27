// The span scanners of src/agents/jsonc.rs (`scan_string`, `skip_trivia`, `skip_value`, `match_pair`, `find_key`,
// `root_open`, `last_value_end`, `excise_member`, `nth_element`, `locate`): byte-span lookups over the original text.
@file:Suppress("ReturnCount", "LoopWithTooManyJumpStatements")

package rtok.mcp.config

import kotlinx.serialization.json.JsonPrimitive
import rtok.json.StrictJson

internal object JsoncScan {
    data class At(
        val member: Int,
        val start: Int,
        val end: Int,
    )

    private fun ws(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c'

    /** End (exclusive) of the `"`-string starting at [i], or null when [i] is not a quote. */
    fun scanString(
        s: String,
        i: Int,
    ): Int? {
        if (s.getOrNull(i) != '"') return null
        var j = i + 1
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return null
    }

    fun skipTrivia(
        s: String,
        from: Int,
    ): Int {
        var i = from
        while (true) {
            while (i < s.length && ws(s[i])) i++
            when {
                s.startsWith("//", i) -> {
                    while (i < s.length && s[i] != '\n') i++
                }

                s.startsWith("/*", i) -> {
                    while (i < s.length && !s.startsWith("*/", i)) i++
                    i = minOf(i + 2, s.length)
                }

                else -> {
                    return i
                }
            }
        }
    }

    fun skipValue(
        s: String,
        from: Int,
    ): Int? {
        val i = skipTrivia(s, from)
        return when (s.getOrNull(i) ?: return null) {
            '{' -> {
                matchPair(s, i, '{', '}')
            }

            '[' -> {
                matchPair(s, i, '[', ']')
            }

            '"' -> {
                scanString(s, i)
            }

            else -> {
                var j = i
                while (j < s.length && !ws(s[j]) && s[j] !in ",}]/") j++
                if (j > i && skipTrivia(s, j) < s.length) j else null
            }
        }
    }

    private fun matchPair(
        s: String,
        i: Int,
        open: Char,
        close: Char,
    ): Int? {
        var depth = 0
        var j = i
        while (j < s.length) {
            val end = scanString(s, j)
            if (end != null) {
                j = end
                continue
            }
            if (s.startsWith("//", j) || s.startsWith("/*", j)) {
                j = skipTrivia(s, j)
                continue
            }
            if (s[j] == open) {
                depth++
            } else if (s[j] == close && --depth == 0) {
                return j + 1
            }
            j++
        }
        return null
    }

    /** `(key_start, value_start, value_end)` of [key] in the object opening at [open]. */
    fun findKey(
        s: String,
        open: Int,
        key: String,
    ): At? {
        var i = skipTrivia(s, open + 1)
        while (true) {
            if (s.getOrNull(i) == '}') return null
            val ks = i
            val ke = scanString(s, i) ?: return null
            val name = runCatching { (StrictJson.parse(s.substring(ks, ke)) as JsonPrimitive).content }.getOrNull() ?: return null
            i = skipTrivia(s, ke)
            if (s.getOrNull(i) != ':') return null
            val vs = skipTrivia(s, i + 1)
            val ve = skipValue(s, vs) ?: return null
            if (name == key) return At(ks, vs, ve)
            i = skipTrivia(s, ve)
            if (s.getOrNull(i) != ',') return null
            i = skipTrivia(s, i + 1)
        }
    }

    fun rootOpen(s: String): Int? = skipTrivia(s, 0).takeIf { s.getOrNull(it) == '{' }

    fun lastValueEnd(
        s: String,
        open: Int,
    ): Int? {
        var i = skipTrivia(s, open + 1)
        var last: Int? = null
        while (true) {
            if (s.getOrNull(i) == '}') return last
            val ke = scanString(s, i) ?: return null
            i = skipTrivia(s, ke)
            if (s.getOrNull(i) != ':') return last
            val ve = skipValue(s, skipTrivia(s, i + 1)) ?: return null
            last = ve
            i = skipTrivia(s, ve)
            if (s.getOrNull(i) != ',') return last
            i = skipTrivia(s, i + 1)
        }
    }

    /** Remove the member spanning `keyStart until valueEnd` plus one adjacent comma. */
    fun exciseMember(
        s: String,
        keyStart: Int,
        valueEnd: Int,
    ): String {
        var back = keyStart
        while (back > 0 && ws(s[back - 1])) back--
        if (back > 0 && s[back - 1] == ',') return s.substring(0, back - 1) + s.substring(valueEnd)
        val fwd = skipTrivia(s, valueEnd)
        if (s.getOrNull(fwd) == ',') {
            val gap = s.substring(valueEnd, fwd).let { if (it.isBlank()) "" else it }
            return s.substring(0, keyStart) + gap + s.substring(fwd + 1)
        }
        return s.substring(0, keyStart) + s.substring(valueEnd)
    }

    fun nthElement(
        s: String,
        open: Int,
        n: Int,
    ): Pair<Int, Int>? {
        var i = skipTrivia(s, open + 1)
        var k = 0
        while (true) {
            if (s.getOrNull(i) == ']') return null
            val ve = skipValue(s, i) ?: return null
            if (k == n) return i to ve
            i = skipTrivia(s, ve)
            if (s.getOrNull(i) != ',') return null
            i = skipTrivia(s, i + 1)
            k++
        }
    }

    fun locate(
        s: String,
        segs: List<Seg>,
    ): At? {
        val start = skipTrivia(s, 0)
        var at = At(start, start, skipValue(s, start) ?: return null)
        for (seg in segs) {
            val open = at.start
            val c = s.getOrNull(open) ?: return null
            at =
                when {
                    seg is Seg.Key && c == '{' -> findKey(s, open, seg.key) ?: return null
                    seg is Seg.Index && c == '[' -> nthElement(s, open, seg.n)?.let { (vs, ve) -> At(vs, vs, ve) } ?: return null
                    else -> return null
                }
        }
        return at
    }
}
