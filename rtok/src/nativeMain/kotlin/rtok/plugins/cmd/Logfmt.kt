// Port of src/plugins/cmd/logfmt.rs — summarise and factor runs of `key=value` (logfmt) lines.
package rtok.plugins.cmd

private const val MIN_RUN = 8
private const val MAX_KEYS = 12
private const val MAX_VALUE = 24
private const val MIN_PAIRS = 3

private class Pair(val key: String, val value: String, val start: Int, val end: Int)

private fun ws(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

private fun pairs(line: String): List<Pair> {
    val out = ArrayList<Pair>(); var i = 0; val n = line.length
    while (i < n) {
        while (i < n && ws(line[i])) i++
        val start = i
        while (i < n && !ws(line[i]) && line[i] != '=') i++
        val key = line.substring(start, i)
        if (i < n && line[i] == '=' && isKey(key)) {
            val v0 = i + 1; i = v0
            if (i < n && line[i] == '"') {
                i++
                while (i < n && line[i] != '"') i += if (line[i] == '\\') 2 else 1
                i++
            } else while (i < n && !ws(line[i])) i++
            val end = minOf(i, n)
            out += Pair(key, line.substring(v0, end), start, end)
        } else while (i < n && !ws(line[i])) i++
    }
    return out
}

internal fun fields(line: String): List<kotlin.Pair<String, String>> = pairs(line).map { it.key to it.value }

private fun isKey(k: String) = k.isNotEmpty() && (k[0].isAsciiLetter() || k[0] == '_') &&
    k.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' || it == '.' || it == '-' }

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private class Col(val key: String, val first: String, var last: String, var constant: Boolean)

/** One line describing a run of logfmt lines: constant fields once, varying keys with their range. */
fun logfmtSummary(run: List<String>): String? {
    if (run.size < MIN_RUN) return null
    val cols = fields(run[0]).map { (k, v) -> Col(k, v, v, true) }.toMutableList()
    for (line in run.drop(1)) {
        val f = fields(line)
        cols.retainAll { c ->
            val hit = f.firstOrNull { it.first == c.key } ?: return@retainAll false
            c.constant = c.constant && hit.second == c.first; c.last = hit.second; true
        }
        if (cols.isEmpty()) return null
    }
    val kept = cols.take(MAX_KEYS)
    val constant = kept.filter { it.constant }.map { "${it.key}=${it.first}" }
    val varying = kept.filter { !it.constant }.map {
        if (it.first == it.last || maxOf(it.first.encodeToByteArray().size, it.last.encodeToByteArray().size) > MAX_VALUE) it.key
        else "${it.key}=${it.first}..${it.last}"
    }
    val parts = ArrayList<String>()
    if (constant.isNotEmpty()) parts += "fields: ${constant.joinToString(" ")}"
    if (varying.isNotEmpty()) parts += "varying: ${varying.joinToString(" ")}"
    return parts.joinToString("; ")
}

/** Strips `key=value` pairs every record shares (anchors kept whole) and returns the `[every record: …]` header. */
fun logfmtFactor(lines: MutableList<String>, anchor: (String) -> Boolean): String? {
    val rec = lines.indices.filter { !anchor(lines[it]) && pairs(lines[it]).size >= MIN_PAIRS }
    val plain = lines.count { !anchor(it) }
    if (rec.size < MIN_RUN || rec.size * 4 < plain * 3) return null
    var common = fields(lines[rec[0]])
    for (i in rec.drop(1)) {
        val f = fields(lines[i])
        common = common.filter { (k, v) -> f.any { it.first == k && it.second == v } }
        if (common.isEmpty()) return null
    }
    common = common.take(MAX_KEYS)
    for (i in rec) {
        val line = lines[i]; val out = StringBuilder(line)
        for (p in pairs(line).asReversed()) {
            if (common.any { it.first == p.key && it.second == p.value }) {
                var end = p.end
                while (end < out.length && ws(out[end])) end++
                out.deleteRange(p.start, end)
            }
        }
        val s = out.toString().trimEnd(::ws)
        if (s.isNotEmpty()) lines[i] = s
    }
    return "[every record: ${common.joinToString(" ") { "${it.first}=${it.second}" }}]"
}
