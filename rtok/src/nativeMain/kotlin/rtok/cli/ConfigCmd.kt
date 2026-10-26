// Port of src/cli/config.rs: `rtok config init|path|validate|set` (show/get wait for the model).
package rtok.cli

import rtok.config.*
import rtok.fileLog

private val DRY_RUN = OptSpec.Flag("dry-run", "Print the diff it would write and exit")

/** `print_diff`: a non-empty diff on its own line. */
private fun CliIo.diff(d: String) {
    if (d.isNotEmpty()) println(d)
}

private fun configError(e: ConfigException): Nothing = throw CliFailure(e.message ?: "config error")

private inline fun <T> config(block: () -> T): T =
    try {
        block()
    } catch (e: ConfigException) {
        configError(e)
    }

private val INIT =
    CommandSpec(
        "init",
        "Write the annotated reference file to `<home>/config.toml`",
        options = listOf(OptSpec.Flag("force", "Overwrite an existing file"), DRY_RUN),
    ) { inv ->
        val (path, diff) = config { Config.initMaybe(Config.homeDir(), inv.configFile, inv.flag("force"), inv.flag("dry-run")) }
        inv.io.println(path)
        inv.io.diff(diff)
    }

private val PATH =
    CommandSpec("path", "Print the path of the config file") { inv ->
        inv.io.println(Config.userPath(Config.homeDir(), inv.configFile))
    }

private val VALIDATE =
    CommandSpec(
        "validate",
        "Reject unknown keys, wrong types, and out-of-range values",
        args = listOf(ArgSpec("path", "File to check (else the user config file)", required = false)),
    ) { inv ->
        val home = Config.homeDir()
        val typed = inv.arg("path")
        if (typed == null) config { Config.ensureUserFile(home, inv.configFile) }
        val path = typed ?: Config.userPath(home, inv.configFile)
        val errs = config { Validate.issues(path) }.toMutableList()
        val notes = Validate.pinnedNotes(path)
        val layer = inv.configFile ?: path
        val cfg = runCatching { Layers.load(home, layer, null) }.getOrElse { Config().finish(home) }
        errs += Validate.layeredIssues(Layers.sourced(Layers.figment(home, layer, null)))
        errs += Validate.rulesIssues(cfg.plugins.cmd.rules, cfg.plugins.cmd.rulesDir)
        if (errs.isEmpty()) {
            notes.forEach(inv.io::println)
            inv.io.println("ok $path")
            return@CommandSpec
        }
        val log = cfg.fileLog()
        for (e in errs) {
            inv.io.eprintln(e)
            rtok.log.append(log, "error", "config", "validate", e)
        }
        throw CliExit(ExitCode.FAILURE)
    }

private val SET =
    CommandSpec(
        "set",
        "Edit one key in the user file",
        options = listOf(DRY_RUN),
        args = listOf(ArgSpec("key"), ArgSpec("value")),
    ) { inv ->
        val key = inv.requireArg("key")
        val value = inv.requireArg("value")
        val dry = inv.flag("dry-run")
        val (_, diff) = config { Validate.setWith(Config.homeDir(), inv.configFile, key, value, dry) }
        if (!dry) inv.io.println(value)
        inv.io.diff(diff)
    }

val CONFIG = CommandSpec("config", "Read, check and edit the configuration", subcommands = listOf(INIT, PATH, VALIDATE, SET))
