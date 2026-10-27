// Port of the tests of src/tui/doctor_fix.rs (T478) over an injected engine: no real host file is read or written.
@file:Suppress("MagicNumber")

package rtok.tui

import rtok.doctor.FixItem
import rtok.doctor.FixPlan
import rtok.doctor.Fixed
import rtok.doctor.Selection
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiDoctorFixTest {
    private class Fake {
        val applied = mutableListOf<Pair<Selection, String>>()
        var plans = 0
        var drift = false

        fun plan(sel: Selection): FixPlan {
            plans++
            val items =
                listOf("/home/.claude/settings.json", "/proj/.claude/settings.json").map { src ->
                    val off = sel.toggled.any { it.source == src }
                    FixItem(src, "hooks.Stop[0]", "hook", "claude", "rtok-hook", "Stop", null, src.startsWith("/proj"), !src.startsWith("/proj") != off, true)
                }
            return FixPlan(items, "--- a\n+++ b\n-old", emptyList(), "t${sel.toggled.size}${if (drift) "x" else ""}")
        }

        fun engine() =
            DoctorEngine({ _, s -> plan(s) }) { _, s, token ->
                val now = plan(s)
                if (now.token != token) {
                    Fixed("The files changed since the preview.\n", 1, now)
                } else {
                    applied += s to token
                    Fixed("removed 1 entry\n", 0, null)
                }
            }
    }

    private fun screen(a: TuiApp) = Buffer(120, 30).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    private fun app(f: Fake) = TuiApp("doctor").also { it.doctorEngine(f.engine()) }

    @Test fun fOpensADryRunChecklistAndEscClosesIt() {
        val f = Fake()
        val a = app(f)
        assertEquals(Effect.NONE, a.key(Key.Char("f")))
        val s = screen(a)
        assertTrue("doctor --fix checklist (dry run, nothing written)" in s, s)
        assertTrue("[x] hook rtok-hook in /home/.claude/settings.json" in s, s)
        assertTrue("[ ] hook rtok-hook in /proj/.claude/settings.json (project file)" in s, s)
        assertTrue("+++ b" in s, s)
        a.key(Key.Esc)
        assertFalse("dry run" in screen(a))
        assertTrue(f.applied.isEmpty())
    }

    @Test fun spaceTogglesThroughANewPlanAndEnterAsksBeforeWriting() {
        val f = Fake()
        val a = app(f)
        a.key(Key.Char("f"))
        a.key(Key.Down)
        a.key(Key.Char(" "))
        assertEquals(2, f.plans, "a toggle re-plans")
        assertEquals(2, a.doctorFix.chosen())
        a.key(Key.Enter)
        assertTrue("Remove 2 selected entries?" in screen(a))
        a.key(Key.Char("n"))
        assertTrue(f.applied.isEmpty(), "any key but y declines")
        a.key(Key.Enter)
        assertEquals(Effect.REFRESH, a.key(Key.Char("y")), "a write re-reads the model")
        assertEquals(1, f.applied.size)
        assertTrue("doctor --fix: done (any key closes)" in screen(a))
        a.key(Key.Char("x"))
        assertFalse(a.doctorFix.isOpen)
    }

    @Test fun aStalePreviewWritesNothingAndShowsTheFreshOne() {
        val f = Fake()
        val a = app(f)
        a.key(Key.Char("f"))
        f.drift = true
        a.key(Key.Enter)
        assertEquals(Effect.NONE, a.key(Key.Char("y")))
        assertTrue(f.applied.isEmpty())
        assertTrue("The files changed since the preview." in screen(a))
        a.key(Key.Enter)
        assertEquals(Effect.REFRESH, a.key(Key.Char("y")), "the fresh plan needs its own y")
    }

    @Test fun globalKeysPassWhileOpenAndOtherPagesIgnoreF() {
        val f = Fake()
        val a = app(f)
        a.key(Key.Char("f"))
        assertEquals(Effect.QUIT, a.key(Key.Char("q")))
        val nav = TuiApp("doctor")
        nav.key(Key.Char("f"))
        assertFalse(nav.doctorFix.isOpen, "without an engine the checklist never opens")
        a.key(Key.Esc)
        a.key(Key.Right)
        a.key(Key.Char("f"))
        assertEquals(1, f.plans)
    }
}
