// Port of the tests of src/doctor/web.rs over the in-memory [FixMachine].

package rtok.doctor

import rtok.config.Config
import rtok.doctor.FixMachine.Companion.BROKEN
import rtok.doctor.FixMachine.Companion.PROJECT_DUP
import rtok.doctor.FixMachine.Companion.SETTINGS
import rtok.doctor.FixMachine.Companion.USER_DUP
import rtok.doctor.FixMachine.Companion.cfg
import rtok.doctor.FixMachine.Companion.machine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class DoctorWebTest {
    private val opts = FixOpts(3, null, Fix.KINDS)

    private fun at(source: String) = Ref(source, "hooks.Stop[0].hooks[0]")

    private fun planOf(
        m: FixMachine,
        sel: Selection,
        c: Config = cfg(),
    ) = DoctorWeb.plan(c, m.probes(), m, opts, sel)

    private fun confirm(
        m: FixMachine,
        sel: Selection,
    ) = planOf(m, sel).token

    private fun apply(
        m: FixMachine,
        sel: Selection,
        token: String,
        c: Config = cfg(),
    ) = DoctorWeb.apply(c, m.probes(), m, opts, sel, token)

    @Test
    fun thePlanStartsFromTheTerminalDefaultsAndWritesNothing() {
        val m = machine(BROKEN)
        m.put(PROJECT, BROKEN)
        val plan = planOf(m, Selection())
        val user = plan.items.first { it.source == SETTINGS }
        val project = plan.items.first { it.source == PROJECT }
        assertTrue(user.selected && !user.shared)
        assertTrue(!project.selected && project.shared)
        assertTrue(plan.diff.contains(SETTINGS) && !plan.diff.contains(PROJECT))
        assertTrue(m.backups.isEmpty())
        assertEquals(BROKEN, m.text(SETTINGS))
    }

    @Test
    fun applyWritesTheSelectionAfterABackupAndOnlyThat() {
        val m = machine(BROKEN)
        m.put(PROJECT, BROKEN)
        val sel = Selection()
        val done = apply(m, sel, confirm(m, sel))
        assertEquals(0, done.code, done.text)
        assertTrue(done.text.contains("1 entry removed"), done.text)
        assertFalse(m.text(SETTINGS).contains("old.sh"))
        assertEquals(BROKEN, m.text(PROJECT))
        assertEquals(1, m.backups.size)
        // The toggle flips the project file on, and the user's off.
        val toggled = Selection(toggled = listOf(at(PROJECT)))
        val m2 = machine(BROKEN)
        m2.put(PROJECT, BROKEN)
        apply(m2, toggled, confirm(m2, toggled))
        assertFalse(m2.text(PROJECT).contains("old.sh"))
    }

    @Test
    fun aKeptCopySwapAndAToggleMoveTheRemovalToTheOtherFile() {
        val m = machine(USER_DUP)
        m.put(PROJECT, PROJECT_DUP)
        val first = planOf(m, Selection())
        assertEquals(1, first.items.size)
        assertEquals(SETTINGS, first.items[0].source)
        assertTrue(first.items[0].canKeep)
        assertEquals(PROJECT, first.items[0].keptIn)
        val sel = Selection(keep = listOf(at(SETTINGS)), toggled = listOf(at(PROJECT)))
        val swapped = planOf(m, sel)
        assertEquals(1, swapped.items.size)
        assertTrue(swapped.items[0].shared && swapped.items[0].selected)
        assertEquals(SETTINGS, swapped.items[0].keptIn)
        apply(m, sel, confirm(m, sel))
        assertTrue(m.text(SETTINGS).contains("/h/.claude/settings.json"))
        assertFalse(m.text(PROJECT).contains("Stop"))
    }

    @Test
    fun aRefThatNamesNothingChangesNothing() {
        val m = machine(BROKEN)
        val sel = Selection(keep = listOf(at("/nowhere")), toggled = listOf(at("/nowhere")))
        val done = apply(m, sel, confirm(m, sel))
        assertTrue(done.text.contains("1 entry removed"), done.text)
        assertFalse(m.text(SETTINGS).contains("old.sh"))
    }

    @Test
    fun aTomlHookIsRefusedInThePlanAndNeverWritten() {
        val toml = "[[hooks]]\nevent = \"Stop\"\ncommand = \"/h/gone.sh\"\n"
        val m = machine("{}")
        m.put("/h/.kimi-code/config.toml", toml)
        val base = cfg()
        val c = base.copy(setup = base.setup.copy(kimi = base.setup.kimi.copy(configPath = "/h/.kimi-code/config.toml")))
        val plan = planOf(m, Selection(), c)
        assertTrue(plan.items.isEmpty(), "${plan.items}")
        assertTrue(plan.refused[0].contains("TOML hook files are not edited yet"))
        apply(m, Selection(), plan.token, c)
        assertEquals(toml, m.text("/h/.kimi-code/config.toml"))
    }

    @Test
    fun anEntryThatAppearedAfterThePreviewIsNotRemovedByItsConfirmation() {
        for ((path, body) in listOf(SETTINGS to TWO, SETTINGS to BEFORE, CURSOR to CURSOR_BODY)) {
            val m = machine(BROKEN)
            val sel = Selection()
            val token = confirm(m, sel)
            m.put(path, body)
            val done = apply(m, sel, token)
            assertEquals(1, done.code, "$path: ${done.text}")
            val fresh = assertNotNull(done.replan, "a fresh preview to confirm")
            assertNotEquals(token, fresh.token)
            assertEquals(body, m.text(path), "nothing was written")
            assertTrue(m.backups.isEmpty())
            // The fresh preview, once confirmed, writes what it shows and nothing else.
            val again = apply(m, sel, fresh.token)
            assertEquals(0, again.code, again.text)
        }
    }

    @Test
    fun anUnchangedPreviewStillAppliesAndAMissingOrWrongTokenDoesNot() {
        val m = machine(BROKEN)
        val sel = Selection()
        for (bad in listOf("", "0000")) {
            val done = apply(m, sel, bad)
            assertEquals(1 to true, done.code to (done.replan != null), bad)
            assertEquals(BROKEN, m.text(SETTINGS))
        }
        val done = apply(m, sel, confirm(m, sel))
        assertEquals(0, done.code, done.text)
        assertFalse(m.text(SETTINGS).contains("old.sh"))
    }

    @Test
    fun theTokenFollowsTheSelectionSoAToggleNeedsItsOwnConfirmation() {
        val m = machine(BROKEN)
        m.put(PROJECT, BROKEN)
        val first = Selection()
        val both = Selection(toggled = listOf(at(PROJECT)))
        assertNotEquals(confirm(m, first), confirm(m, both))
        val done = apply(m, both, confirm(m, first))
        assertEquals(1, done.code)
        assertEquals(BROKEN, m.text(PROJECT))
    }

    private companion object {
        const val PROJECT = "/proj/.claude/settings.json"
        const val TWO = """{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}, {"hooks": [{"type": "command", "command": "/h/gone/other.sh"}]}]}}"""
        const val BEFORE = """{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/other.sh"}]}, {"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}]}}"""
        const val CURSOR = "/h/.cursor/hooks.json"
        const val CURSOR_BODY = """{"version": 1, "hooks": {"stop": [{"command": "/h/gone/cursor.sh"}]}}"""
    }
}
