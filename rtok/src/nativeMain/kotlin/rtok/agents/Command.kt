// Port of src/agents/command.rs host install side (`parse_hosts`, `install`, `update`, `apply_hosts`) and
// cli/agents_host.rs `setup_flags`. Not ported yet: the desktop restart (restart.rs, T141), asking for a missing
// host's path on a terminal (locate.rs `ask_missing`, T966). `--project` is PostCreate.kt.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config

/** What a writer printed to stdout, and whether a plugin reinstall failed. */
data class AgentsOutput(
    val out: String,
) {
    /** T279: a Claude reinstall that removed the old plugin and failed to install the new one must not exit 0. */
    val reinstallFailed: Boolean get() = Claude.REINSTALL_FAILED in out
}

object AgentsCommand {
    /** The `--source` values `agents update` takes (T279). */
    val SOURCES = listOf("github", "local", "marketplace")

    /** Comma-separated host ids; empty input is refused before any backup is taken. */
    fun parseHosts(host: String): List<String> =
        host
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .ifEmpty { throw AgentsFailure("unknown host: $host") }

    /** The `[setup]` overlay the flags given turn into; null when none was given. */
    fun setupFlags(
        dryRun: Boolean = false,
        yes: Boolean = false,
        mcp: Boolean = false,
        proxy: Boolean = false,
        modes: List<String> = emptyList(),
        force: Boolean = false,
        source: String? = null,
    ): JsonObject? {
        val setup =
            buildMap {
                if (dryRun) put("dry_run", JsonPrimitive(true))
                if (yes) put("yes", JsonPrimitive(true))
                if (mcp) put("mcp", JsonPrimitive(true))
                if (proxy) put("proxy", JsonPrimitive(true))
                if (modes.isNotEmpty()) put("modes", JsonArray(modes.map(::JsonPrimitive)))
                if (force) put("force", JsonPrimitive(true))
                source?.let { put("source", JsonPrimitive(it)) }
            }
        return if (setup.isEmpty()) null else JsonObject(mapOf("setup" to JsonObject(setup)))
    }

    /** `rtok agents install|uninstall`: remove reaches every variant, `--replace` migrates (Claude). */
    fun install(
        cfg: Config,
        host: String,
        mode: Mode,
        cli: Boolean,
        desktop: Boolean,
        all: Boolean,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): AgentsOutput = applyHosts(cfg, Request(parseHosts(host), mode, cli, desktop, all || mode == Mode.Remove), env) {}

    /** T289.3 `--project`: the post-create entries of the checkout around [cwd] (a linked worktree gets its own). */
    fun installProject(
        cfg: Config,
        host: String,
        remove: Boolean,
        cwd: String = rtok.sys.cwd(),
    ): AgentsOutput {
        val hosts = parseHosts(host)
        val root =
            rtok.config.ProjectRoot
                .find(cwd)
                ?.checkout ?: throw AgentsFailure("--project: run it inside the project's git repository")
        return AgentsOutput(PostCreate.run(cfg, hosts, remove, root))
    }

    /** `rtok agents update`: the named hosts, else every host with rtok in it. */
    fun update(
        cfg: Config,
        host: String?,
        cli: Boolean,
        desktop: Boolean,
        all: Boolean,
        env: AgentsRun.Env = AgentsRun.Env(),
        warn: (String) -> Unit = {},
    ): AgentsOutput {
        val hosts = host?.let(::parseHosts) ?: AgentsList.installedHosts(cfg, env)
        if (hosts.isEmpty()) return AgentsOutput("nothing to update: rtok is not installed in any host (rtok agents install <host>)\n")
        return applyHosts(cfg, Request(hosts, Mode.Update, cli, desktop, all), env, warn)
    }

    /** For `update` the key that would point rtok at a missing host is said to [warn] before the run refuses it. */
    private fun applyHosts(
        cfg: Config,
        req: Request,
        env: AgentsRun.Env,
        warn: (String) -> Unit,
    ): AgentsOutput {
        if (req.mode == Mode.Update) {
            val absent = AgentsRun.absent(cfg, AgentsRun.resolve(req.hosts, env.agent), req::wants, env.present)
            if (absent.isNotEmpty()) warn("warning: ${AgentsRun.notFound(absent).message}")
        }
        return AgentsOutput(AgentsRun.run(cfg, req, env))
    }
}
