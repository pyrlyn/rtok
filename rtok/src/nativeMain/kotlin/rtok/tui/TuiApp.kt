// Port of src/tui/app.rs, first slice: the pure state of `rtok tui` (tab, row cursors, help) over the same
// snapshot `rtok web` serves, and the global keys. Pages that edit (plugin toggles, doctor fix, junk clear,
// filters, drill) follow.
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import tui.Key

/** model::pages(): every tab with the snapshot field it renders (the web UI's sidebar is the same list). */
val TABS: List<Pair<String, String>> =
    listOf(
        "overview" to "usage",
        "plugins" to "plugins",
        "calls" to "calls",
        "sessions" to "sessions",
        "doctor" to "doctor",
        "logs" to "logs",
        "skills" to "skills",
        "stats" to "stats",
        "graph" to "graph",
        "hosts" to "hosts",
        "config" to "config",
        "services" to "services",
        "worktrees" to "worktrees",
        "usage" to "agent_usage",
        "tasks" to "tasks",
    )

private const val PAGE_ROWS = 10
private const val MAX_DIGIT = 9

/** What a key asked the loop to do beyond redrawing. */
enum class Effect { NONE, QUIT, REFRESH }

/** `rtok tui` state; [snapshot] is replaced on every tick. */
class TuiApp(
    startTab: String,
) {
    /** app.rs `new`: `[tui] tab` when it names a page, else the first tab. */
    var tab: Int = TABS.indexOfFirst { it.first == startTab }.coerceAtLeast(0)
        private set
    var help = false
        private set
    var snapshot: JsonObject = JsonObject(emptyMap())
    private val cursors = IntArray(TABS.size)

    val page: String get() = TABS[tab].first
    val field: String get() = TABS[tab].second

    /** The row cursor of the current tab. */
    val cursor: Int get() = cursors[tab].coerceIn(0, (rows() - 1).coerceAtLeast(0))

    /** Rows the current tab lists (the cursor's range). */
    fun rows(): Int =
        when (val v = snapshot[field]) {
            is JsonArray -> v.size
            is kotlinx.serialization.json.JsonPrimitive -> if (v.isString) v.content.lines().size else 0
            is JsonObject -> (v["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lines()?.size ?: v.size
            else -> 0
        }

    /** Moves to tab [i], wrapping. */
    fun select(i: Int) {
        tab = Math.floorMod(i, TABS.size)
    }

    private fun move(d: Int) {
        cursors[tab] = (cursor + d).coerceIn(0, (rows() - 1).coerceAtLeast(0))
    }

    /** app.rs `key`: Ctrl+C / q / Esc quit, ←/→ (Tab, Shift+Tab) wrap, 1..9 jump, `?` help, `r` re-read, rows move. */
    fun key(k: Key): Effect =
        when {
            help && k != Key.Char("?") && k != Key.Ctrl('c') -> {
                help = false
                Effect.NONE
            }

            k == Key.Ctrl('c') || k == Key.Char("q") || k == Key.Esc -> {
                Effect.QUIT
            }

            k == Key.Char("r") -> {
                Effect.REFRESH
            }

            else -> {
                nav(k)
                Effect.NONE
            }
        }

    private fun nav(k: Key) {
        when (k) {
            Key.Char("?") -> {
                help = !help
            }

            Key.Left, Key.BackTab -> {
                select(tab - 1)
            }

            Key.Right, Key.Tab -> {
                select(tab + 1)
            }

            Key.Up, Key.Char("k") -> {
                move(-1)
            }

            Key.Down, Key.Char("j") -> {
                move(1)
            }

            Key.PageUp -> {
                move(-PAGE_ROWS)
            }

            Key.PageDown -> {
                move(PAGE_ROWS)
            }

            Key.Home -> {
                cursors[tab] = 0
            }

            Key.End -> {
                cursors[tab] = (rows() - 1).coerceAtLeast(0)
            }

            is Key.Char -> {
                k.c
                    .toIntOrNull()
                    ?.takeIf { it in 1..MAX_DIGIT }
                    ?.let { select(it - 1) }
            }

            else -> {
                Unit
            }
        }
    }
}

private object Math {
    fun floorMod(
        a: Int,
        n: Int,
    ) = ((a % n) + n) % n
}
