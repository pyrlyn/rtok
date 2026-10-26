// Port of src/cli/run_cmd.rs: `rtok run` and `rtok filter`.
package rtok.cli

import rtok.config.Config
import rtok.plugins.cmd.Filter
import rtok.plugins.cmd.emitFiltered

private val NO_REF = OptSpec.Flag("no-ref", "Never append the `[rtok <id> · N lines]` pointer")

/** The config with the trailer turned off when `--no-ref` is given. */
private fun Invocation.cmdConfig(): Config {
    val cfg = loadConfig()
    return if (flag("no-ref")) cfg.copy(plugins = cfg.plugins.copy(cmd = cfg.plugins.cmd.copy(trailer = false))) else cfg
}

val RUN = CommandSpec(
    "run", "Run a command and print its filtered output (exit code preserved)",
    options = listOf(OptSpec.Value("agent", "Sub-agent id; scopes dedup to that agent's window", "ID"), NO_REF),
    args = listOf(ArgSpec("command", "One shell snippet, or words to quote", variadic = true)),
) { inv ->
    val code = rtok.plugins.cmd.run(inv.cmdConfig(), inv.args("command"), inv.value("agent"))
    if (code != 0) throw CliExit(code)
}

val FILTER = CommandSpec(
    "filter", "Filter text from stdin without executing",
    options = listOf(
        OptSpec.Flag("stdin", "Read from stdin (the default; kept for Rust compatibility)"),
        OptSpec.Value("cmd", "The command the text came from (else `[filter] cmd`)", "CMD"),
        OptSpec.Flag("archive", "Archive and record like `rtok run`"),
        NO_REF,
        OptSpec.Value("exit", "Exit code the command had", "CODE"),
    ),
) { inv ->
    val cfg = inv.cmdConfig()
    val hint = inv.value("cmd") ?: cfg.filter.cmd
    val bytes = inv.io.readStdin()
    if (inv.flag("archive")) {
        val exit = inv.value("exit")?.toIntOrNull() ?: 0
        emitFiltered(cfg, hint.split(' ', '\t').filter { it.isNotEmpty() }, bytes, exit, null)
    } else inv.io.out(Filter.runWithStore(cfg, hint, bytes.decodeToString()))
}
