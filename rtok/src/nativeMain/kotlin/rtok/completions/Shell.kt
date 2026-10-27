// Port of shell-completions' `Shell`: the shells `rtok completions` renders, and the script for one of them.
package rtok.completions

import rtok.cli.CommandSpec

enum class Shell {
    Bash,
    Zsh,
    Fish,
    Powershell,
    Elvish,
    Clink,
    ;

    /** The name accepted on the command line. */
    val cliName: String get() = name.lowercase()

    companion object {
        /** A shell by its command-line name, case-insensitive (clap `ValueEnum::from_str(_, true)`). */
        fun parse(s: String): Shell? = entries.firstOrNull { it.cliName == s.lowercase() }
    }
}

object Completions {
    /** The completion script for [shell] over [cmd]'s tree. */
    fun generate(
        shell: Shell,
        cmd: CommandSpec,
    ): String {
        val tree = CompTree.of(cmd)
        return when (shell) {
            Shell.Bash -> BashScript.render(tree)
            Shell.Zsh -> ZshScript.render(tree)
            Shell.Fish -> FishScript.render(tree)
            Shell.Powershell -> PowershellScript.render(tree)
            Shell.Elvish -> ElvishScript.render(tree)
            Shell.Clink -> ClinkScript.render(tree)
        }
    }

    /** Help text on one line: a completer shows a single line per candidate. */
    internal fun firstLine(s: String) = s.replace('\n', ' ')
}
