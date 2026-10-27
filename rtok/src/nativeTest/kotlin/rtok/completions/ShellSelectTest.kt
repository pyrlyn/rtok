// The picker's keys (src/completions/picker.rs `ask_terminal`): pre-checked rows, space, arrows, enter, esc.
package rtok.completions

import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellSelectTest {
    private val rows =
        listOf(
            Status(Shell.Bash, "/b", installed = false),
            Status(Shell.Zsh, "/z", installed = true),
            Status(Shell.Fish, "/f", installed = false),
        )

    private fun run(vararg keys: Key): ShellSelect.Outcome? {
        val s = ShellSelect(rows)
        return keys.firstNotNullOfOrNull(s::key)
    }

    @Test fun enterKeepsTheInstalledRowsChecked() = assertEquals(ShellSelect.Outcome.Apply(listOf(Shell.Zsh)), run(Key.Enter))

    @Test fun spaceChecksTheCursorRowAndDownMovesIt() = assertEquals(ShellSelect.Outcome.Apply(listOf(Shell.Bash)), run(Key.Char(" "), Key.Down, Key.Char(" "), Key.Enter))

    @Test fun upWrapsToTheLastRow() = assertEquals(ShellSelect.Outcome.Apply(listOf(Shell.Zsh, Shell.Fish)), run(Key.Up, Key.Char(" "), Key.Enter))

    @Test fun escAndCtrlCCancel() {
        assertEquals(ShellSelect.Outcome.Cancel, run(Key.Char(" "), Key.Esc))
        assertEquals(ShellSelect.Outcome.Cancel, run(Key.Ctrl('c')))
        assertNull(run(Key.Char("x"), Key.Down))
    }

    @Test fun theLinesShowThePromptTheCursorAndTheMarks() {
        val l = ShellSelect(rows).lines()
        assertTrue(l[0].contains("esc cancels"), l[0])
        assertEquals("> [ ] bash       not installed", l[1])
        assertEquals("  [x] zsh        installed", l[2])
    }
}
