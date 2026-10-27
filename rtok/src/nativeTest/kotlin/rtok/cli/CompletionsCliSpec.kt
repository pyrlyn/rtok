// Port of tests/completions.rs (T53.2, T416.4) and the trycmd cases completions-list, -no-shell,
// -dry-run-needs-install and -install-dry-run. HOME is the temp home and every XDG/ZDOTDIR/LOCALAPPDATA/SHELL
// override is unset, so nothing outside it is read or written.
package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import rtok.completions.Shell
import rtok.config.envVar
import rtok.sys.exists
import rtok.sys.listDirOrEmpty

private val OVERRIDES = listOf("HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "ZDOTDIR", "LOCALAPPDATA", "SHELL")

private fun <T> withCompletionsHome(
    tag: String,
    body: (String) -> T,
): T =
    withHome(tag) { home ->
        val saved = OVERRIDES.associateWith { envVar(it) }
        OVERRIDES.forEach { platform.posix.unsetenv(it) }
        platform.posix.setenv("HOME", home, 1)
        try {
            body(home)
        } finally {
            saved.forEach { (k, v) -> if (v == null) platform.posix.unsetenv(k) else platform.posix.setenv(k, v, 1) }
        }
    }

private fun okOut(vararg args: String): String {
    val r = cli(*args)
    r.err shouldBe ""
    r.code shouldBe 0
    return r.out
}

class CompletionsCliSpec :
    FunSpec({
        tags(Cli)
        test("every shell renders non-empty completions") {
            for (shell in Shell.entries) okOut("completions", shell.cliName) shouldContain "rtok"
        }
        test("an unknown shell is refused") {
            cli("completions", "tcsh").code shouldBe 2
        }
        test("--list prints a line per shell with state and path") {
            withCompletionsHome("completions-list") { home ->
                okOut("completions", "--list") shouldBe
                    """
                    |bash       no  $home/.local/share/bash-completion/completions/rtok
                    |zsh        no  $home/.zfunc/_rtok
                    |fish       no  $home/.config/fish/completions/rtok.fish
                    |powershell no  $home/.config/powershell/rtok.ps1
                    |elvish     no  $home/.config/elvish/lib/rtok.elv
                    |clink      no  -
                    |
                    """.trimMargin()
            }
        }
        test("no shell and no terminal is refused") {
            withCompletionsHome("completions-no-shell") {
                val r = cli("completions")
                r.code shouldBe 1
                r.err shouldBe "Error: no shell named and no terminal for the picker; run `rtok completions <shell>` to print a script, `rtok completions --install` to write one, or `--list` for the state\n"
            }
        }
        test("--dry-run needs --install or --uninstall") {
            withCompletionsHome("completions-dry-run-needs-install") {
                val r = cli("completions", "bash", "--dry-run")
                r.code shouldBe 2
                r.out shouldBe ""
                r.err shouldContain "the following required arguments were not provided:\n  <--install|--uninstall>"
            }
        }
        test("--dry-run previews install and uninstall and writes nothing (T416.4)") {
            withCompletionsHome("completions-dry-run") { home ->
                val before = listDirOrEmpty(home).size
                val text = okOut("completions", "bash", "--install", "--dry-run")
                text shouldContain "+++ b/"
                text shouldContain "bash-completion/completions/rtok"
                val ps = okOut("completions", "powershell", "--install", "--dry-run")
                ps shouldContain "Microsoft.PowerShell_profile.ps1"
                ps shouldContain "# rtok completions"
                listDirOrEmpty(home).size shouldBe before
                okOut("completions", "bash", "--install")
                val script = "$home/.local/share/bash-completion/completions/rtok"
                val un = okOut("completions", "bash", "--uninstall", "--dry-run")
                un shouldContain "- "
                un shouldContain "1 file"
                exists(script) shouldBe true
                okOut("completions", "bash", "--uninstall").trim() shouldBe "removed $script"
            }
        }
    })
