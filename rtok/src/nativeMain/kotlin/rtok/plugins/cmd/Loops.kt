// Port of src/plugins/cmd/loops.rs — collapse runs of same-shape lines a polling loop prints.
package rtok.plugins.cmd

import rtok.sys.rustLines

object Loops {
    /** Fewer lines than this are not worth a `×N` line. */
    private const val MIN_RUN = 3

    private val FIELD = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\\b[0-9a-f]{12,}\\b|\\d+(?:\\.\\d+)?")
    private val PROBLEM = Regex("(?i)error|fail|warn|panic|exception|denied|refused|timed? ?out|fatal|unreachable")
    /** What Rust `str::parse::<f64>` accepts among [FIELD] matches. */
    private val NUMBER = Regex("^\\d+(\\.\\d+)?([eE]\\d+)?$")

    /** A line cut at its number/id fields: the text between them and the fields themselves. */
    private class Cut(val parts: List<String>, val vals: List<String>) {
        fun sameShape(other: Cut) = parts == other.parts

        companion object {
            fun of(line: String): Cut {
                val parts = ArrayList<String>(); val vals = ArrayList<String>(); var at = 0
                for (m in FIELD.findAll(line)) { parts += line.substring(at, m.range.first); vals += m.value; at = m.range.last + 1 }
                parts += line.substring(at)
                return Cut(parts, vals)
            }
        }
    }

    /** [output] with runs of same-shape lines collapsed, or null when nothing collapsed. */
    fun collapse(output: String): String? {
        val lines = output.rustLines()
        val cuts = lines.map(Cut::of)
        val out = ArrayList<String>(); var changed = false; var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank() || PROBLEM.containsMatchIn(lines[i])) { out += lines[i]; i++; continue }
            // Blank lines (`echo` between polls) do not end a run, but only the ones inside it are dropped.
            val members = arrayListOf(i); var j = i + 1
            while (j < lines.size) {
                when {
                    lines[j].isBlank() -> j++
                    !PROBLEM.containsMatchIn(lines[j]) && cuts[j].sameShape(cuts[i]) -> { members += j; j++ }
                    else -> break
                }
            }
            if (members.size < MIN_RUN) { out += lines[i]; i++; continue }
            out += runLine(cuts, members); changed = true
            i = members.last() + 1
        }
        if (!changed) return null
        return shorterOrNull(out.joinToString("\n"), output)
    }

    /** `a..b`: smallest and largest when every value is a number, else first and last (ids carry no order). */
    private fun span(vals: List<String>): String {
        if (vals.all { NUMBER.matches(it) }) {
            val nums = vals.map { it.toDouble() }
            return "${vals[nums.indexOf(nums.min())]}..${vals[nums.indexOf(nums.max())]}"
        }
        return "${vals.first()}..${vals.last()}"
    }

    /** One line for the run: constant fields written back in, changing ones as `#` with their range after `×N`. */
    private fun runLine(cuts: List<Cut>, members: List<Int>): String {
        val first = cuts[members[0]]
        val line = StringBuilder(); val vals = ArrayList<String>()
        for ((k, part) in first.parts.withIndex()) {
            line.append(part)
            if (k >= first.vals.size) continue
            if (members.all { cuts[it].vals[k] == first.vals[k] }) line.append(first.vals[k])
            else { line.append('#'); vals += span(members.map { cuts[it].vals[k] }) }
        }
        return if (vals.isEmpty()) "$line ×${members.size}" else "$line ×${members.size} (vals: ${vals.joinToString(", ")})"
    }
}
