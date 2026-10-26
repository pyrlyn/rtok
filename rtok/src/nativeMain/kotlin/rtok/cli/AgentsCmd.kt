// Port of src/cli/agents.rs and cli/agents_host.rs (host side): `rtok agents install|uninstall|remove|update|list|info`.
// Not ported yet: `--project`, the
// desktop restart behind `--no-restart` (accepted, nothing to skip yet), and the session/usage/junk/message commands.
package rtok.cli

import kotlinx.serialization.json.JsonArray
import rtok.StatPreview
import rtok.agents.AgentsCommand
import rtok.agents.AgentsFailure
import rtok.agents.AgentsJson
import rtok.agents.AgentsList
import rtok.agents.AgentsOutput
import rtok.agents.Mode
import rtok.agents.Outdated
import rtok.agents.OutdatedSelection
import rtok.agents.SessionsTable
import rtok.config.Config
import rtok.log.watchLoop
import rtok.model.Agents
import rtok.store.Store

private val HOST_ARG = ArgSpec("host", "Host id, or several comma-separated (`opencode,cursor`)")
private val DRY_RUN = OptSpec.Flag("dry-run", "Show what would change; write nothing")
private val STAT = OptSpec.Flag("stat", "With --dry-run: per-file +/- counts instead of the diff")
private val NO_RESTART = OptSpec.Flag("no-restart", "Do not quit and reopen a running desktop app")
private val CLI = OptSpec.Flag("cli", "Only the CLI app")
private val DESKTOP = OptSpec.Flag("desktop", "Only the desktop app")
private val ALL = OptSpec.Flag("all", "Every app of the host (the default)")

/** [body], with a refused request ([AgentsFailure]) turned into a CLI error. */
private fun <T> refusals(body: () -> T): T = runCatching(body).getOrElse { throw if (it is AgentsFailure) CliFailure(it.message.orEmpty()) else it }

/** Runs an agents writer: `--stat` needs `--dry-run`, refusals become CLI errors, a failed reinstall exits 1. */
private fun write(
    inv: Invocation,
    body: () -> AgentsOutput,
) {
    if (inv.flag("stat") && !inv.flag("dry-run")) throw CliFailure("--stat requires --dry-run")
    StatPreview.set(inv.flag("stat"))
    val res = refusals(body)
    inv.io.out(res.out)
    if (res.reinstallFailed) throw CliFailure("a plugin reinstall failed")
}

private fun hostWriter(
    name: String,
    help: String,
    remove: Boolean,
): CommandSpec {
    val flags =
        if (remove) {
            listOf(DRY_RUN, NO_RESTART, OptSpec.Flag("yes", "Also remove what asks first (a linked plugin, an edited copy)"), STAT)
        } else {
            listOf(
                DRY_RUN,
                OptSpec.Flag("yes", "Also install the modules behind --yes (the host's plugin)"),
                OptSpec.Flag("replace", "Replace another install of rtok in the host (Claude: migrate)"),
                OptSpec.Flag("mcp", "Write the MCP entry even when `[setup] mcp = false`"),
                OptSpec.Flag("proxy", "Also point the host at rtok's proxy"),
                OptSpec.Value("mode", "Output modes to set, comma-separated", "MODES"),
                CLI,
                DESKTOP,
                ALL,
                NO_RESTART,
                STAT,
            )
        }
    return CommandSpec(name, help, options = flags, args = listOf(HOST_ARG)) { inv ->
        write(inv) {
            val modes =
                inv
                    .value("mode")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
            val overlay = AgentsCommand.setupFlags(inv.flag("dry-run"), inv.flag("yes"), inv.flag("mcp"), inv.flag("proxy"), modes)
            val mode =
                when {
                    remove -> Mode.Remove
                    inv.flag("replace") -> Mode.Replace
                    else -> Mode.Install
                }
            val cfg = Config.loadWith(inv.configFile, overlay)
            AgentsCommand.install(cfg, inv.requireArg("host"), mode, inv.flag("cli"), inv.flag("desktop"), inv.flag("all"))
        }
    }
}

/** `agents outdated` and `update --check`: the report as a table or JSON; `--exit-code` exits 10 when one is behind. */
private fun outdated(
    inv: Invocation,
    json: Boolean,
    exitCode: Boolean,
) {
    val cfg = inv.loadConfig()
    val hosts = inv.arg("host")?.let { refusals { AgentsCommand.parseHosts(it) } }.orEmpty()
    val rep = refusals { Outdated.report(cfg, OutdatedSelection(hosts, inv.flag("cli"), inv.flag("desktop"), inv.flag("all"))) }
    inv.io.out(if (json) Outdated.json(rep) + "\n" else Outdated.human(rep))
    if (exitCode && rep.outdated.isNotEmpty()) throw CliExit(Outdated.EXIT_OUTDATED)
}

private val OPTIONAL_HOSTS = ArgSpec("host", "Host id(s), comma-separated; omitted = every host in `agents list`", required = false)

