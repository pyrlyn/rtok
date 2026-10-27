// Port of src/tui/panel.rs (T329.48): the shell the Graph tab's panels share, compare (T485) and export (T329.41).
// A panel replaces the tab body while it is open; this is the stage it moves through, the scroll keys, the keys it
// leaves to the tab shell and the layout with the hints line. The loop has no worker yet, so a run answers on the
// key, as Rust's foreground path does.
package rtok.tui

import tui.Buffer
import tui.Color
import tui.Key
import tui.Line
import tui.Rect
import tui.Size
import tui.Span
import tui.Style
import tui.paragraph
import tui.rows
import tui.wrap

/** Rows one PageUp or PageDown moves. */
private const val PAGE = 10
private const val HINT_ROWS = 2

/** [A] is what the panel asks the user for while a field has the keys. */
sealed interface PanelStage<out A> {
    data object Closed : PanelStage<Nothing>

    data class Prompt<A>(
        val ask: A,
    ) : PanelStage<A>

    data class Shown(
        val lines: List<Line>,
    ) : PanelStage<Nothing>
}

/** panel.rs `error_lines`: one `✕` banner. */
fun errorLines(e: String): List<Line> = listOf(listOf(Span("✕ ", Style(fg = Color.Red, bold = true)), Span(e, Style(bold = true))))

/** panel.rs `edit`: Backspace drops a character, a printable key adds one. */
fun edit(
    input: String,
    k: Key,
): String =
    when (k) {
        Key.Backspace -> input.dropLast(1)
        is Key.Char -> input + k.c
        else -> input
    }

/** panel.rs `Stage::run` in the foreground: the answer, or its error as a banner. */
fun shown(work: () -> List<Line>): PanelStage<Nothing> = PanelStage.Shown(runCatching(work).getOrElse { errorLines(it.message ?: it.toString()) })

abstract class TuiPanel<A>(
    /** Names the panel in the hints line. */
    val label: String,
    private val hints: String,
) {
    var stage: PanelStage<A> = PanelStage.Closed
        private set
    var scroll = 0
        private set

    val isOpen: Boolean get() = stage != PanelStage.Closed

    /** Whether keys go to a field, ahead of the shell's own (`?`, `r`, `q`). */
    val typing: Boolean get() = stage is PanelStage.Prompt

    /** The next stage when [k] opens the panel (from closed, or over a result); null when it is not an opening key. */
    protected abstract fun open(
        k: Key,
        target: Pair<Int, String>?,
    ): PanelStage<A>?

    /** A key for the field [ask]; the next stage, or null to stay. */
    protected abstract fun prompt(
        ask: A,
        k: Key,
        actions: TuiActions,
    ): PanelStage<A>?

    abstract fun promptLines(ask: A): List<Line>

    private fun shellKey(k: Key): Boolean = k == Key.Left || k == Key.Right || k == Key.Char("q") || (k as? Key.Char)?.c?.singleOrNull()?.let { it in '1'..'9' } == true

    /** Whether the panel took [k]; the shell keeps its tab, quit and digit keys while a result is up. */
    fun key(
        k: Key,
        target: Pair<Int, String>?,
        actions: TuiActions,
    ): Boolean {
        val s = stage
        val taken = !(isOpen && !typing && shellKey(k)) && !(s == PanelStage.Closed && openFrom(k, target) == null)
        if (taken && s != PanelStage.Closed) {
            val next =
                when (s) {
                    is PanelStage.Prompt -> prompt(s.ask, k, actions)
                    is PanelStage.Shown -> if (k == Key.Esc) PanelStage.Closed else openFrom(k, target) ?: null.also { scrollKey(s.lines.size - 1, k) }
                    else -> null
                }
            if (next != null) stage = next
        }
        return taken
    }

    /** Opens on [k] when it is an opening key: the stage is set and the scroll reset. */
    private fun openFrom(
        k: Key,
        target: Pair<Int, String>?,
    ): PanelStage<A>? =
        open(k, target)?.also {
            stage = it
            scroll = 0
        }

    private fun scrollKey(
        last: Int,
        k: Key,
    ) {
        val top = last.coerceAtLeast(0)
        scroll =
            when (k) {
                Key.Up -> (scroll - 1).coerceAtLeast(0)
                Key.Down -> (scroll + 1).coerceAtMost(top)
                Key.PageUp -> (scroll - PAGE).coerceAtLeast(0)
                Key.PageDown -> (scroll + PAGE).coerceAtMost(top)
                Key.Home -> 0
                else -> scroll
            }
    }

    /** Draws the panel over [r]; false when it is closed and the tab draws itself. */
    fun render(
        b: Buffer,
        r: Rect,
    ): Boolean {
        val lines =
            when (val s = stage) {
                PanelStage.Closed -> return false
                is PanelStage.Prompt -> promptLines(s.ask)
                is PanelStage.Shown -> s.lines
            }
        val (text, foot) = r.rows(Size.Fill(), Size.Fixed(HINT_ROWS))
        // Rows wrap to the width, as ratatui's `Wrap { trim: false }` does; the scroll counts logical lines.
        val shown = lines.drop(scroll.coerceAtMost((lines.size - 1).coerceAtLeast(0))).flatMap { wrap(it, text.w) }
        b.paragraph(text, shown)
        b.paragraph(foot, listOf(listOf(Span("$label: $hints", MUTED))))
        return true
    }
}
