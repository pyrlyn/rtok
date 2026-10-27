// Port of src/completions/install.rs `Places`: where each shell looks for per-user completions, from HOME and the
// XDG / ZDOTDIR / LOCALAPPDATA overrides, plus the rc lines and extra files some shells need.
package rtok.completions

import rtok.cli.CliFailure

data class Places(
    val home: String,
    val xdgData: String? = null,
    val xdgConfig: String? = null,
    val zdotdir: String? = null,
    val localAppData: String? = null,
    /** `$SHELL`, for picking a shell when none is named. */
    val shell: String? = null,
    val windows: Boolean = false,
) {
    private val config get() = xdgConfig ?: "$home/.config"
    private val zfunc get() = "${zdotdir ?: home}/.zfunc"

    /** pwsh's `$PROFILE` (CurrentUserCurrentHost); a Documents folder moved elsewhere is not followed. */
    internal val psProfile get() = (if (windows) "$home/Documents/PowerShell" else "$config/powershell") + "/Microsoft.PowerShell_profile.ps1"

    /** The script path and, for shells that need it, the note on how the shell finds it. */
    fun script(shell: Shell): Pair<String, String?> =
        when (shell) {
            Shell.Bash -> "${xdgData ?: "$home/.local/share"}/bash-completion/completions/rtok" to null
            Shell.Zsh -> "$zfunc/_rtok" to null
            Shell.Fish -> "$config/fish/completions/rtok.fish" to null
            Shell.Elvish -> "$config/elvish/lib/rtok.elv" to "elvish loads it when rc.elv has: use rtok"
            Shell.Powershell -> psProfile.substringBeforeLast('/') + "/rtok.ps1" to null
            Shell.Clink -> "${localAppData ?: throw CliFailure("clink needs %LOCALAPPDATA% (Clink runs on Windows)")}/clink/rtok.lua" to null
        }

    /** The rc file that gets one line of ours, and that line; zsh reads `.zshenv` before any `compinit`. */
    internal fun rcLine(
        shell: Shell,
        script: String,
    ): Pair<String, String>? =
        when (shell) {
            Shell.Powershell -> {
                psProfile to ". \"$script\"  # rtok completions"
            }

            Shell.Zsh -> {
                val init = shQuote("$zfunc/$ZSH_INIT")
                "${zdotdir ?: home}/.zshenv" to "[[ -o interactive && -r $init ]] && . $init  # rtok completions"
            }

            else -> {
                null
            }
        }

    /** Files written beside the script: zsh's init, which puts `.zfunc` on `fpath` and registers `_rtok`. */
    internal fun extraFiles(shell: Shell): List<Pair<String, String>> = if (shell == Shell.Zsh) listOf("$zfunc/$ZSH_INIT" to ZSH_INIT_BODY.replace("{dir}", shQuote(zfunc))) else emptyList()

    /** The named shell, else the one `$SHELL` names. */
    fun pick(shell: Shell?): Shell =
        shell ?: this.shell
            ?.substringAfterLast('/')
            ?.substringBefore('.')
            ?.let(Shell::parse)
            ?: throw CliFailure("cannot tell the shell from \$SHELL; name one of: ${Shell.entries.joinToString(", ") { it.cliName }}")

    companion object {
        const val ZSH_INIT = "rtok.zsh"

        /** `compinit -C` trusts an old dump, so the init registers `_rtok` itself on the first prompt. */
        val ZSH_INIT_BODY =
            """
            |# Written by `rtok completions zsh --install`; `--uninstall` removes it and its .zshenv line.
            |fpath=({dir} ${'$'}{fpath:#{dir}})
            |autoload -Uz add-zsh-hook
            |_rtok_compdef() {
            |  add-zsh-hook -d precmd _rtok_compdef
            |  unfunction _rtok_compdef
            |  (( ${'$'}+functions[compdef] )) && { autoload -Uz _rtok; compdef _rtok rtok }
            |}
            |add-zsh-hook precmd _rtok_compdef
            |
            """.trimMargin()

        /** `path` as one POSIX-shell word: single quotes keep `$`, spaces and `"` literal. */
        fun shQuote(path: String) = "'" + path.replace("'", "'\\''") + "'"

        fun fromEnv(): Places {
            fun v(k: String) = rtok.config.envVar(k)?.takeIf { it.isNotEmpty() }
            return Places(
                home = rtok.config.envUserHome() ?: throw CliFailure("no HOME or USERPROFILE"),
                xdgData = v("XDG_DATA_HOME"),
                xdgConfig = v("XDG_CONFIG_HOME"),
                zdotdir = v("ZDOTDIR"),
                localAppData = v("LOCALAPPDATA"),
                shell = rtok.config.envVar("SHELL"),
            )
        }
    }
}
