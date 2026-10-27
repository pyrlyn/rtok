@file:Suppress("MagicNumber")

// The pure tests of src/doctor/fix.rs (`is_rtok_own`, `parse_path`) and the checklist (checklist.rs) over scripted
// answers; the file-writing fix.rs tests are in FixTest.

package rtok.doctor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecklistTest {
    @Test
    fun isRtokOwnMatchesTheBinaryAndTheHookShimOnly() {
        assertTrue(Fix.isRtokOwn("/usr/local/bin/rtok hook pre-tool-use"))
        assertTrue(Fix.isRtokOwn("'/x y/rtok-hook' run"))
        assertFalse(Fix.isRtokOwn("/h/scripts/rtokish.sh"))
    }

    @Test
    fun parsePathReadsNestedAndFlatKeyPaths() {
        assertEquals(Triple("PreToolUse", 1, 2), Fix.parsePath("hooks.PreToolUse[1].hooks[2]"))
        assertEquals(Triple("Stop", 0, null), Fix.parsePath("hooks.Stop[0]"))
        assertNull(Fix.parsePath("hooks.Stop"))
        assertNull(Fix.parsePath("other.Stop[0]"))
        assertNull(Fix.parsePath("hooks.Stop[x]"))
    }

    private fun dup(
        source: String,
        keep: Boolean,
        fixable: Boolean = true,
    ) = Problem(
        kind = "duplicate-hook",
        agent = "claude",
        source = source,
        path = "hooks.Stop[0]",
        event = "Stop",
        command = "/bin/x",
        detail = if (keep) "runs 2 times; keep this copy (user file)" else "runs 2 times; an extra copy",
        fixable = fixable,
        group = 0,
        keep = keep,
    )

    private class Script(
        vararg answers: String,
    ) : Prompt {
        val left = answers.toMutableList()
        val screens = mutableListOf<String>()

        override fun ask(screen: String): String? {
            screens += screen
            return left.removeFirstOrNull()
        }
    }

    @Test
    fun aSharedProjectFileStartsUnselectedAndYesNeedsTheLastConfirmation() {
        val found = mutableListOf(dup("/h/.claude/settings.json", true), dup("/proj/.claude/settings.json", false))
        val shared = Checklist.sharedIn("/proj", "/h")
        val s = Script("y", "1", "y", "y")
        val c = Checklist.run(found, Fix.KINDS, shared, { _, _ -> "DIFF" }, s)
        assertEquals("nothing is selected\n", s.screens[1].lines().first() + "\n")
        assertTrue(s.screens[0].contains(" 1 [ ] extra hook Stop `/bin/x`"), s.screens[0])
        assertTrue(s.screens[0].contains("(shared project file); kept in /h/.claude/settings.json"), s.screens[0])
        assertTrue(s.screens[3].startsWith("DIFF\nWrite 1 change(s)?"), s.screens[3])
        assertEquals(Choice.Apply(emptySet()), c)
    }

    @Test
    fun kSwapsTheKeptCopyAndARefusalIsANote() {
        val found = mutableListOf(dup("/a.json", true), dup("/b.json", false))
        val s = Script("k 1", "q")
        Checklist.run(found, Fix.KINDS, { false }, { _, _ -> "" }, s)
        assertTrue(found[1].keep && !found[0].keep)
        assertEquals("runs 2 times; keep this copy (your choice)", found[1].detail)
        assertTrue(s.screens[1].contains("extra hook Stop `/bin/x`\n      /a.json; kept in /b.json"), s.screens[1])
        val locked = mutableListOf(dup("/a.json", true, fixable = false), dup("/b.json", false))
        assertEquals("the kept copy is not yours to edit, so it stays", Checklist.keepCopy(locked, 1).exceptionOrNull()?.message)
        val t = Script("zz", "k 9")
        assertEquals(Choice.Cancel, Checklist.run(locked, Fix.KINDS, { false }, { _, _ -> "" }, t))
        assertTrue(t.screens[1].startsWith("answer a number, k N, d, y or q\n"))
        assertTrue(t.screens[2].startsWith("no such item\n"))
    }
}
