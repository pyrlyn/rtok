// JSONC reading (agents/jsonc.rs `parse`, `strip_comments`): comments and trailing commas, nothing else
// of JSON5. Lookup and validation only; the surgical editor is a later chunk.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object Jsonc {
    /** Parse JSONC into a tree. @throws kotlinx.serialization.SerializationException on invalid input. */
    fun parse(raw: String): JsonElement = Json.parseToJsonElement(stripTrailingCommas(stripComments(raw)))

    fun parseOrNull(raw: String): JsonElement? = runCatching { parse(raw) }.getOrNull()

    /** [raw] with `//…` and `/*…*/` outside strings removed. */
    fun stripComments(raw: String): String =
        rewrite(raw) { s, i, out ->
            skipComment(s, i) ?: run {
                out.append(s[i])
                i + 1
            }
        }

    /** [raw] (comment-free) with any `,` that only precedes `}` or `]` removed. */
    fun stripTrailingCommas(raw: String): String =
        rewrite(raw) { s, i, out ->
            if (s[i] != ',' || nextSignificant(s, i + 1).let { it != '}' && it != ']' }) out.append(s[i])
            i + 1
        }

    private inline fun rewrite(
        raw: String,
        step: (String, Int, StringBuilder) -> Int,
    ): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val end = scanString(raw, i)
            i =
                if (end != null) {
                    out.append(raw, i, end)
                    end
                } else {
                    step(raw, i, out)
                }
        }
        return out.toString()
    }

    private fun skipComment(
        s: String,
        i: Int,
    ): Int? =
        when {
            s.startsWith("//", i) -> s.indexOf('\n', i).let { if (it < 0) s.length else it }
            s.startsWith("/*", i) -> s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
            else -> null
        }

    private fun nextSignificant(
        s: String,
        from: Int,
    ): Char? = (from until s.length).firstOrNull { !s[it].isWhitespace() }?.let { s[it] }

    /** End (exclusive) of the `"`-string starting at [i], or null when [i] is not a quote. */
    private fun scanString(
        s: String,
        i: Int,
    ): Int? {
        if (s[i] != '"') return null
        var j = i + 1
        while (j < s.length && s[j] != '"') j += if (s[j] == '\\') 2 else 1
        return minOf(j + 1, s.length)
    }
}
