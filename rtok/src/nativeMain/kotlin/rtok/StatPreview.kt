// `--stat` for the dry runs that show a diff (stat_preview.rs): the per-file stat lines and the
// totals line instead of the diff, and the same totals line closing the default diff.
package rtok

import rtok.sys.Preview
import rtok.sys.PreviewMode
import kotlin.concurrent.AtomicInt

object StatPreview {
    // A process-wide switch rather than a parameter, as in Rust: the flag is read deep inside
    // renderers that have no CLI context.
    private val stat = AtomicInt(0)

    fun set(on: Boolean) {
        stat.value = if (on) 1 else 0
    }

    fun on(): Boolean = stat.value != 0

    /** Text with the colour escapes removed, so a painted diff can be counted. */
    internal fun plain(text: String): String =
        buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (c == '\u001b') {
                    while (i < text.length && !text[i++].isAsciiLetter()) Unit
                } else {
                    append(c)
                }
            }
        }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** Rebuild a before/after pair from `+`/`-`/` ` lines; headers and unmarked lines are skipped. */
    internal fun sides(text: String): Pair<String, String> {
        val before = StringBuilder()
        val after = StringBuilder()
        for (line in plain(text).lines()) {
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@")) continue
            val rest = if (line.isEmpty()) "" else line.substring(1) + "\n"
            when (line.firstOrNull()) {
                '+' -> {
                    after.append(rest)
                }

                '-' -> {
                    before.append(rest)
                }

                ' ' -> {
                    before.append(rest)
                    after.append(rest)
                }

                else -> {
                    Unit
                }
            }
        }
        return before.toString() to after.toString()
    }

    /**
     * What to print for [items] (a path and the text that shows its change): with `--stat` the
     * stat lines and totals, otherwise [shown] and the totals line under it.
     */
    fun show(
        items: List<Pair<String, String>>,
        shown: String,
    ): String {
        val p = Preview()
        for ((path, text) in items) {
            val (before, after) = sides(text)
            p.edit(path, before, after)
        }
        return when {
            p.isEmpty() -> shown
            on() -> p.render(PreviewMode.Stat).trimEnd()
            else -> withTotals(shown, p)
        }
    }

    // Without edit diffs `Diff` mode prints the totals line alone.
    private fun withTotals(
        shown: String,
        p: Preview,
    ): String {
        val totals = p.render(PreviewMode.Diff).trimEnd('\n').substringAfterLast('\n')
        return if (shown.isEmpty()) totals else "${shown.trimEnd()}\n$totals"
    }

    /** [show] for one file's unified diff. */
    fun showFile(
        path: String,
        diff: String,
    ): String = show(listOf(path to diff), shown(diff))

    /** [diff], unless `--stat` replaces it. */
    fun shown(diff: String): String = if (on()) "" else diff
}
