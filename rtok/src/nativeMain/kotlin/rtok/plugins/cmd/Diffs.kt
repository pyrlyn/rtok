// Port of src/plugins/cmd/diffs.rs — tiered unified-diff compaction: small diffs keep every change
// with one line of context, big ones list every file with counts and detail only the smallest.
package rtok.plugins.cmd

import rtok.sys.rustLines
import rtok.sys.scalarList

object Diffs {
    internal const val SMALL = 120
    private const val DETAIL_FILES = 3
    private const val DETAIL_BYTES = 2500
    private const val HEAD_CONTEXT = 60

    internal class Hunk(
        val head: String,
        val body: List<String>,
    )

    internal class File(
        val header: MutableList<String>,
        val hunks: MutableList<Hunk> = ArrayList(),
    )

    internal class Parsed(
        val preamble: List<String>,
        val files: List<File>,
    )

    private val COUNTS = Regex("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@")

    private fun counts(head: String): Pair<Int, Int>? {
        val m = COUNTS.find(head) ?: return null

        fun n(i: Int): Int? =
            m.groups[i]
                ?.value
                ?.toIntOrNull()
                .let { if (m.groups[i] == null) 1 else it }
        return (n(1) ?: return null) to (n(2) ?: return null)
    }

    internal fun parse(output: String): Parsed? {
        val lines = output.rustLines()
        var at = lines.indexOfFirst { it.startsWith("diff --git ") }.takeIf { it >= 0 } ?: return null
        val preamble = lines.subList(0, at)
        val files = ArrayList<File>()
        while (at < lines.size) {
            if (!lines[at].startsWith("diff --git ")) return null
            val file = File(mutableListOf(lines[at]))
            at++
            while (at < lines.size && !lines[at].startsWith("@@ ") && !lines[at].startsWith("diff --git ")) file.header += lines[at++]
            while (at < lines.size && lines[at].startsWith("@@ ")) {
                val head = lines[at]
                var (old, new) = counts(head) ?: return null
                at++
                val body = ArrayList<String>()
                while (old > 0 || new > 0) {
                    val line = lines.getOrNull(at) ?: return null
                    when {
                        line.isEmpty() || line[0] == ' ' -> {
                            old--
                            new--
                        }

                        line[0] == '-' -> {
                            old--
                        }

                        line[0] == '+' -> {
                            new--
                        }

                        else -> {
                            return null
                        }
                    }
                    if (old < 0 || new < 0) return null
                    body += line
                    at++
                }
                while (lines.getOrNull(at)?.startsWith('\\') == true) body += lines[at++]
                file.hunks += Hunk(head, body)
            }
            files += file
        }
        return Parsed(preamble, files)
    }

    private fun changed(
        h: Hunk,
        sign: Char,
    ) = h.body.count { it.firstOrNull() == sign }

    private fun File.changed(sign: Char) = hunks.sumOf { changed(it, sign) }

    private fun pathOf(file: File): String {
        val first = file.header[0].removePrefixRepeated("diff --git ")
        val split = first.lastIndexOf(" b/").takeIf { it >= 0 } ?: return first
        val old = first.substring(0, split).removePrefixRepeated("a/")
        val new = first.substring(split + 3)
        return if (old == new) new else "$old -> $new"
    }

    private fun noteOf(file: File): String {
        fun has(p: String) = file.header.any { it.startsWith(p) }
        val notes =
            buildList {
                if (has("new file mode")) add("new")
                if (has("deleted file mode")) add("deleted")
                if (has("Binary files") || has("GIT binary patch")) add("binary")
                if (has("old mode")) add("mode")
            }
        return if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})"
    }

    private fun headerLines(file: File): List<String> {
        val named = file.header.any { it.startsWith("+++ ") }
        val out = ArrayList<String>()
        for ((i, l) in file.header.withIndex()) {
            if (l.startsWith("index ") || (i == 0 && named)) continue
            if (l.startsWith("--- a/") && file.header
                    .getOrNull(i + 1)
                    ?.takeIf { it.startsWith("+++ b/") }
                    ?.substring(6) == l.substring(6)
            ) {
                continue
            }
            out += l
        }
        return out
    }

    private fun fold(body: List<String>): List<String> {
        fun isCtx(l: String) = l.startsWith(' ') || l.isEmpty()
        val first = body.indexOfFirst { !isCtx(it) }
        val last = body.indexOfLast { !isCtx(it) }
        if (first < 0) return body
        val out = ArrayList<String>()
        var i = 0
        while (i < body.size) {
            if (!isCtx(body[i])) {
                out += body[i++]
                continue
            }
            var j = i
            while (j < body.size && isCtx(body[j])) j++
            val run = body.subList(i, j)
            val (head, tail) =
                when {
                    i < first -> 0 to 1
                    j > last -> 1 to 0
                    else -> 1 to 1
                }
            val hidden = maxOf(0, run.size - head - tail)
            if (hidden >= 2) {
                out += run.subList(0, head)
                out += " ⋯ $hidden unchanged"
                out += run.subList(run.size - tail, run.size)
            } else {
                out += run
            }
            i = j
        }
        return out
    }

    private fun head(h: Hunk): String {
        val at = h.head.indexOf("@@", 2).takeIf { it >= 0 } ?: return h.head
        val end = at + 2
        val range = h.head.substring(0, end)
        val context = h.head.substring(end)
        val chars = context.scalarList()
        return if (chars.size > HEAD_CONTEXT) range + chars.take(HEAD_CONTEXT).joinToString("") + "…" else h.head
    }

    private fun whole(file: File): List<String> = headerLines(file) + file.hunks.flatMap { listOf(head(it)) + fold(it.body) }

    private fun tierTwo(parsed: Parsed): List<String> {
        val sizes = parsed.files.map { f -> whole(f).sumOf { it.byteLen + 1 } }
        val order = parsed.files.indices.sortedBy { sizes[it] }
        val detail = BooleanArray(parsed.files.size)
        var used = 0
        for (i in order.take(DETAIL_FILES)) {
            if (parsed.files[i].hunks.isEmpty()) continue
            if (used + sizes[i] > DETAIL_BYTES) break
            used += sizes[i]
            detail[i] = true
        }
        val add = parsed.files.sumOf { it.changed('+') }
        val del = parsed.files.sumOf { it.changed('-') }
        val out = arrayListOf("${parsed.files.size} files, +$add -$del")
        for ((i, f) in parsed.files.withIndex()) {
            out += "${pathOf(f)} +${f.changed('+')} -${f.changed('-')}${noteOf(f)}"
            if (detail[i]) {
                out += whole(f).filter { !it.startsWith("+++ ") && !it.startsWith("--- ") }
            } else {
                out += f.hunks.map { "  ${head(it)}" }
            }
        }
        return out
    }

    fun tiered(output: String): String? {
        val parsed = parse(output) ?: return null
        val out = ArrayList<String>(parsed.preamble)
        if (output.rustLines().size <= SMALL) parsed.files.forEach { out += whole(it) } else out += tierTwo(parsed)
        return shorterOrNull(out.joinToString("\n"), output)
    }
}

/** Rust `trim_start_matches(p)`: strip every leading repeat of [p]. */
internal fun String.removePrefixRepeated(p: String): String {
    var r = this
    while (p.isNotEmpty() && r.startsWith(p)) r = r.substring(p.length)
    return r
}
