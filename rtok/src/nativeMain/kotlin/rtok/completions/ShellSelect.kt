// Port of src/completions/picker.rs `ask_terminal` (T408; inquire's MultiSelect in Rust): the shells, pre-checked
// where the script is installed. Space toggles, enter applies, esc or Ctrl-C cancels.
package rtok.completions

import tui.Buffer
import tui.Key
import tui.Terminal

/** The picker's state: which row the cursor is on and which rows are checked. Pure, so tests drive it with keys. */
class ShellSelect(
    private val rows: List<Status>,
) {
    private var cursor = 0
    private val checked = rows.indices.filter { rows[it].installed }.toMutableSet()

    sealed interface Outcome {
        data class Apply(
            val shells: List<Shell>,
        ) : Outcome

        data object Cancel : Outcome
    }

    /** Applies [k]; an [Outcome] once the selection is confirmed or cancelled. */
    fun key(k: Key): Outcome? =
        when (k) {
            Key.Enter -> Outcome.Apply(rows.indices.filter { it in checked }.map { rows[it].shell })
            Key.Esc, Key.Ctrl('c') -> Outcome.Cancel
            else -> null.also { move(k) }
        }

    private fun move(k: Key) {
        val n = rows.size.coerceAtLeast(1)
        when (k) {
            Key.Up, Key.Ctrl('p'), Key.Char("k") -> cursor = (cursor - 1 + n) % n
            Key.Down, Key.Ctrl('n'), Key.Char("j"), Key.Tab -> cursor = (cursor + 1) % n
            Key.Char(" ") -> if (cursor in rows.indices && !checked.remove(cursor)) checked += cursor
            else -> Unit
        }
    }

    fun lines(): List<String> =
        listOf("? $PROMPT") +
            rows.mapIndexed { i, s ->
                val mark = if (s.installed) "installed" else "not installed"
                "${if (i == cursor) ">" else " "} [${if (i in checked) "x" else " "}] ${s.shell.cliName.padEnd(NAME_W)} $mark"
            }

    companion object {
        const val PROMPT = "Shells with rtok completions (space toggles, enter applies, esc cancels)"
        private const val NAME_W = 10
        private const val POLL_MS = 200

        /** The terminal prompt over stdin/stdout (null = cancelled). */
        fun ask(rows: List<Status>): List<Shell>? {
            val select = ShellSelect(rows)
            Terminal().use { t ->
                t.open()
                while (true) {
                    val (w, h) = t.size()
                    val b = Buffer(w, h)
                    select.lines().forEachIndexed { y, line -> b.put(0, y, line) }
                    t.draw(b)
                    for (k in t.poll(POLL_MS)) {
                        when (val o = select.key(k)) {
                            is Outcome.Apply -> return o.shells
                            Outcome.Cancel -> return null
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}
