// Port of src/cli/completions.rs: `rtok completions [SHELL]` prints a script; `--install` / `--uninstall` write or
// remove the per-user files (`--dry-run` shows the diff); `--list` prints each shell's state.
package rtok.cli

import rtok.completions.Completions
import rtok.completions.Install
import rtok.completions.Picker
import rtok.completions.Places
import rtok.completions.Shell
import rtok.completions.ShellSelect
import rtok.sys.PreviewMode

private fun parseShell(raw: String): Shell = Shell.parse(raw) ?: throw CliFailure("invalid value '$raw' for '[SHELL]'\n  [possible values: ${Shell.entries.joinToString(", ") { it.cliName }}]", ExitCode.USAGE)

private fun Invocation.checkCompletionArgs(shellGiven: Boolean) {
    conflict("install", "uninstall")
    conflict("install", "list")
    conflict("uninstall", "list")
    if (flag("list") && shellGiven) throw CliFailure("the argument '--list' cannot be used with '[SHELL]'", ExitCode.USAGE)
    if (flag("dry-run") && !flag("install") && !flag("uninstall")) {
        throw CliFailure("the following required arguments were not provided:\n  <--install|--uninstall>", ExitCode.USAGE)
    }
}

private fun Invocation.installLines(shell: Shell?): List<String> {
    val places = Places.fromEnv()
    val picked = places.pick(shell)
    val install = flag("install")
    if (!flag("dry-run")) return if (install) Install.install(picked, RTOK, places) else Install.uninstall(picked, places)
    val preview = if (install) Install.previewInstall(picked, RTOK, places) else Install.previewUninstall(picked, places)
    val text = preview.render(PreviewMode.Diff)
    return listOf(if (text.isEmpty()) "nothing to change" else text.trimEnd())
}

val COMPLETIONS: CommandSpec =
    CommandSpec(
        "completions",
        "Print, install or remove the shell completion script",
        options =
            listOf(
                OptSpec.Flag("install", "Write the script to the shell's per-user completions directory"),
                OptSpec.Flag("uninstall", "Remove what `--install` wrote"),
                OptSpec.Flag("list", "Print each shell, whether its completions are installed (yes/no) and the file"),
                OptSpec.Flag("dry-run", "With `--install`/`--uninstall`: show the file diff and write nothing"),
            ),
        args = listOf(ArgSpec("shell", "Shell to complete for (with `--install`/`--uninstall`: default `\$SHELL`)", required = false)),
    ) { inv ->
        val raw = inv.arg("shell")
        inv.checkCompletionArgs(raw != null)
        val shell = raw?.let(::parseShell)
        val lines =
            when {
                inv.flag("list") -> {
                    Picker.list(Places.fromEnv())
                }

                inv.flag("install") || inv.flag("uninstall") -> {
                    inv.installLines(shell)
                }

                shell != null -> {
                    inv.io.out(Completions.generate(shell, RTOK))
                    emptyList()
                }

                // A bare call opens the picker on a terminal; without one it is refused.
                tui.isInteractive() -> {
                    Picker.pick(Places.fromEnv(), RTOK, ShellSelect::ask)
                }

                else -> {
                    throw CliFailure(
                        "no shell named and no terminal for the picker; run `rtok completions <shell>` to print a script, " +
                            "`rtok completions --install` to write one, or `--list` for the state",
                    )
                }
            }
        lines.forEach(inv.io::println)
    }
