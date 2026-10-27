// Port of src/completions/install.rs and picker.rs (T408, T416): per-shell status, `--install` / `--uninstall`
// (the script, its extra files and one rc line), their `--dry-run` previews, `--list`, and the picker's plan.
package rtok.completions

import rtok.cli.CommandSpec
import rtok.sys.Preview

/** One shell's completions as the per-user files hold them now; [path] is null where the shell has no place here. */
data class Status(
    val shell: Shell,
    val path: String?,
    val installed: Boolean,
)

/** What a confirmed picker selection asks for, relative to what is installed now. */
data class Changes(
    val install: List<Shell>,
    val uninstall: List<Shell>,
)

object Install {
    fun status(places: Places): List<Status> =
        Shell.entries.map { shell ->
            val path = runCatching { places.script(shell).first }.getOrNull()
            Status(shell, path, path != null && rtok.sys.exists(path))
        }

    /** The installed script: a wrapper that asks the installed rtok, except elvish and clink, which get the script. */
    private fun scriptBody(
        shell: Shell,
        cmd: CommandSpec,
    ): String =
        when (shell) {
            Shell.Bash -> Wrappers.BASH
            Shell.Zsh -> Wrappers.ZSH
            Shell.Fish -> Wrappers.FISH
            Shell.Powershell -> Wrappers.POWERSHELL
            else -> Completions.generate(shell, cmd)
        }

    private fun files(
        shell: Shell,
        cmd: CommandSpec,
        places: Places,
    ) = listOf(places.script(shell).first to scriptBody(shell, cmd)) + places.extraFiles(shell)

    fun previewInstall(
        shell: Shell,
        cmd: CommandSpec,
        places: Places,
    ): Preview {
        val p = Preview()
        val files = files(shell, cmd, places)
        files.forEach { (path, body) -> p.edit(path, read(path), body) }
        places.rcLine(shell, files[0].first)?.let { (rc, line) ->
            val old = read(rc)
            Rc.with(old, line)?.let { p.edit(rc, old, it) }
        }
        return p
    }

    fun previewUninstall(
        shell: Shell,
        places: Places,
    ): Preview {
        val script = places.script(shell).first
        val p = Preview()
        owned(shell, places, script).forEach { path -> rtok.sys.fileLen(path)?.let { p.remove(path, it, 1) } }
        places.rcLine(shell, script)?.let { (rc, line) ->
            val old = read(rc)
            Rc.without(old, line)?.let { p.edit(rc, old, it) }
        }
        return p
    }

    /** Write the script, its extra files and the rc line; one report line per file. */
    fun install(
        shell: Shell,
        cmd: CommandSpec,
        places: Places,
    ): List<String> {
        val note = places.script(shell).second
        val files = files(shell, cmd, places)
        val lines = files.map { (path, body) -> Rc.writeIfChanged(path, body) }.toMutableList()
        places.rcLine(shell, files[0].first)?.let { (rc, line) ->
            lines += Rc.with(read(rc), line)?.let { Rc.writeIfChanged(rc, it) } ?: "unchanged $rc"
        }
        return lines + listOfNotNull(note)
    }

    private fun owned(
        shell: Shell,
        places: Places,
        script: String,
    ) = listOf(script) + places.extraFiles(shell).map { it.first }

    /** Remove exactly what [install] wrote. */
    fun uninstall(
        shell: Shell,
        places: Places,
    ): List<String> {
        val script = places.script(shell).first
        val lines = mutableListOf<String>()
        owned(shell, places, script).forEachIndexed { i, path ->
            if (rtok.sys.exists(path)) {
                rtok.sys.removeFile(path)
                lines += "removed $path"
            } else if (i == 0) {
                lines += "absent $path"
            }
        }
        places.rcLine(shell, script)?.let { (rc, line) ->
            Rc.without(read(rc), line)?.let {
                rtok.agent.sdk.writeAtomic(rc, it.encodeToByteArray())
                lines += "updated $rc"
            }
        }
        return lines
    }

    private fun read(path: String) = if (rtok.sys.exists(path)) rtok.sys.readToString(path) else ""
}
