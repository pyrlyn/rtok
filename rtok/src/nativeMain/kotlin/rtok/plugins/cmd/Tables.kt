// Port of the table formatters of src/plugins/cmd/formatters.rs: docker/podman ps, kubectl get,
// ps aux, df and sqlite3 tables.
package rtok.plugins.cmd

import rtok.sys.rustLines

object Tables {
    private fun words(s: String) = s.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }

    private fun String.upper() = buildString { for (c in this@upper) append(if (c in 'a'..'z') c - 32 else c) }

    fun dockerPs(output: String): String? {
        val lines = output.rustLines().filter { it.isNotEmpty() }
        val u = (lines.firstOrNull() ?: return null).upper()
        if (!u.contains("CONTAINER ID") && !(u.contains("IMAGE") && u.contains("NAMES"))) return null
        return lines
            .drop(1)
            .map(::compactDockerRow)
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    private fun compactDockerRow(line: String) = shortenAgo(dropRegistryHost(dropArrowPort(words(line).joinToString(" ").replace("0.0.0.0:", ""))))

    /** `8080->80/tcp` → `8080`. */
    private fun dropArrowPort(s: String) =
        buildString {
            var i = 0
            while (i < s.length) {
                if (s[i] == '-' && s.getOrNull(i + 1) == '>') {
                    i += 2
                    while (i < s.length && s[i] in '0'..'9') i++
                    if (s.getOrNull(i) == '/') {
                        i++
                        while (i < s.length && (s[i] in 'a'..'z' || s[i] in 'A'..'Z')) i++
                    }
                    continue
                }
                append(s[i++])
            }
        }

    private fun dropRegistryHost(s: String) =
        words(s).joinToString(" ") { tok ->
            val slash = tok.indexOf('/')
            if (slash >= 0 && tok.substring(0, slash).let { it.contains('.') && !it.contains(':') }) tok.substring(slash + 1) else tok
        }

    /** Only `<number> <unit>` shortens; docker's prose forms stay readable words. */
    internal fun shortenAgo(s: String): String {
        fun unit(w: String) =
            when (w) {
                "day", "days" -> 'd'
                "hour", "hours" -> 'h'
                "minute", "minutes" -> 'm'
                "second", "seconds" -> 's'
                "week", "weeks" -> 'w'
                else -> null
            }
        val words = s.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val u = words.getOrNull(i + 1)?.let(::unit)
            if (u != null && w.isNotEmpty() && w.all { it in '0'..'9' }) {
                out += "$w$u"
                i += 2
            } else {
                out += w
                i++
            }
        }
        return out.joinToString(" ")
    }

    private val KUBE_KEEP = setOf("NAME", "READY", "STATUS", "IP")

    fun kubectlGet(output: String): String? {
        val lines = output.rustLines().filter { it.isNotEmpty() }
        val header = lines.firstOrNull() ?: return null
        val cols = words(header)
        if (cols.firstOrNull() != "NAME" || cols.none { it == "STATUS" || it == "READY" || it == "AGE" }) return null
        val idx = cols.indices.filter { cols[it] in KUBE_KEEP }
        if (idx.isEmpty()) return null
        // A cell can hold spaces (RESTARTS `1 (3m ago)` since 1.22): a cell is the text under its header.
        val starts = header.indices.filter { i -> header[i] != ' ' && (i == 0 || header[i - 1] == ' ') }

        fun cell(
            line: String,
            col: Int,
        ): String? {
            val from = starts.getOrNull(col) ?: return null
            if (from > line.length) return null
            val to = starts.getOrNull(col + 1) ?: Int.MAX_VALUE
            return line.substring(from, minOf(to, line.length)).trim().ifEmpty { null }
        }
        val out = arrayListOf(idx.joinToString(" ") { cols[it] })
        for (line in lines.drop(1)) {
            val fields = words(line)
            if (fields.size < 2) continue
            // A row that does not line up with the header falls back to token order.
            val aligned = starts.size == cols.size && starts.drop(1).all { at -> at <= line.length && line.substring(0, at).endsWith(' ') }
            val picked = if (aligned) idx.mapNotNull { cell(line, it) } else idx.mapNotNull { fields.getOrNull(it) }
            if (picked.isNotEmpty()) out += picked.joinToString(" ")
        }
        return if (out.size < 2) null else out.joinToString("\n")
    }

    fun psAux(output: String): String? {
        val lines = output.rustLines().filter { it.isNotEmpty() }
        val header = lines.firstOrNull() ?: return null
        val u = header.upper()
        if (!u.contains("PID") || !(u.contains("CMD") || u.contains("COMMAND"))) return null
        // BSD `ps aux` prints `USER PID …`; the macOS shape in the goldens prints `PID …` first.
        val pidAt = words(header).indexOfFirst { it.equals("PID", ignoreCase = true) }.coerceAtLeast(0)
        val rows = ArrayList<String>()
        for (line in lines.drop(1)) {
            val fields = words(line)
            // One bad line (a repeated header, a wrapped row) skips itself.
            val pid = fields.getOrNull(pidAt)?.takeIf { p -> p.all { it in '0'..'9' } } ?: continue
            val cmd = fields.firstOrNull { it.startsWith('/') || it.startsWith("./") } ?: fields.lastOrNull() ?: ""
            val base = cmd.substringAfterLast('/')
            val tail = fields.lastOrNull() ?: ""
            rows += if (tail != base && tail != pid) "$pid $base $tail" else "$pid $base"
        }
        return rows.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun df(output: String): String? {
        val lines = output.rustLines().filter { it.isNotBlank() }
        val u = (lines.firstOrNull() ?: return null).upper()
        if (!u.contains("FILESYSTEM") || !(u.contains("MOUNTED") || u.contains("CAPACITY") || u.contains("USE%"))) return null
        val rows = lines.drop(1).mapNotNull(::compactDfRow)
        // Past the default cap, head/tail of the raw table is smaller than one short row per mount.
        return if (rows.isEmpty() || rows.size > 40) null else rows.joinToString("\n")
    }

    private fun compactDfRow(line: String): String? {
        val t = words(line)
        val p = t.indexOfFirst { it.endsWith('%') }.takeIf { it >= 3 } ?: return null
        val fs = t.subList(0, p - 3).joinToString(" ")
        var i = p + 1
        while (i < t.size && !t[i].contains('/') && dfInodeToken(t[i])) i++
        if (fs.isEmpty() || i >= t.size) return null
        return "$fs ${t[p - 2]}/${t[p - 3]} ${t[p]} ${t.subList(i, t.size).joinToString(" ")}"
    }

    private fun dfInodeToken(tok: String): Boolean {
        if (tok == "-" || tok.endsWith('%')) return true
        val core = tok.trimEnd { it in "kKmMgGtTbBiI" }
        return core.isNotEmpty() && core.all { it in '0'..'9' || it == '.' }
    }

    fun sqlite3(output: String): String? {
        val lines = output.rustLines()
        if (lines.none(::sqliteRuleLine)) return null
        // Collapse padding: dropping only the rule line can still be larger than `Rule()`.
        val kept = lines.filter { !sqliteRuleLine(it) }.map { words(it).joinToString(" ") }.filter { it.isNotEmpty() }
        return if (kept.isEmpty() || kept.size > 40) null else kept.joinToString("\n")
    }

    private fun sqliteRuleLine(line: String): Boolean {
        val t = line.trim()
        return t.byteLen >= 3 && t.all { it in "-= +|" } && t.any { it == '-' || it == '=' }
    }
}
