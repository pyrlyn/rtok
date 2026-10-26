// Port of src/plugins/graph/blast.rs (T377): the `impact` walk under a token budget. The flat
// `depth  path  scope` listing is kept while it fits; past the budget rows are grouped by file, nearest and
// most central first, and the cut says what was left.
package rtok.plugins.graph

import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Measurement

/** One reached definition: depth, path, scope (empty = file-level). */
data class WalkRow(
    val depth: Int,
    val path: String,
    val scope: String,
)

/** What the answer may cost; [overhead] is the text the caller adds around the rows. */
data class Budget(
    val tokens: UInt,
    val overhead: UInt = 0u,
    val all: Boolean = false,
    val mark: Boolean = false,
) {
    companion object {
        fun of(
            cx: Ctx,
            all: Boolean,
        ) = Budget(graphCfg(cx).impactTokens, all = all)
    }
}

object Blast {
    /** Lines of one file shown past the header. */
    const val LINES_PER_FILE = 3

    private class Group(
        val path: String,
        var depth: Int,
        val rows: MutableList<WalkRow> = ArrayList(),
    ) {
        fun render(): String =
            buildString {
                val n = rows.size
                append("$path ($n ref${if (n == 1) "" else "s"}, depth $depth)\n")
                for (r in rows.take(LINES_PER_FILE)) append("  ${r.depth}  ${r.scope.ifEmpty { "(file)" }}\n")
                if (n > LINES_PER_FILE) append("  +${n - LINES_PER_FILE} more\n")
            }
    }

    /** One `depth  path  scope` line per row, `(file)` when the row is file-level (mod.rs `impact_lines_text`). */
    fun linesText(rows: List<WalkRow>): String =
        buildString {
            for (r in rows) append("${r.depth}  ${r.path}  ${r.scope.ifEmpty { "(file)" }}\n")
        }

    fun render(
        cx: Ctx,
        rows: List<WalkRow>,
        name: String,
        b: Budget,
        rank: () -> Map<String, Float>,
    ): String {
        val flat = linesText(rows)

        fun cost(s: String) = cx.estimate(if (b.mark) Tools.markAmbiguousLines(s) else s, Class.Code)
        val budget = (if (b.tokens > b.overhead) b.tokens - b.overhead else 0u).coerceAtLeast(1u)
        if (b.all || b.tokens == 0u || cost(flat) <= budget) return flat
        val byPath = LinkedHashMap<String, Group>()
        for (r in rows) {
            val g = byPath.getOrPut(r.path) { Group(r.path, r.depth) }
            g.depth = minOf(g.depth, r.depth)
            g.rows += r
        }
        val ranks = rank()

        fun rankOf(g: Group) = ranks[g.path] ?: 0f
        val groups =
            byPath.values.sortedWith(
                compareBy<Group> { it.depth }.thenByDescending { rankOf(it) }.thenByDescending { it.rows.size }.thenBy { it.path },
            )

        fun cut(
            files: Int,
            refs: Int,
        ) = "+$files files, $refs refs not shown — impact $name --all\n"
        val reserve = cost(cut(groups.size, rows.size))
        var left = if (budget > reserve) budget - reserve else 0u
        val out = StringBuilder()
        var shown = 0
        for ((i, g) in groups.withIndex()) {
            val block = g.render()
            val tokens = cost(block)
            if (i > 0 && tokens > left) break
            left = if (left > tokens) left - tokens else 0u
            out.append(block)
            shown = i + 1
        }
        val rest = groups.drop(shown)
        if (rest.isNotEmpty()) out.append(cut(rest.size, rest.sumOf { it.rows.size }))
        val text = out.toString()
        val fb = flat.encodeToByteArray().size
        val ob = text.encodeToByteArray().size
        if (ob < fb) cx.record(Measurement("graph", "impact", fb.toULong(), ob.toULong(), cx.estimate(flat, Class.Code), cx.estimate(text, Class.Code), null, cx.callId()))
        return text
    }
}
