// Port of the tests in src/completions/picker.rs (T408): the plan, status after install/uninstall, a confirmed,
// unchanged or cancelled selection, a failing prompt, and the `--list` lines.
@file:Suppress("MagicNumber")

package rtok.completions

import rtok.cli.CommandSpec
import rtok.completions.Shell.Bash
import rtok.completions.Shell.Fish
import rtok.completions.Shell.Zsh
import rtok.sys.exists
import rtok.sys.removeDirAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PickerTest {
    private val home = "${rtok.sys.tempDir()}/rtok-completions-picker-${rtok.sys.pid()}".also { removeDirAll(it) }
    private val p = Places(home)
    private val cmd = CommandSpec("rtok")

    @AfterTest fun clean() = removeDirAll(home)

    private fun on() = Install.status(p).filter { it.installed }.map { it.shell }

    @Test fun planCoversInstallUninstallMixedAndNoop() {
        assertEquals(Changes(listOf(Bash, Zsh), emptyList()), Picker.plan(emptyList(), listOf(Bash, Zsh)))
        assertEquals(Changes(emptyList(), listOf(Bash)), Picker.plan(listOf(Bash, Zsh), listOf(Zsh)))
        assertEquals(Changes(listOf(Fish), listOf(Bash)), Picker.plan(listOf(Bash), listOf(Fish)))
        assertEquals(Changes(emptyList(), emptyList()), Picker.plan(listOf(Bash, Zsh), listOf(Zsh, Bash)))
    }

    @Test fun statusFollowsTheFilesInstallAndUninstallWrite() {
        assertTrue(on().isEmpty())
        Install.install(Zsh, cmd, p)
        assertEquals(listOf(Zsh), on())
        Install.uninstall(Zsh, p)
        assertTrue(on().isEmpty())
        val clink = Install.status(p).first { it.shell == Shell.Clink }
        assertTrue(clink.path == null && !clink.installed)
    }

    @Test fun confirmedSelectionInstallsAndRemovesFiles() {
        Install.install(Bash, cmd, p)
        val bash = Install.status(p)[0].path!!
        val lines =
            Picker.pick(p, cmd) { rows ->
                assertTrue(rows.none { it.shell == Shell.Clink }, "clink is not offered here")
                assertTrue(rows.first { it.shell == Bash }.installed)
                listOf(Zsh)
            }
        assertTrue(!exists(bash))
        assertTrue(exists("$home/.zfunc/_rtok"))
        assertTrue(lines.any { it.startsWith("removed ") } && lines.any { it.startsWith("wrote ") }, "$lines")
    }

    @Test fun unchangedSelectionAndCancelTouchNothing() {
        Install.install(Fish, cmd, p)
        assertEquals(listOf("no changes"), Picker.pick(p, cmd) { listOf(Fish) })
        assertEquals(listOf("cancelled, nothing changed"), Picker.pick(p, cmd) { null })
        assertTrue(exists("$home/.config/fish/completions/rtok.fish"))
        assertTrue(!exists("$home/.zfunc"))
    }

    @Test fun aFailingPromptChangesNoFile() {
        assertTrue("no tty" in assertFailsWith<IllegalStateException> { Picker.pick(p, cmd) { error("no tty") } }.message!!)
        assertTrue(Picker.list(p).all { " no " in it }, "${Picker.list(p)}")
    }

    @Test fun listHasALinePerShellWithStateAndPath() {
        Install.install(Bash, cmd, p)
        val lines = Picker.list(p)
        assertEquals(6, lines.size)
        assertTrue(lines[0].startsWith("bash       yes ") && lines[0].endsWith("completions/rtok"), lines[0])
        assertTrue(lines[1].startsWith("zsh        no  "), lines[1])
        assertTrue(lines[5].endsWith(" -"), lines[5])
    }
}
