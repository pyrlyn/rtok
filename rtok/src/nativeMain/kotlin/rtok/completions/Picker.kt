// Port of src/completions/picker.rs (T408): `--list` and the picker's plan over [Install.status].
package rtok.completions

import rtok.cli.CommandSpec

object Picker {
    private const val NAME_W = 10
    private const val STATE_W = 3

    /** `--list`: `name`, `yes`/`no`, path (`-` where the shell has no per-user location). */
    fun list(places: Places): List<String> = Install.status(places).map { s -> "${s.shell.cliName.padEnd(NAME_W)} ${(if (s.installed) "yes" else "no").padEnd(STATE_W)} ${s.path ?: "-"}" }

    fun plan(
        installed: List<Shell>,
        chosen: List<Shell>,
    ) = Changes(chosen.filter { it !in installed }, installed.filter { it !in chosen })

    /** Ask which shells should have completions (null = cancelled), then apply the difference. */
    fun pick(
        places: Places,
        cmd: CommandSpec,
        ask: (List<Status>) -> List<Shell>?,
    ): List<String> {
        val rows = Install.status(places).filter { it.path != null }
        val chosen = ask(rows) ?: return listOf("cancelled, nothing changed")
        val changes = plan(rows.filter { it.installed }.map { it.shell }, chosen)
        val lines = changes.uninstall.flatMap { Install.uninstall(it, places) } + changes.install.flatMap { Install.install(it, cmd, places) }
        return lines.ifEmpty { listOf("no changes") }
    }
}
