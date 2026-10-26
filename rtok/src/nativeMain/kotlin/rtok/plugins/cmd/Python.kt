// Port of src/plugins/cmd/python.rs — pytest / unittest summaries and traceback frame folding.
package rtok.plugins.cmd

import rtok.sys.rustLines

object Python {
    private val PROGRESS = Regex("^\\S*\\s*[.FEsxXpP]+\\s*(\\[\\s*\\d+%\\])?$")
    private val TOTALS = Regex("^\\d+ (passed|failed|error|errors|skipped|xfailed|xpassed|warnings?|deselected)\\b.* in [\\d.]+s")
    private val RAN = Regex("(?m)^Ran \\d+ tests? in [\\d.]+s$")

    /** The text inside a `=== name ===` banner. */
    private fun banner(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("==") || !t.endsWith("==") || t.length < 4) return null
        return t.substring(2, t.length - 2).trim('=').trim()
    }

    private fun progress(line: String) = PROGRESS.containsMatchIn(line.trimEnd())

    private fun totals(line: String) = TOTALS.containsMatchIn(line.trim())

    private enum class Section { Header, Kept }

    fun pytest(output: String): String? {
        val lines = output.rustLines()
        val hasBanner = lines.any { banner(it) != null }
        val hasTotals = lines.any { l -> totals(l) || banner(l)?.let(::totals) == true }
        if (!hasTotals || !(hasBanner || lines.any(::progress))) return null
        // A `FAILED id` line already names every failing test; the dots line would repeat it.
        val named = lines.any { it.startsWith("FAILED ") || it.startsWith("ERROR ") }
        var section = Section.Header
        val out = ArrayList<String>()
        for (line in lines) {
            val name = banner(line)
            if (name != null) {
                section = if (name.contains("test session starts")) Section.Header else Section.Kept
                // Only the totals frame is worth its tokens; the others label what follows.
                if (totals(name)) out += line
                continue
            }
            when {
                totals(line) -> out += line
                section == Section.Kept -> out += line
                progress(line) -> if (!named && (line.contains('F') || line.contains('E'))) out += line
                listOf("ERROR", "WARN", "Error", "error").any { line.contains(it) } -> out += line
            }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun unittest(output: String): String? {
        if (!RAN.containsMatchIn(output)) return null
        return output.rustLines().filter { l -> !(l.isNotEmpty() && l.all { it == '.' || it == 's' || it == 'x' }) }.joinToString("\n")
    }

    fun script(output: String): String? {
        unittest(output)?.let { return collapseFrames(it) }
        return collapseFrames(output).takeIf { it != output }
    }

    /** Fold runs of identical traceback frames into one plus a repeat count. */
    fun collapseFrames(output: String): String {
        val lines = output.rustLines()
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            out += line
            i++
            if (!line.startsWith("Traceback (most recent call last):")) continue
            val start = i
            while (i < lines.size && lines[i].startsWith(' ')) i++
            out += fold(lines.subList(start, i))
        }
        return out.joinToString("\n") + if (output.endsWith('\n')) "\n" else ""
    }

    private fun fold(frames: List<String>): List<String> {
        val groups = ArrayList<MutableList<String>>()
        for (l in frames) if (l.startsWith("  File ") || groups.isEmpty()) groups += mutableListOf(l) else groups.last() += l
        val out = ArrayList<String>()
        var k = 0
        while (k < groups.size) {
            var run = 1
            while (k + run < groups.size && groups[k + run] == groups[k]) run++
            out += groups[k]
            if (run > 1) out += "  [previous frame repeated ${run - 1} more times]"
            k += run
        }
        return out
    }
}
