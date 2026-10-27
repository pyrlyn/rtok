// Port of src/tui/junk_clear.rs: the Hosts tab's "clear safe junk" panel. `c` shows the dry run, `y` removes what it
// listed, any other key declines; after the removal any key closes.
package rtok.tui

import rtok.agents.Cleared
import rtok.agents.Planned
import tui.Key

/** What a key did on the panel: let the page have it, keep it, or keep it after a write (the loop re-reads). */
enum class Keyed { PASS, TAKEN, WROTE }

class TuiJunk {
    sealed interface Stage {
        data object Closed : Stage

        class Plan(
            val plan: Cleared,
        ) : Stage

        class Done(
            val cleared: Cleared,
        ) : Stage
    }

    var stage: Stage = Stage.Closed
        private set

    fun key(
        k: Key,
        actions: TuiActions,
    ): Keyed {
        val s = stage
        stage = Stage.Closed
        return when {
            s is Stage.Closed && k == Key.Char("c") -> {
                stage = Stage.Plan(actions.junkPlan())
                Keyed.TAKEN
            }

            s is Stage.Closed -> {
                Keyed.PASS
            }

            s is Stage.Plan && k == Key.Char("y") -> {
                val paths = removable(s.plan).map { it.path }
                if (paths.isEmpty()) {
                    Keyed.TAKEN
                } else {
                    stage = Stage.Done(actions.junkApply(paths))
                    Keyed.WROTE
                }
            }

            else -> {
                Keyed.TAKEN
            }
        }
    }

    /** The head line and its tone, then the command's text; null while closed. */
    fun panel(actions: TuiActions): Pair<Pair<String, String>, String>? {
        val s = stage
        val (c, head, tone) =
            when {
                s is Stage.Plan && removable(s.plan).isEmpty() -> Triple(s.plan, "clear safe junk: nothing to remove (any key closes)", "muted")
                s is Stage.Plan -> Triple(s.plan, "clear safe junk (dry run, nothing removed): y = remove, any other key = cancel", "warn")
                s is Stage.Done && s.cleared.failed -> Triple(s.cleared, "clear safe junk: some items were kept (any key closes)", "warn")
                s is Stage.Done -> Triple(s.cleared, "clear safe junk: done (any key closes)", "ok")
                else -> return null
            }
        return (head to tone) to actions.junkText(c)
    }
}

/** What `y` removes: the planned items the plan marks `clear`. */
internal fun removable(c: Cleared): List<Planned> = c.items.filter { it.planned && it.action == "clear" }
