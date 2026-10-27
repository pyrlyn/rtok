// Port of src/cli/demon.rs: `rtok demon start|stop|restart|status|kill` over src/demon.rs. Every verb takes optional
// services; with none they act on what is already up, falling back to `[demon] services`; `status` shows them all.
package rtok.cli

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import rtok.demon.DemonException
import rtok.demon.Lifecycle
import rtok.demon.Row
import rtok.demon.Service
import rtok.demon.rows
import rtok.demon.supervise
import rtok.demon.table

private val DEMON_JSON = Json { prettyPrint = true }

/** The service positionals, validated like clap's `ValueEnum`. */
private fun Invocation.services(): List<Service> = args("service").map(::serviceArg)

private fun serviceArg(raw: String): Service = Service.of(raw) ?: throw CliFailure("invalid value '$raw' for '[SERVICE]...'\n  [possible values: ${Service.names.joinToString(", ")}]", ExitCode.USAGE)

private fun Invocation.lifecycle() = Lifecycle(loadConfig(), configFile, io::println, io::eprintln)

/** Runs a demon verb; a [DemonException] is an ordinary failure. */
private fun Invocation.demon(body: Lifecycle.() -> Unit) =
    try {
        lifecycle().body()
    } catch (
        @Suppress("SwallowedException") e: DemonException,
    ) {
        throw CliFailure(e.message ?: "demon failed")
    }

private val SERVICES = ArgSpec("service", "Services (${Service.names.joinToString(", ")})", required = false, variadic = true)

private fun verb(
    name: String,
    help: String,
    body: Invocation.() -> Unit,
) = CommandSpec(name, help, args = listOf(SERVICES)) { it.body() }

val DEMON: CommandSpec =
    CommandSpec(
        "demon",
        "Keep rtok's long-running surfaces up",
        subcommands =
            listOf(
                verb("start", "Detach a supervisor that restarts the service whenever it dies") { demon { start(services()) } },
                verb("stop", "Ask the supervisor and its child to exit") { demon { stop(services(), false) } },
                verb("restart", "Stop, then start") { demon { restart(services()) } },
                CommandSpec("upgrade", "Stop live surfaces, replace the binary, start the same set") { it.demon { upgrade() } },
                CommandSpec(
                    "status",
                    "State, pids, uptime, restarts and log path; every service when none is named",
                    options = listOf(OptSpec.Flag("json", "JSON instead of the table")),
                    args = listOf(SERVICES),
                ) { inv ->
                    val cfg = inv.loadConfig()
                    val rows = Lifecycle(cfg, inv.configFile).store.rows(inv.services(), "${cfg.proxy.bind}:${cfg.proxy.port}")
                    inv.io.out(if (inv.flag("json")) DEMON_JSON.encodeToString(ListSerializer(Row.serializer()), rows) + "\n" else table(rows))
                },
                verb("kill", "SIGKILL instead of SIGTERM, and drop the state file") { demon { stop(services(), true) } },
                CommandSpec(
                    "supervise",
                    "The detached half; `demon start` runs this, you do not",
                    args = listOf(ArgSpec("service", "Service")),
                ) { inv -> inv.demon { supervise(serviceArg(inv.requireArg("service"))) } },
            ),
    )
