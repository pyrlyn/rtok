// Port of crates/rtok-store/src/sanitize.rs: one cleaning pass for stored bodies — terminal escapes,
// stray control and zero-width characters, CRLF, trailing blanks, blank-line runs, and harness
// wrapper blocks. The returned value is `null`-free: clean input comes back as the same instance.
package rtok.store

import kotlinx.serialization.json.*

object Sanitize {
    private val WRAPPERS = listOf("system-reminder", "task-notification", "ci-monitor-event", "local-command-caveat")
    private val TAGS = WRAPPERS.map { "<$it>" to "</$it>" }

    /** A request body cleaned: JSON string values one by one (keys untouched), else plain UTF-8 text; else as is. */
    fun body(bytes: ByteArray): ByteArray {
        val s = bytes.decodeUtf8OrNull() ?: return bytes
        SerdeJson.parse(s)?.let { v ->
            val (out, hit) = strings(v, ::text)
            return if (!hit) bytes else SerdeJson.encode(out).encodeToByteArray()
        }
        val clean = text(s)
        return if (clean === s) bytes else clean.encodeToByteArray()
    }

    /** Clean every string value in `value`; returns the new value and whether any changed. */
    fun strings(
        value: JsonElement,
        clean: (String) -> String,
    ): Pair<JsonElement, Boolean> =
        when (value) {
            is JsonPrimitive -> {
                if (!value.isString) value to false else clean(value.content).let { c -> if (c == value.content) value to false else JsonPrimitive(c) to true }
            }

            is JsonArray -> {
                var hit = false
                JsonArray(value.map { strings(it, clean).also { r -> hit = hit or r.second }.first }) to hit
            }

            is JsonObject -> {
                var hit = false
                JsonObject(value.mapValues { strings(it.value, clean).also { r -> hit = hit or r.second }.first }) to hit
            }
        }

    /** The full cleanup for one stored string; returns `s` itself when nothing changed. */
    fun text(s: String): String {
        val clean = whitespace(unwrap(characters(s, true)))
        return if (clean == s) s else clean
    }

    private fun unwrap(s: String): String {
        var cur = s
        while (true) cur = strip(cur) ?: return cur
    }

    private fun strip(s: String): String? {
        if (!s.contains("</")) return null
        val last = TAGS.map { s.lastIndexOf(it.second).takeIf { i -> i >= 0 } }
        val out = StringBuilder()
        var kept = 0
        var at = 0
        while (true) {
            val start = s.indexOf('<', at).takeIf { it >= 0 } ?: break
            at = start + 1
            val i = TAGS.indexOfFirst { s.startsWith(it.first, start) }
            if (i < 0) continue
            val (opening, closing) = TAGS[i]
            val body = start + opening.length
            if (!(last[i]?.let { it >= body } ?: false)) continue
            val q = s.indexOf(closing, body).takeIf { it >= 0 } ?: continue
            val end = q + closing.length
            out.append(s, kept, start)
            at = end
            while (at < s.length && s[at] == '\n') at++
            kept = at
        }
        if (kept == 0) return null
        out.append(s, kept, s.length)
        return out.toString()
    }

    /** Terminal escapes and invisible characters only; line ends, blanks, wrappers stay. */
    fun terminalNoise(s: String): String {
        val c = characters(s, false)
        return if (c == s) s else c
    }

    private fun characters(
        s: String,
        lineEnds: Boolean,
    ): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> {
                    i = skipEscape(s, i)
                    continue
                }

                c == '\r' && lineEnds && i + 1 < s.length && s[i + 1] == '\n' -> {}

                c == '\r' && lineEnds -> {
                    out.append('\n')
                }

                c == '\n' || c == '\t' || c == '\r' -> {
                    out.append(c)
                }

                c == '\u200b' || c == '\u2060' || c == '\ufeff' -> {}

                c.isISOControl() -> {}

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun whitespace(s: String): String {
        val out = StringBuilder(s.length)
        var newlines = 0
        s.split('\n').forEachIndexed { n, raw ->
            if (n > 0) {
                newlines++
                if (newlines <= 2) out.append('\n')
            }
            val line = raw.trimEnd(' ', '\t')
            if (line.isNotEmpty()) {
                newlines = 0
                out.append(line)
            }
        }
        return out.toString()
    }

    /** Index after the escape sequence starting at `i` (CSI, OSC, or a two-byte escape). */
    fun skipEscape(
        body: String,
        i: Int,
    ): Int {
        var j = i + 1
        if (j >= body.length) return j
        return when (val c = body[j]) {
            '[' -> {
                j++
                while (j < body.length && body[j].code !in 0x40..0x7e) j++
                minOf(j + 1, body.length)
            }

            ']' -> {
                j++
                while (j < body.length) {
                    if (body[j] == '\u0007') return j + 1
                    if (body[j] == '\u001b' && j + 1 < body.length && body[j + 1] == '\\') return j + 2
                    j++
                }
                j
            }

            else -> {
                if (c.code < 0x80) j + 1 else j
            }
        }
    }
}

internal fun ByteArray.decodeUtf8OrNull(): String? =
    try {
        decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        null
    }
