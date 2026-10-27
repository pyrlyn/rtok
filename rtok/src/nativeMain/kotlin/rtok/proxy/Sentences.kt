// Port of the sentence helpers in src/proxy/tools_rewrite.rs: split at `.`/`!`/`?` before whitespace (not after
// `e.g.`/`i.e.`/`vs.`/`cf.` or before a lowercase word), and cut text to a token budget at a sentence end.
package rtok.proxy

import rtok.config.Estimator
import rtok.estimate
import rtok.plugin.sdk.Class

internal object Sentences {
    internal fun truncateAtSentence(
        text: String,
        max: Int,
        est: Estimator,
    ): String {
        if (estimate(text, Class.Prose, est) <= max) return text
        var out = ""
        var overflow = false
        for (s in sentences(text)) {
            val cand = out + s
            if (estimate(cand.trim(), Class.Prose, est) > max) {
                overflow = out.isEmpty()
                break
            }
            out = cand
        }
        return if (overflow) prefixTokens(text, max, est) else out.trim()
    }

    private fun Char.asciiSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            val end = text[i] in ".!?" && (i + 1 == text.length || text[i + 1].asciiSpace())
            if (end && !continuesSentence(text, start, i)) {
                out += text.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    /** A stop after `e.g.`/`i.e.`/`vs.`/`cf.` or before a lowercase word is not a sentence end. */
    private fun continuesSentence(
        text: String,
        start: Int,
        dot: Int,
    ): Boolean {
        val word =
            text
                .substring(start, dot)
                .split(Regex("\\s"))
                .last()
                .lowercase()
        return word in setOf("e.g", "i.e", "vs", "cf") ||
            text
                .substring(dot + 1)
                .trimStart()
                .firstOrNull()
                ?.isLowerCase() == true
    }

    private fun prefixTokens(
        text: String,
        max: Int,
        est: Estimator,
    ): String {
        var i = 0
        var keep = 0
        while (i < text.length) {
            val next = if (text[i].isHighSurrogate() && i + 1 < text.length) i + 2 else i + 1
            if (estimate(text.substring(0, next), Class.Prose, est) > max) break
            keep = next
            i = next
        }
        return text.substring(0, keep)
    }
}
