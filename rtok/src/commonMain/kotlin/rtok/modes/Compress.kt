// Port of src/modes/compress.rs — deterministic English fluff stripper that never
// mutates fenced code bodies or backtick spans, and never drops negation tokens.
package rtok.modes

enum class CaveIntensity { Lite, Full, Ultra }

private val PLEASANTRIES = listOf(
    "I'd be happy to help you with that.", "I'd be happy to help you with that",
    "I'd be happy to help!", "I'd be happy to help.", "I'd be happy to help",
    "I would be happy to help.", "I would be happy to help",
    "Happy to help!", "Happy to help.", "Of course!", "Of course.",
    "Certainly!", "Certainly.", "Sure thing!", "Sure thing.", "Sure!", "Sure.",
)
private val FILLER_WORDS = listOf("just", "really", "basically", "actually", "simply", "literally")
private val ARTICLES = listOf("a", "an", "the")
private val CONJUNCTION_PAD = listOf("and then", "and also", "as well as")
private val KEEP_WORDS = setOf("not", "never", "no", "only", "except")

fun compressProse(text: String, intensity: CaveIntensity): String {
    val out = StringBuilder(text.length)
    var rest = text
    while (true) {
        val (start, end) = findFence(rest) ?: break
        out.append(compressOutside(rest.substring(0, start), intensity))
        out.append(rest, start, end)
        rest = rest.substring(end)
    }
    out.append(compressOutside(rest, intensity))
    return out.toString()
}

/** split_inclusive('\n') */
private fun splitInclusive(text: String): List<String> {
    val res = ArrayList<String>(); var s = 0
    for (i in text.indices) if (text[i] == '\n') { res += text.substring(s, i + 1); s = i + 1 }
    if (s < text.length) res += text.substring(s)
    return res
}

internal fun findFence(text: String): Pair<Int, Int>? {
    val lines = splitInclusive(text)
    var pos = 0; var idx = 0
    var open: Triple<Int, Char, Int>? = null
    while (idx < lines.size) {
        val line = lines[idx]; val start = pos; pos += line.length; idx++
        val r = fenceRun(line) ?: continue
        if (r.first != '`' || !r.third.contains('`')) { open = Triple(start, r.first, r.second); break }
    }
    val (start, ch, len) = open ?: return null
    while (idx < lines.size) {
        val line = lines[idx]; val at = pos; pos += line.length; idx++
        val r = fenceRun(line) ?: continue
        if (r.first == ch && r.second >= len && r.third.isRustTrimEmpty()) return start to (at + line.length - r.third.length)
    }
    return start to text.length
}

private fun String.isRustTrimEmpty() = all { it.isWhitespace() }

private fun fenceRun(line: String): Triple<Char, Int, String>? {
    val body = line.trimStart(' ')
    if (line.length - body.length > 3) return null
    val ch = body.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val run = body.takeWhile { it == ch }.length
    return if (run >= 3) Triple(ch, run, body.substring(run)) else null
}

private sealed interface Seg { val s: String }
private class Prose(override val s: String) : Seg
private class Protected(override val s: String) : Seg

private fun inlineSegments(text: String): List<Seg> {
    val segs = ArrayList<Seg>(); var proseStart = 0; var cur = 0; val n = text.length
    while (cur < n) {
        if (text[cur] != '`') { cur++; continue }
        val opener = cur
        while (cur < n && text[cur] == '`') cur++
        val dl = cur - opener
        var closingEnd: Int? = null
        while (cur < n) {
            if (text[cur] != '`') { cur++; continue }
            val closing = cur
            while (cur < n && text[cur] == '`') cur++
            if (cur - closing == dl) { closingEnd = cur; break }
        }
        if (proseStart < opener) segs += Prose(text.substring(proseStart, opener))
        if (closingEnd != null) { segs += Protected(text.substring(opener, closingEnd)); proseStart = closingEnd }
        else { segs += Protected(text.substring(opener)); proseStart = n }
    }
    if (proseStart < n) segs += Prose(text.substring(proseStart))
    return segs
}

