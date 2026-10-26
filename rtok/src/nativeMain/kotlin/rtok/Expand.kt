// Port of src/expand.rs: `rtok expand <id>` and the shared fetch used by the MCP `expand` tool.
package rtok

import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Measurement
import rtok.store.archiveIdsWithPrefix
import rtok.store.getArchive
import rtok.store.liveZonePointer
import rtok.store.markExpandedRecorded
import rtok.sys.rustLines
import rtok.sys.scalarCount
import rtok.sys.takeLastScalars
import rtok.sys.takeScalars

/** An expand request the caller got wrong (bad range, unknown or ambiguous id). */
class ExpandException(message: String) : Exception(message)

object Expand {
    /** How a json_tree pointer starts; recognised even when that plugin is compiled out. */
    const val JSON_TREE_PREFIX = "[json-tree "

    fun fetch(cx: Runtime, id: String): ByteArray? = fetchRange(cx, id, null)

    /** T534: the range is checked against the payload's line count before anything is frozen or charged. */
    fun fetchRange(cx: Runtime, id: String, lines: String?): ByteArray? {
        val full = resolveId(cx, id)
        val bytes = cx.store.getArchive(full, cx.config.core.archiveDir) ?: return null
        if (lines != null) parseRange(lines, bytes.decodeToString().rustLines().size)
        val plugin = when (val p = cx.store.liveZonePointer(full)) {
            null -> "archive"
            else -> when { p.startsWith("[toon ") -> "toon"; p.startsWith(JSON_TREE_PREFIX) -> "json_tree"; else -> "archive" }
        }
        // T208: freeze + measurement commit in one transaction.
        cx.store.markExpandedRecorded(cx.session, full, Measurement(plugin, "expand", 0u, bytes.size.toULong(), 0u,
            cx.estimate(bytes.decodeToString(), Class.Code), full, cx.callId))
        return bytes
    }

