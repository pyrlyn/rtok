// Port of src/plugins/cmd/grep.rs — group `grep -n`/`rg -n`/`git grep` hits by path, cut long lines
// around the match, and keep every path (past the budget as line-number anchors).
package rtok.plugins.cmd

import rtok.cmdStem
import rtok.sys.Utf8Order
import rtok.sys.rustLines

object Grep {
    private const val PER_FILE = 12
    private const val BUDGET = 16
    private const val ANCHORS = 12
    private const val WINDOW = 60

    /** Flags that change the output shape away from `path:line:text`. */
    private val SHAPE_FLAGS =
        setOf(
            "-c",
            "-l",
            "-L",
            "-q",
            "-o",
            "-A",
            "-B",
            "-C",
            "-f",
            "-z",
            "-Z",
            "--count",
            "--files-with-matches",
            "--files-without-match",
            "--only-matching",
            "--context",
            "--after-context",
            "--before-context",
            "--null",
            "--heading",
            "--json",
            "--files",
            "--no-filename",
            "-h",
            "--file",
        )
    private const val VALUE_SHORT = "mgtTdDM"
    private val VALUE_LONG =
        setOf(
            "type",
            "type-not",
            "glob",
            "iglob",
            "max-count",
            "include",
            "exclude",
            "exclude-dir",
            "max-depth",
            "threads",
            "ignore-file",
            "replace",
            "sort",
            "sortr",
        )

    private class Hit(
        val num: String?,
        val text: String,
    )

    fun group(
        argv: List<String>,
        output: String,
    ): String? {
        val (flags, pattern) = parseArgv(argv) ?: return null
        val numbered = 'n' in flags
        val re = pattern?.let { buildRegex(flags, it) }
        val files = HashMap<String, MutableList<Hit>>()
        val other = ArrayList<String>()
        for (line in output.rustLines()) {
            if (line.isEmpty()) continue
            if (line.startsWith("Binary file ") || line.startsWith("grep: ") || line.startsWith("rg: ")) {
                other += line
                continue
            }
            val c = line.indexOf(':').takeIf { it >= 0 } ?: return null
            val path = line.substring(0, c)
            val rest = line.substring(c + 1)
            if (path.isEmpty() || path.any { it.isWhitespace() }) return null
            val hit =
                if (numbered) {
                    val d = rest.indexOf(':').takeIf { it >= 0 } ?: return null
                    val n = rest.substring(0, d)
                    if (n.isEmpty() || !n.all { it in '0'..'9' }) return null
                    Hit(n, rest.substring(d + 1))
                } else {
                    Hit(null, rest)
                }
            files.getOrPut(path) { ArrayList() } += hit
        }
        if (files.isEmpty()) return null
        val out = ArrayList<String>()
        var used = 0
        for (path in files.keys.sortedWith(Utf8Order)) {
            val hits = files.getValue(path)
            if (used >= BUDGET) {
                out += anchors(path, hits)
                continue
            }
            val before = out.size
            val shown = hits.take(PER_FILE)
            val indent = commonIndent(shown)
            val body =
                shown.map { h ->
                    val text = window(h.text.substring(minOf(indent, h.text.length)), re)
                    if (h.num != null) "${h.num}:$text" else text
                }
            val more = hits.size - shown.size
            if (hits.size == 1) {
                out += "$path:${body[0]}"
            } else {
                out += path
                out += body
                if (more > 0) out += "… +$more more"
            }
            used += out.size - before
        }
        out += other
        val joined = out.joinToString("\n")
        return joined.takeIf { it.byteLen < output.trimEnd().byteLen }
    }

    private fun anchors(
        path: String,
        hits: List<Hit>,
    ): String {
        val nums = hits.mapNotNull { it.num }
        if (nums.isEmpty()) return "$path ×${hits.size}"
        var list = nums.take(ANCHORS).joinToString(",")
        if (nums.size > ANCHORS) list += ",…+${nums.size - ANCHORS}"
        return "$path:$list"
    }

    private fun parseArgv(argv: List<String>): Pair<String, String?>? {
        val args = argv.iterator()
        if (!args.hasNext()) return null
        if (cmdStem(args.next()) == "git") {
            if (!args.hasNext()) return null
            args.next()
        } // `grep`
        val flags = StringBuilder()
        val patterns = ArrayList<String>()
        var positional: String? = null
        while (args.hasNext()) {
            val a = args.next()
            if (a in SHAPE_FLAGS) return null
            if (a.startsWith("--")) {
                when (val long = a.substring(2)) {
                    "line-number" -> flags.append('n')
                    "ignore-case" -> flags.append('i')
                    "fixed-strings" -> flags.append('F')
                    "extended-regexp" -> flags.append('E')
                    "perl-regexp" -> flags.append('P')
                    in VALUE_LONG -> if (args.hasNext()) args.next()
                    "regexp" -> patterns += if (args.hasNext()) args.next() else return null
                    else -> if (long.startsWith("regexp=")) patterns += long.substring("regexp=".length)
                }
                continue
            }
            if (a.length > 1 && a[0] == '-') {
                val short = a.substring(1)
                var i = 0
                while (i < short.length) {
                    val c = short[i++]
                    if ("-$c" in SHAPE_FLAGS) return null
                    if (c == 'e') {
                        val rest = short.substring(i)
                        patterns += rest.ifEmpty { if (args.hasNext()) args.next() else return null }
                        break
                    }
                    if (c in VALUE_SHORT) {
                        if (i >= short.length && args.hasNext()) args.next()
                        break
                    }
                    flags.append(c)
                }
                continue
            }
            if (positional == null) positional = a
        }
        // `rg` numbers lines only on request when stdout is not a terminal, like grep.
        return flags.toString() to (if (patterns.isEmpty()) positional else patterns.joinToString("|"))
    }

    private fun buildRegex(
        flags: String,
        pattern: String,
    ): Regex? {
        var p =
            if ('F' in flags) {
                Regex.escape(pattern)
            } else {
                if ('\\' in pattern && 'E' !in flags && 'P' !in flags) return null
                pattern
            }
        if ('i' in flags) p = "(?i)$p"
        return runCatching { Regex(p) }.getOrNull()
    }

    private fun commonIndent(hits: List<Hit>): Int = hits.filter { it.text.isNotBlank() }.minOfOrNull { h -> h.text.takeWhile { it == ' ' || it == '\t' }.length } ?: 0

    /** [text] cut to [WINDOW] scalars either side of the first match of [re]. */
    private fun window(
        text: String,
        re: Regex?,
    ): String {
        val m = re?.find(text) ?: return text
        val start = stepBack(text, m.range.first, WINDOW)
        val end = stepForward(text, m.range.last + 1, WINDOW)
        if (start == 0 && end == text.length) return text
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }

    private fun stepBack(
        s: String,
        from: Int,
        n: Int,
    ): Int {
        var i = from
        var k = 0
        while (i > 0 && k < n) {
            i--
            if (i > 0 && s[i].isLowSurrogate() && s[i - 1].isHighSurrogate()) i--
            k++
        }
        return if (k < n) 0 else i
    }

    private fun stepForward(
        s: String,
        from: Int,
        n: Int,
    ): Int {
        var i = from
        var k = 0
        while (i < s.length && k < n) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            k++
        }
        return i
    }
}