private fun compressOutside(prose: String, intensity: CaveIntensity): String {
    val segs = inlineSegments(prose)
    val last = (segs.size - 1).coerceAtLeast(0)
    val out = StringBuilder(prose.length)
    segs.forEachIndexed { index, seg ->
        when (seg) {
            is Protected -> out.append(seg.s)
            is Prose -> {
                var s = if (index == 0) stripLeadingPleasantries(seg.s) else seg.s
                if (intensity != CaveIntensity.Lite) {
                    s = stripWords(s, FILLER_WORDS); s = stripWords(s, ARTICLES)
                    if (intensity == CaveIntensity.Ultra) for (p in CONJUNCTION_PAD) s = replaceCi(s, p, " ")
                }
                out.append(cleanupWsSegment(s, index > 0, index < last))
            }
        }
    }
    return out.toString()
}

private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
private fun Char.isAsciiAlpha() = this in 'a'..'z' || this in 'A'..'Z'
private fun String.asciiLower() = buildString { for (c in this@asciiLower) append(if (c in 'A'..'Z') c + 32 else c) }

private fun stripLeadingPleasantries(text: String): String {
    var rest = text
    while (true) {
        val trimmed = rest.trimStart { it.isWhitespace() }
        val phrase = PLEASANTRIES.firstOrNull { p ->
            trimmed.length >= p.length && trimmed.substring(0, p.length).asciiLower() == p.asciiLower() &&
                trimmed.getOrNull(p.length).let { c -> c == null || (!c.isAsciiAlnum() && c != '\'') }
        } ?: break
        rest = trimmed.substring(phrase.length)
    }
    return rest
}

internal fun replaceCi(hay: String, needle: String, with: String): String {
    if (needle.isEmpty()) return hay
    val lower = hay.asciiLower(); val nd = needle.asciiLower()
    val out = StringBuilder(hay.length); var i = 0
    while (true) {
        val at = lower.indexOf(nd, i); if (at < 0) break
        val end = at + needle.length
        val jl = at > 0 && isWordChar(hay[at - 1]); val jr = end < hay.length && isWordChar(hay[end])
        if (jl || jr) { out.append(hay, i, at + 1); i = at + 1; continue }
        out.append(hay, i, at).append(with); i = end
    }
    out.append(hay, i, hay.length)
    return out.toString()
}

private fun isWordChar(c: Char) = c.isAsciiAlnum() || c.code >= 0x80

private fun stripWords(text: String, words: List<String>): String {
    val drop = words.toSet(); val out = StringBuilder(text.length)
    var prev: Char? = null; var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isAsciiAlpha()) {
            val st = i; i++
            while (i < text.length && (text[i].isAsciiAlpha() || text[i] == '\'')) i++
            val word = text.substring(st, i); val lw = word.asciiLower()
            val next = text.getOrNull(i)
            val glued = (prev != null && isNameGlue(prev)) || (next != null && isNameGlue(next))
            if (glued || lw in KEEP_WORDS || lw !in drop) out.append(word)
            prev = word.last()
        } else { out.append(c); prev = c; i++ }
    }
    return out.toString()
}

private fun isNameGlue(c: Char) = c == '.' || c == '-' || c == '_' || c == '/' || c == '\\' ||
    c in '0'..'9' || (c.code >= 0x80 && c.isLetterOrDigit())

private fun cleanupWsSegment(s: String, preserveStart: Boolean, preserveEnd: Boolean): String {
    val edge = '\uE000'
    val padded = buildString { if (preserveStart) append(edge); append(s); if (preserveEnd) append(edge) }
    var cleaned = cleanupWs(padded)
    if (preserveStart) cleaned = cleaned.substring(1)
    if (preserveEnd) cleaned = cleaned.dropLast(1)
    return cleaned
}

/** Rust `str::lines`: split on \n, strip a trailing \r, no final empty line. */
internal fun rustLines(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val parts = s.split('\n').toMutableList()
    if (s.endsWith('\n')) parts.removeAt(parts.size - 1)
    return parts.map { it.removeSuffix("\r") }
}

private fun cleanupWs(s: String): String {
    val out = StringBuilder(s.length); var start = true
    for (line in rustLines(s)) {
        if (!start) out.append('\n'); start = false
        var prevSpace = true
        for (c in line) {
            if (c.isWhitespace()) { if (!prevSpace) { out.append(' '); prevSpace = true } }
            else { out.append(c); prevSpace = false }
        }
        while (out.endsWith(' ')) out.setLength(out.length - 1)
    }
    if (s.endsWith('\n')) out.append('\n')
    return out.toString().trimStart()
}