    /**
     * T661: a hex prefix of at least 8 chars names the one archive starting with it; a full id or an unmatched
     * prefix passes through. A restricted runtime resolves only its own grants and never falls back globally.
     */
    fun resolveId(cx: Runtime, id: String): String {
        val scoped = cx.scopedArchiveIds(id)
        val restricted = scoped != null
        if (id.length > 64 || id.length < MIN_PREFIX || !id.all(::isHex)) {
            if (restricted) throw ExpandException(unknownIdMessage(id))
            return id
        }
        if (id.length == 64 && !restricted) return id
        val hits = scoped ?: cx.store.archiveIdsWithPrefix(id, 2)
        return when {
            hits.size == 1 -> hits[0]
            hits.isEmpty() && restricted -> throw ExpandException(unknownIdMessage(id))
            hits.isEmpty() -> id
            restricted -> throw ExpandException("archive id prefix $id is ambiguous; use a longer prefix")
            else -> throw ExpandException(ambiguousMessage(id, hits))
        }
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    internal fun ambiguousMessage(id: String, hits: List<String>) =
        "archive id prefix $id is ambiguous; use one of: " + hits.joinToString(", ") { it.take(id.length + 4) }

    /** T354: an id that cannot be one (`-`, `/dev/stdin`) gets a hint that expand does not read stdin. */
    fun unknownIdMessage(id: String): String =
        if (id.length in 8..64 && id.all(::isHex)) "unknown archive id: $id"
        else "unknown archive id: $id (expand takes the hex id from an \"expand <id>\" trailer; it does not read stdin)"

    /** 1-based inclusive line range over already-split lines. */
    fun <T> sliceLines(lines: List<T>, a: Int, b: Int): List<T> = lines.take(b).drop(maxOf(a - 1, 0))

    /**
     * `--lines` / `--grep` filtering shared with the MCP `expand` tool. Hits print as `N:line`; with
     * [context] > 0 each hit carries N lines either side, touching windows merged, blocks split by `--`.
     */
    fun filterLines(text: String, lines: String?, grep: String?, context: Int): List<String> {
        var out = text.rustLines().mapIndexed { i, l -> (i + 1) to l }
        if (lines != null) { val (a, b) = parseRange(lines, out.size); out = sliceLines(out, a, b) }
        if (grep == null) return out.map { it.second }
        val re = regexOrLiteral(grep)
        if (context == 0) return out.filter { re.containsMatchIn(it.second) }.map { (n, l) -> "$n:$l" }
        val windows = ArrayList<IntArray>()
        for ((i, p) in out.withIndex()) {
            if (!re.containsMatchIn(p.second)) continue
            val lo = maxOf(i - context, 0); val hi = minOf(i.toLong() + context, out.size - 1L).toInt()
            val last = windows.lastOrNull()
            if (last != null && lo <= last[1] + 1) last[1] = maxOf(last[1], hi) else windows += intArrayOf(lo, hi)
        }
        val rendered = ArrayList<String>()
        for ((w, win) in windows.withIndex()) {
            if (w > 0) rendered += "--"
            for (k in win[0]..win[1]) rendered += "${out[k].first}:${out[k].second}"
        }
        return rendered
    }

    /** A regex, or the literal text when [pattern] does not compile (the `expand --grep` / `search` grammar). */
    fun regexOrLiteral(pattern: String): Regex = runCatching { Regex(pattern) }.getOrElse { Regex(Regex.escape(pattern)) }

    /** Head and tail of [text] around [marker], at most [max] chars in total; the marker is the floor. */
    fun cut(text: String, marker: String, max: Int): String {
        val keep = maxOf(max - marker.scalarCount(), 0) / 2
        val head = text.takeScalars(keep)
        val tail = if (keep == 0) "" else text.takeLastScalars(minOf(keep, text.scalarCount()))
        return head + marker + tail
    }

    private fun <T> capLines(out: MutableList<T>, maxLines: UInt): Int {
        if (maxLines == 0u || out.size <= maxLines.toInt()) return 0
        val omitted = out.size - maxLines.toInt()
        while (out.size > maxLines.toInt()) out.removeAt(out.size - 1)
        return omitted
    }

    /** Filtered lines plus an optional `[expand] max_lines` trailer. */
    fun renderLines(text: String, id: String, lines: String?, grep: String?, context: Int, maxLines: UInt): String {
        val out = filterLines(text, lines, grep, context).toMutableList()
        val omitted = capLines(out, maxLines)
        val sb = StringBuilder(out.joinToString("\n"))
        if (omitted > 0) {
            if (sb.isNotEmpty()) sb.append('\n')
            val shown = if (id.length >= 64) shortId(id) else id
            sb.append("… $omitted lines omitted (expand $shown)")
        }
        return sb.toString()
    }

    /** The bytes `rtok expand` writes: raw archive bytes without filters, else the rendered text plus `\n`. */
    fun run(cfg: Config, id: String, lines: String?, grep: String?, context: Int): ByteArray {
        // Validate before fetch: fetching a live-zone pointer freezes it.
        if (lines != null) parseRange(lines, Int.MAX_VALUE)
        Runtime.open(cfg, "expand").use { cx -> return render(cx, id, lines, grep, context) }
    }

    /** [run] over an open runtime. */
    fun render(cx: Runtime, id: String, lines: String?, grep: String?, context: Int): ByteArray {
        val bytes = fetchRange(cx, id, lines) ?: throw ExpandException(unknownIdMessage(id))
        val maxLines = cx.config.expand.maxLines
        if (lines == null && grep == null && maxLines == 0u) return bytes
        val rendered = renderLines(bytes.decodeToString(), id, lines, grep, context, maxLines)
        return if (rendered.isEmpty()) ByteArray(0) else (rendered + "\n").encodeToByteArray()
    }

    private fun stripQuotes(spec: String): String =
        if (spec.length >= 2 && ((spec.first() == '"' && spec.last() == '"') || (spec.first() == '\'' && spec.last() == '\''))) spec.substring(1, spec.length - 1) else spec

    /** End index (exclusive) of `<br>`, `<br/>`, `<br />` at [start]. */
    private fun brTagEnd(s: String, start: Int): Int? {
        var i = start + 1
        if (i + 1 >= s.length || s[i].lowercaseChar() != 'b' || s[i + 1].lowercaseChar() != 'r') return null
        i += 2
        while (i < s.length && s[i].isAsciiWhitespace()) i++
        if (i < s.length && s[i] == '/') { i++; while (i < s.length && s[i].isAsciiWhitespace()) i++ }
        return if (i < s.length && s[i] == '>') i + 1 else null
    }

    private fun Char.isAsciiWhitespace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

    private fun stripTrailingBr(s: String): String? {
        val t = s.trimEnd()
        val start = t.lastIndexOf('<').takeIf { it >= 0 } ?: return null
        val end = brTagEnd(t, start) ?: return null
        return if (end == t.length) t.substring(0, start) else null
    }

    private fun isRangeSep(c: Char) = c == '-' || c == ',' || c == ':' || c == '_' || c.isWhitespace()

    /** T522: fold `[a-b]`, `a - b`, `a:b`, `a,b`, `235"-"270` and `<br>` breaks into `a[-b]`; `lone` = trailing `<br>`. */
    private fun normalizeRange(raw: String): Pair<String, Boolean> {
        var s = raw
        if (s.length >= 2 && s.startsWith('[') && s.endsWith(']')) s = s.substring(1, s.length - 1).trim()
        var lone = false
        while (true) { val rest = stripTrailingBr(s) ?: break; lone = true; s = rest.trimEnd() }
        val out = StringBuilder(); var pendingSep = false; var idx = 0
        while (idx < s.length) {
            if (s[idx] == '<') { val end = brTagEnd(s, idx); if (end != null) { idx = end; pendingSep = true; continue } }
            val c = s[idx]
            val hasNext = idx + 1 < s.length
            if ((c == '"' || c == '\'') && idx > 0 && hasNext) {
                val prevSep = pendingSep || (out.isNotEmpty() && isRangeSep(out.last()))
                if (prevSep || isRangeSep(s[idx + 1])) { idx++; continue }
            }
            if (isRangeSep(c)) { pendingSep = true; idx++; continue }
            if (pendingSep) { out.append('-'); pendingSep = false }
            out.append(c); idx++
        }
        if (pendingSep) out.append('-')
        return out.toString() to lone
    }

    private fun parseUsize(s: String): Long? = if (s.isNotEmpty() && s.all { it in '0'..'9' } || (s.startsWith('+') && s.length > 1 && s.drop(1).all { it in '0'..'9' })) s.removePrefix("+").toLongOrNull() else null

    /** `a`, `a-b`, `a-`, `-b` (1-based, inclusive) against [n] lines; any other spelling fails. */
    fun parseRange(spec0: String, n: Int): Pair<Int, Int> {
        val spec = stripQuotes(spec0.trim()).trim()
        fun bad(): Nothing = throw ExpandException("invalid line range `$spec`: expected a positive line or a-b")
        if (spec.isEmpty()) bad()
        val (norm, lone) = normalizeRange(spec)
        if (norm.none { it in '0'..'9' }) bad()
        val dash = norm.indexOf('-')
        val start = if (dash < 0) norm else norm.substring(0, dash)
        val end = if (dash < 0) null else norm.substring(dash + 1)
        val a = if (start.isEmpty()) 1L else parseUsize(start) ?: bad()
        val b = if (end.isNullOrEmpty()) null else parseUsize(end) ?: bad()
        if (a == 0L || b == 0L) throw ExpandException("invalid line range `$spec`: lines are 1-based")
        if (b != null && a > b) throw ExpandException("invalid line range `$spec`: start exceeds end")
        if (a > n) throw ExpandException("invalid line range `$spec`: start exceeds line count $n")
        if (lone && b == null) return a.toInt() to a.toInt()
        return a.toInt() to minOf(b ?: n.toLong(), n.toLong()).toInt()
    }
}
