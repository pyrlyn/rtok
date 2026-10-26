// Port of src/plugins/read/density.rs: `mode=density:X` keeps the most informative lines (word
// self-information per byte) within X of the file's bytes, with line numbers and gap markers.
package rtok.plugins.read

import rtok.sys.rustLines
import kotlin.math.log2

/** `density:X` → `X` in (0, 1]; `null` for any other mode; failure for a bad share. */
fun parseDensity(mode: String): Result<Double>? {
    if (!mode.startsWith("density:")) return null
    val arg = mode.removePrefix("density:")
    val x = arg.toDoubleOrNull()
    return if (x != null && x > 0.0 && x <= 1.0) {
        Result.success(x)
    } else {
        Result.failure(IllegalArgumentException("density: expected a share in (0, 1], got `$arg`"))
    }
}

private fun words(line: String): List<String> = line.split { !(it.isLetterOrDigit() || it == '_') }

private inline fun String.split(crossinline sep: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>()
    var start = -1
    for (i in indices) {
        if (sep(this[i])) {
            if (start >= 0) {
                out += substring(start, i)
                start = -1
            }
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out += substring(start)
    return out
}

private fun byteLen(s: String) = s.encodeToByteArray().size

fun renderDensity(
    raw: String,
    share: Double,
): String {
    val lines = raw.rustLines()
    val total = lines.sumOf { byteLen(it) + 1 }
    val freq = HashMap<String, Int>()
    var seen = 0
    for (l in lines) {
        for (w in words(l)) {
            freq[w] = (freq[w] ?: 0) + 1
            seen++
        }
    }
    val scores = lines.map { l -> words(l).sumOf { w -> -log2(freq.getValue(w).toDouble() / seen) } / (byteLen(l) + 1) }
    val order = lines.indices.sortedWith { a, b -> scores[b].compareTo(scores[a]).takeIf { it != 0 } ?: a.compareTo(b) }
    val budget = (total * share).toLong()
    val keep = BooleanArray(lines.size)
    var used = 0L
    for (i in order) {
        val cost = byteLen(lines[i]) + 1
        if (used + cost > budget) continue
        if (scores[i] == 0.0) break
        keep[i] = true
        used += cost
    }
    val out = ArrayList<String>()
    var gap = 0
    for ((i, l) in lines.withIndex()) {
        if (keep[i]) {
            if (gap > 0) {
                out += "… $gap lines omitted"
                gap = 0
            }
            out += "${i + 1}:$l"
        } else {
            gap++
        }
    }
    if (gap > 0) out += "… $gap lines omitted"
    return out.joinToString("\n")
}
