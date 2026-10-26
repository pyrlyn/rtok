// Replacement for the `change-preview` crate's `Preview`/`Mode`: file edits as a unified diff or
// `git diff --stat` lines, removals as one line each, closed by totals lines.
package rtok.sys

/** How much of the preview to print. */
enum class PreviewMode {
    /** Every edit as a unified diff. */
    Diff,

    /** One `path | 7 +++--` line per edit instead of its diff. */
    Stat,
}

/** One file the command would rewrite. */
data class PreviewEdit(
    val path: String,
    val insertions: Int,
    val deletions: Int,
    internal val diff: String,
)

/** One path the command would delete, with everything under it. */
data class PreviewRemoval(
    val path: String,
    val bytes: Long,
    val files: Long,
)

/** The sums the closing lines print. */
data class PreviewTotals(
    val filesChanged: Int = 0,
    val insertions: Int = 0,
    val deletions: Int = 0,
    val pathsRemoved: Int = 0,
    val filesRemoved: Long = 0,
    val bytesRemoved: Long = 0,
)

/** Everything one run would change. */
class Preview {
    val edits = mutableListOf<PreviewEdit>()
    val removals = mutableListOf<PreviewRemoval>()

    /** Record a rewrite of [path]; an edit that changes nothing is dropped. */
    fun edit(
        path: String,
        before: String,
        after: String,
    ): Preview {
        if (before == after) return this
        val diff = ChangePreview.unifiedDiff(path, before, after)
        val (ins, del) = ChangePreview.counts(before, after)
        edits += PreviewEdit(path, ins, del, diff)
        return this
    }

    /** Record a deletion of [path], which holds [files] files taking [bytes] bytes. */
    fun remove(
        path: String,
        bytes: Long,
        files: Long,
    ): Preview {
        removals += PreviewRemoval(path, bytes, files)
        return this
    }

    fun isEmpty(): Boolean = edits.isEmpty() && removals.isEmpty()

    fun totals(): PreviewTotals =
        PreviewTotals(
            filesChanged = edits.size,
            insertions = edits.sumOf { it.insertions },
            deletions = edits.sumOf { it.deletions },
            pathsRemoved = removals.size,
            filesRemoved = removals.sumOf { it.files },
            bytesRemoved = removals.sumOf { it.bytes },
        )

    /** The preview as plain text. */
    fun render(mode: PreviewMode): String =
        buildString {
            when (mode) {
                PreviewMode.Diff -> edits.forEach { append(it.diff) }
                PreviewMode.Stat -> append(statLines())
            }
            append(removalLines())
            val t = totals()
            if (t.filesChanged > 0) append(editTotals(t)).append('\n')
            if (t.pathsRemoved > 0) append(removalTotals(t)).append('\n')
        }

    private fun statLines(): String {
        val width = edits.maxOfOrNull { it.path.length } ?: 0
        val count = edits.maxOfOrNull { (it.insertions + it.deletions).toString().length } ?: 0
        val most = edits.maxOfOrNull { it.insertions + it.deletions } ?: 0
        // A 2000-line rewrite must not print a 2000-character bar; past the cap every bar
        // shrinks by the same ratio, rounded up so a one-line change still shows a mark.
        val scale = { n: Int -> if (most <= BAR) n else (n * BAR + most - 1) / most }
        return edits.joinToString("") {
            " ${it.path.padEnd(width)} | ${(it.insertions + it.deletions).toString().padStart(count)} " +
                "+".repeat(scale(it.insertions)) + "-".repeat(scale(it.deletions)) + "\n"
        }
    }

    private fun removalLines(): String {
        val width = removals.maxOfOrNull { it.path.length } ?: 0
        val size = removals.maxOfOrNull { ChangePreview.humanBytes(it.bytes).length } ?: 0
        return removals.joinToString("") {
            "- ${it.path.padEnd(width)}  ${ChangePreview.humanBytes(it.bytes).padStart(size)}  ${plural(it.files, "file")}\n"
        }
    }

    private companion object {
        const val BAR = 40

        fun plural(
            n: Long,
            noun: String,
        ) = "${ChangePreview.group(n)} $noun${if (n == 1L) "" else "s"}"

        fun editTotals(t: PreviewTotals): String {
            val parts = mutableListOf("${plural(t.filesChanged.toLong(), "file")} changed")
            if (t.insertions > 0) parts += "${plural(t.insertions.toLong(), "insertion")}(+)"
            if (t.deletions > 0) parts += "${plural(t.deletions.toLong(), "deletion")}(-)"
            return parts.joinToString(", ")
        }

        fun removalTotals(t: PreviewTotals) = "${plural(t.pathsRemoved.toLong(), "path")}, ${plural(t.filesRemoved, "file")}, -${ChangePreview.humanBytes(t.bytesRemoved)}"
    }
}
