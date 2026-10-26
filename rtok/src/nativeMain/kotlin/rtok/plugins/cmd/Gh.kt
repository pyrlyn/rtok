// Port of src/plugins/cmd/gh.rs — `gh pr|issue view`, `gh pr checks`, `gh run view --log` formatters.
package rtok.plugins.cmd

import rtok.sys.rustLines

private const val BEFORE = 2
private const val AFTER = 4

object Gh {
    fun format(argv: List<String>, output: String): String? {
        val got = when (argv.getOrNull(1) to (argv.getOrNull(2) ?: return null)) {
            "pr" to "view", "issue" to "view" -> view(output)
            "pr" to "checks" -> checks(output)
            "run" to "view" -> runLog(output)
            else -> null
        } ?: return null
        return got.takeIf { it.encodeToByteArray().size < output.encodeToByteArray().size }
    }

    private val FIELD = Regex("^[a-z][a-z-]*:\t")

    internal fun view(output: String): String? {
        val lines = output.rustLines()
        if (lines.firstOrNull()?.let { FIELD.containsMatchIn(it) } != true) return null
        val out = ArrayList<String>(); var inBody = false; var blank = false
        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                inBody -> {
                    if (line.isEmpty()) { blank = true; continue }
                    if (blank && out.isNotEmpty()) out += ""
                    blank = false; out += line
                }
                line == "--" -> { inBody = true; out += line }
                FIELD.containsMatchIn(raw) && raw.indexOf('\t').let { it >= 0 && raw.substring(it + 1).isBlank() } -> {}
                else -> out += line
            }
        }
        return out.joinToString("\n")
    }

    private val STATES = listOf("pass", "fail", "pending", "skipping", "cancel")

    internal fun checks(output: String): String? {
        val rows = output.rustLines().map { l -> (l.split('\t').getOrNull(1) ?: "") to l }
        if (rows.isEmpty() || !rows.all { it.first in STATES }) return null
        val parts = STATES.mapNotNull { s -> rows.count { it.first == s }.takeIf { it > 0 }?.let { "$it $s" } }
        val out = arrayListOf("${rows.size} checks: ${parts.joinToString(", ")}")
        out += rows.filter { it.first != "pass" && it.first != "skipping" }.map { it.second }
        return out.joinToString("\n")
    }

    private val PROBLEM = Regex("(?i)##\\[(error|warning)\\]|\\berror\\b|\\bfailed\\b|\\bfailure\\b|panicked|\\bFAIL\\b|warning:")
    private val TS = Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z ?")

    internal fun runLog(output: String): String? {
        val steps = ArrayList<kotlin.Pair<String, MutableList<String>>>()
        for (line in output.rustLines()) {
            val cols = line.split('\t', limit = 3)
            if (cols.size < 3) return null
            val head = "${cols[0]} / ${cols[1]}"
            val text = TS.replaceFirst(cols[2], "")
            val last = steps.lastOrNull()
            if (last != null && last.first == head) last.second += text else steps += head to mutableListOf(text)
        }
        if (steps.isEmpty()) return null
        val out = ArrayList<String>(); var quiet = 0; var quietLines = 0
        for ((head, lines) in steps) {
            val hits = lines.indices.filter { PROBLEM.containsMatchIn(lines[it]) }
            if (hits.isEmpty()) { quiet++; quietLines += lines.size; continue }
            val keep = BooleanArray(lines.size)
            for (i in hits) for (k in maxOf(0, i - BEFORE) until minOf(i + AFTER + 1, lines.size)) keep[k] = true
            out += "## $head"
            var gap = false
            for ((i, line) in lines.withIndex()) {
                if (keep[i]) { if (gap) { out += "  …"; gap = false }; out += line } else gap = true
            }
        }
        if (quiet > 0) out += "$quiet steps without errors ($quietLines lines)"
        return out.joinToString("\n")
    }
}