private val OUTDATED =
    CommandSpec(
        "outdated",
        "List installed rtok plugins older than this rtok",
        options =
            listOf(
                CLI,
                DESKTOP,
                ALL,
                OptSpec.Flag("json", "JSON instead of the table"),
                OptSpec.Flag("exit-code", "Exit with code 10 when at least one plugin is outdated"),
            ),
        args = listOf(OPTIONAL_HOSTS),
    ) { inv -> outdated(inv, inv.flag("json"), inv.flag("exit-code")) }

private val UPDATE =
    CommandSpec(
        "update",
        "Rewrite rtok's modules in every host it is installed in (or the named ones) for this rtok",
        options =
            listOf(
                DRY_RUN,
                CLI,
                DESKTOP,
                ALL,
                NO_RESTART,
                OptSpec.Flag("force", "Reinstall the plugin even when it is current"),
                OptSpec.Value("source", "Where the plugin comes from: github, local or marketplace", "SOURCE"),
                OptSpec.Flag("check", "List outdated plugins only (same output as `agents outdated`)"),
                STAT,
            ),
        args = listOf(ArgSpec("host", "Host id(s); omitted updates every host with rtok in it", required = false)),
    ) { inv ->
        if (inv.flag("check")) return@CommandSpec outdated(inv, json = false, exitCode = false)
        val source = inv.value("source")
        if (source != null && source !in AgentsCommand.SOURCES) throw CliFailure("--source: expected one of ${AgentsCommand.SOURCES.joinToString(", ")}, got $source")
        write(inv) {
            val cfg = Config.loadWith(inv.configFile, AgentsCommand.setupFlags(dryRun = inv.flag("dry-run"), force = inv.flag("force"), source = source))
            AgentsCommand.update(cfg, inv.arg("host"), inv.flag("cli"), inv.flag("desktop"), inv.flag("all"), warn = inv.io::eprintln)
        }
    }

private val JSON = OptSpec.Flag("json", "One JSON row per host app instead of the blocks")

private val LIST =
    CommandSpec("list", "Every known host and app: where it is, its config files and what of rtok is installed", options = listOf(JSON)) { inv ->
        val cfg = inv.loadConfig()
        inv.io.out(if (inv.flag("json")) AgentsJson.list(cfg) + "\n" else AgentsList.list(cfg))
    }

private val INFO =
    CommandSpec("info", "The `list` blocks of the named host(s)", options = listOf(JSON), args = listOf(HOST_ARG)) { inv ->
        val cfg = inv.loadConfig()
        val hosts = refusals { AgentsCommand.parseHosts(inv.requireArg("host")) }
        inv.io.out(refusals { if (inv.flag("json")) AgentsJson.info(cfg, hosts) + "\n" else AgentsList.info(cfg, hosts) })
    }

private val SESSIONS_ALL = OptSpec.Flag("all", "Also show sessions that have ended", global = true)

/** The Sessions page's rows from the store, as `rtok agents sessions` lists them. */
private fun sessionRows(
    cfg: Config,
    all: Boolean,
    now: Long,
) = Store.open(cfg.core.dbPath).use { Agents.agentSessions(it, cfg.agents.idle, all, now) }

private val SESSIONS_WATCH =
    CommandSpec("watch", "Redraw the sessions table in place as sessions appear, end or spend") { inv ->
        val cfg = inv.loadConfig()
        val all = inv.flag("all")
        val ticker = SessionsTable.Ticker()
        watchLoop(inv.io.tty, write = { text -> runCatching { inv.io.out(text) }.isSuccess.also { inv.io.flush() } }) {
            val now = rtok.log.now()
            runCatching { ticker.tick(sessionRows(cfg, all, now), all, now) }.getOrElse { ticker.keep() }
        }
    }

private val SESSIONS =
    CommandSpec(
        "sessions",
        "What is running in this project: host, provider, model, tokens, start, run time",
        options = listOf(SESSIONS_ALL, OptSpec.Flag("json", "JSON instead of the table")),
        subcommands = listOf(SESSIONS_WATCH),
        runsBare = true,
    ) { inv ->
        val all = inv.flag("all")
        val now = rtok.log.now()
        val rows = sessionRows(inv.loadConfig(), all, now)
        inv.io.out(if (inv.flag("json")) AgentsJson.PRETTY.encodeToString(JsonArray.serializer(), JsonArray(rows.map { it.toJson() })) + "\n" else SessionsTable.render(rows, all, now))
    }

val AGENTS =
    CommandSpec(
        "agents",
        "Install rtok into coding agents and inspect them",
        subcommands =
            listOf(
                hostWriter("install", "Install rtok into a host (hooks, MCP, plugin, proxy as it supports)", remove = false),
                hostWriter("setup", "Deprecated alias of `install`", remove = false),
                hostWriter("uninstall", "Remove rtok from a host (every app of it)", remove = true),
                hostWriter("remove", "Alias of `uninstall`", remove = true),
                UPDATE,
                OUTDATED,
                LIST,
                INFO,
                SESSIONS,
            ),
    )
