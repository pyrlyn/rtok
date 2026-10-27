// Port of src/cli/bench.rs: `rtok bench` merges `--tasks/--runs/--dry-run/--timeout/--suite` into `[bench]` only when
// typed, then prints what `rtok.bench.Bench` reports.
package rtok.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.bench.Bench
import rtok.bench.BenchException
import rtok.config.Config

/** The `[bench]` flag layer; null when no flag was typed. */
internal fun benchFlags(
    tasks: String?,
    runs: Long?,
    dryRun: Boolean,
    timeout: Long?,
    suite: String?,
): JsonObject? {
    val bench =
        buildJsonObject {
            tasks?.let { put("tasks", it) }
            runs?.let { put("runs", it) }
            if (dryRun) put("dry_run", true)
            timeout?.let { put("timeout_s", it) }
            suite?.let { put("suite", it) }
        }
    return if (bench.isEmpty()) null else buildJsonObject { put("bench", bench) }
}

private fun Invocation.number(name: String): Long? = value(name)?.let { raw -> raw.toUIntOrNull()?.toLong() ?: throw CliFailure("invalid value '$raw' for '--$name <${name.uppercase()}>': invalid digit found in string", ExitCode.USAGE) }

val BENCH: CommandSpec =
    CommandSpec(
        "bench",
        "A/B benchmark of host configurations",
        options =
            listOf(
                OptSpec.Value("tasks", "Task list TOML", "TASKS"),
                OptSpec.Value("runs", "Repeats per task × config", "RUNS"),
                OptSpec.Flag("dry-run", "Print the schedule and exit"),
                OptSpec.Value("timeout", "Per-run timeout in seconds", "TIMEOUT"),
                OptSpec.Value("suite", "Task suite (`graph` = with/without rtok MCP)", "SUITE"),
            ),
    ) { inv ->
        val flags = benchFlags(inv.value("tasks"), inv.number("runs"), inv.flag("dry-run"), inv.number("timeout"), inv.value("suite"))
        val cfg = Config.loadWith(inv.configFile, flags)
        try {
            inv.io.out(Bench.run(cfg))
        } catch (
            @Suppress("SwallowedException") e: BenchException,
        ) {
            throw CliFailure(e.message ?: "bench failed")
        }
    }
