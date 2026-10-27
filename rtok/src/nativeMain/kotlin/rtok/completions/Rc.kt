// The rc-file edits and wrapper scripts of src/completions/install.rs.
package rtok.completions

internal object Rc {
    /** The rc file with our line appended; null when it is already there. */
    fun with(
        old: String,
        line: String,
    ): String? {
        if (old.lines().any { it == line }) return null
        val sep = if (old.isEmpty() || old.endsWith('\n')) "" else "\n"
        return "$old$sep$line\n"
    }

    /** The rc file without our line; null when it is not there. Other bytes stay as they were. */
    fun without(
        old: String,
        line: String,
    ): String? {
        if (old.lines().none { it == line }) return null
        return Regex("[^\n]*\n|[^\n]+$")
            .findAll(old)
            .map { it.value }
            .filter { it.trimEnd('\r', '\n') != line }
            .joinToString("")
    }

    /** `wrote` or `unchanged`, then the path; parent directories are created. */
    fun writeIfChanged(
        path: String,
        body: String,
    ): String {
        if (rtok.sys.exists(path) && rtok.sys.readToString(path) == body) return "unchanged $path"
        rtok.sys.parent(path)?.let { rtok.sys.createDirAll(it) }
        rtok.agent.sdk.writeAtomic(path, body.encodeToByteArray())
        return "wrote $path"
    }
}

/** Each wrapper does nothing when `rtok` is not on `PATH`, so an uninstalled binary leaves no prompt error. */
internal object Wrappers {
    private const val STALE = "the installed rtok prints the script, so an upgrade never leaves it stale."
    const val BASH = "# Written by `rtok completions bash --install`: $STALE\nif command -v rtok >/dev/null 2>&1; then eval \"$(command rtok completions bash)\"; fi\n"
    const val ZSH = "#compdef rtok\n# Written by `rtok completions zsh --install`: $STALE\n(( \$+commands[rtok] )) || return 1\neval \"$(command rtok completions zsh)\" && _rtok \"\$@\"\n"
    const val FISH = "# Written by `rtok completions fish --install`: $STALE\ncommand -q rtok; and rtok completions fish | source\n"
    const val POWERSHELL =
        "# Written by `rtok completions powershell --install`: $STALE\n" +
            "if (Get-Command rtok -CommandType Application -ErrorAction SilentlyContinue) { rtok completions powershell | Out-String | Invoke-Expression }\n"
}
