// Port of the tests in src/completions/install.rs and picker.rs (T408, T416, T943): standard places, XDG and
// ZDOTDIR overrides, byte-for-byte rc files, wrappers that ask the installed rtok, `$SHELL` picking, the picker plan.
@file:Suppress("MagicNumber")

package rtok.completions

import rtok.cli.CliFailure
import rtok.cli.CommandSpec
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallTest {
    private val home = "${rtok.sys.tempDir()}/rtok-completions-install-${rtok.sys.pid()}".also { removeDirAll(it) }
    private val p = Places(home)
    private val cmd = CommandSpec("rtok")

    @AfterTest fun clean() = removeDirAll(home)

    @Test fun eachShellLandsInItsStandardPlaceAndReinstallIsUnchanged() {
        val p = p.copy(localAppData = "$home/AppData/Local")
        for ((shell, rel) in listOf(
            Shell.Bash to ".local/share/bash-completion/completions/rtok",
            Shell.Zsh to ".zfunc/_rtok",
            Shell.Fish to ".config/fish/completions/rtok.fish",
            Shell.Elvish to ".config/elvish/lib/rtok.elv",
            Shell.Powershell to ".config/powershell/rtok.ps1",
            Shell.Clink to "AppData/Local/clink/rtok.lua",
        )) {
            assertEquals("wrote $home/$rel", Install.install(shell, cmd, p)[0], "$shell")
            assertTrue(Install.install(shell, cmd, p)[0].startsWith("unchanged "), "$shell")
            assertEquals("removed $home/$rel", Install.uninstall(shell, p)[0])
            assertTrue(!exists("$home/$rel"))
        }
    }

    @Test fun xdgDirsWinOverHomeDefaults() {
        val lines = Install.install(Shell.Bash, cmd, p.copy(xdgData = "$home/data"))
        assertTrue(lines[0].endsWith("data/bash-completion/completions/rtok"), "$lines")
    }

    @Test fun powershellProfileKeepsOtherContentByteForByte() {
        val profile = p.psProfile
        createDirAll(profile.substringBeforeLast('/'))
        val mine = "Set-Alias ll ls\r\n# keep me"
        writeString(profile, mine)
        Install.install(Shell.Powershell, cmd, p)
        assertTrue(readToString(profile).startsWith("$mine\n. \""))
        assertEquals("unchanged $profile", Install.install(Shell.Powershell, cmd, p)[1])
        Install.uninstall(Shell.Powershell, p)
        assertEquals("$mine\n", readToString(profile))
    }

    @Test fun zshWritesItsInitAndOneZshenvLineAndUninstallTakesThemBack() {
        createDirAll(home)
        val zshenv = "$home/.zshenv"
        val mine = "export A=1\r\n# keep me"
        writeString(zshenv, mine)
        val init = "$home/.zfunc/rtok.zsh"
        assertEquals(listOf("wrote $home/.zfunc/_rtok", "wrote $init", "wrote $zshenv"), Install.install(Shell.Zsh, cmd, p))
        val body = readToString(init)
        val dir = Places.shQuote("$home/.zfunc")
        assertTrue("fpath=($dir \${fpath:#$dir})" in body, body)
        assertTrue("compdef _rtok rtok" in body, body)
        val q = Places.shQuote(init)
        assertEquals("$mine\n[[ -o interactive && -r $q ]] && . $q  # rtok completions\n", readToString(zshenv))
        assertTrue(Install.install(Shell.Zsh, cmd, p).all { it.startsWith("unchanged ") })
        assertEquals(3, Install.uninstall(Shell.Zsh, p).size)
        assertTrue(!exists(init) && !exists("$home/.zfunc/_rtok"))
        assertEquals("$mine\n", readToString(zshenv))
    }

    @Test fun zshFollowsZdotdir() {
        val lines = Install.install(Shell.Zsh, cmd, p.copy(zdotdir = "$home/zd"))
        assertEquals("wrote $home/zd/.zfunc/_rtok", lines[0])
        assertEquals("wrote $home/zd/.zshenv", lines[2])
    }

    @Test fun installedScriptsAskTheInstalledRtokExceptElvishAndClink() {
        val p = p.copy(localAppData = "$home/AppData/Local")
        for (shell in listOf(Shell.Bash, Shell.Zsh, Shell.Fish, Shell.Powershell)) {
            val path = Install.install(shell, cmd, p)[0].removePrefix("wrote ")
            val body = readToString(path)
            assertTrue("rtok completions ${shell.cliName}" in body, body)
            assertTrue(body.lines().filter { it.isNotEmpty() }.size <= 4, body)
        }
        for (shell in listOf(Shell.Elvish, Shell.Clink)) {
            val body = readToString(Install.install(shell, cmd, p)[0].removePrefix("wrote "))
            assertTrue(body.lines().size > 4, "$shell: $body")
        }
    }

    @Test fun shQuoteKeepsQuotesAndDollarsLiteral() = assertEquals("'/h/it'\\''s \$HOME'", Places.shQuote("/h/it's \$HOME"))

    @Test fun shellComesFromDollarShellOrTheErrorListsThem() {
        assertEquals(Shell.Fish, p.copy(shell = "/usr/local/bin/fish").pick(null))
        assertEquals(Shell.Zsh, p.copy(shell = "/usr/local/bin/fish").pick(Shell.Zsh))
        val err = assertFailsWith<CliFailure> { p.pick(null) }
        assertTrue("bash, zsh, fish, powershell, elvish, clink" in err.message!!)
    }

    @Test fun clinkWithoutLocalAppDataIsRefused() {
        assertTrue("LOCALAPPDATA" in assertFailsWith<CliFailure> { Install.install(Shell.Clink, cmd, p) }.message!!)
    }
}
