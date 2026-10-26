// Port of src/agents/mod.rs install engine: `Request`, `resolve`, `run`, `would_change`, `apply_all`, `carry_flags`,
// `installed_hosts`, `list`, `list_ids`, `info`, and locate.rs `absent`/`not_found`. The hook-client link (T357) and
// the host progress bar are not ported yet; `run` is otherwise Rust's order: refuse unknown/absent hosts, back up every
// file up front, apply, read back, keep the copies only when something changed.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.backup
import rtok.agent.sdk.pruneBackups
import rtok.config.Config
import rtok.sys.removeFile

/** What `rtok agents install|update|remove` was asked to do. */
data class Request(
    val hosts: List<String>,
    val mode: Mode,
    val cli: Boolean = false,
    val desktop: Boolean = false,
    val all: Boolean = false,
) {
    /** Remove reaches every variant; otherwise `--cli/--desktop/--all` pick. */
    fun wants(kind: Kind) = mode == Mode.Remove || Hosts.wants(kind, cli, desktop, all)
}

/** A refused request; the CLI prints [message] as an error. */
class AgentsFailure(
    message: String,
) : RuntimeException(message)

object AgentsRun {
    /** Every host named, or the first unknown name: checked before any backup is taken. */
    fun resolve(
        hosts: List<String>,
        agent: (String) -> Agent? = Agents::agent,
    ): List<Agent> {
        if (hosts.isEmpty()) throw AgentsFailure("no host given")
        return hosts.map { h -> agent(h)?.takeIf { Hosts.host(h) != null } ?: throw AgentsFailure("unknown host: $h") }
    }

    /** Hosts with no wanted variant on this machine. */
    fun absent(
        cfg: Config,
        agents: List<Agent>,
        want: (Kind) -> Boolean,
        present: (Config, Agent, Variant) -> Boolean = { c, a, v -> Locate.present(c, a.id, v) },
    ): List<String> = agents.filter { a -> variants(a).none { want(it.kind) && present(cfg, a, it) } }.map { it.id }

    /** The error for hosts rtok cannot find, naming the key that tells it where they are. */
    fun notFound(hosts: List<String>): AgentsFailure =
        AgentsFailure(
            "${hosts.joinToString(", ")} is not installed (not in its app folders or on PATH); if it is, set its path: " +
                hosts.joinToString(", ") { "`rtok config set ${Locate.key(it)} <path>`" },
        )

    internal fun variants(a: Agent) = Hosts.host(a.id)?.variants.orEmpty()

    /** True when the `rtok` the configs spawn resolves (unix: a bare `rtok` on PATH). */
    fun rtokSpawns(): Boolean = HostBins.findOnPath("rtok") != null

    /**
     * Back up, then install into (or remove from) every wanted variant of every host, one block each. The copies are
     * taken up front so one generation holds the host as it was; the installers therefore take none of their own. A run
     * that wrote nothing deletes its copies: `already installed` leaves the directory as it found it.
     */
    fun run(
        cfg: Config,
        req: Request,
        env: Env = Env(),
    ): String {
        val agents = resolve(req.hosts, env.agent)
        if (req.mode != Mode.Remove) {
            val missing = absent(cfg, agents, req::wants, env.present)
            if (missing.isNotEmpty()) throw notFound(missing)
        }
        val out = StringBuilder()
        if (req.mode != Mode.Remove && !env.spawns()) {
            out.append("warning: rtok is not on PATH; the hooks and MCP entries spawn `rtok` by name and will fail until it is\n")
        }
        val taken = mutableListOf<String>()
        var run = cfg
        if (!cfg.setup.dryRun && cfg.setup.backup) {
            agents
                .flatMap { a -> variants(a).filter { req.wants(it.kind) }.flatMap { a.files(cfg, it.kind) } }
                .distinct()
                .forEach { path -> backup(path, 0)?.let(taken::add) }
            run = cfg.copy(setup = cfg.setup.copy(backup = false))
        }
        val (blocks, changed) = applyAll(run, req, agents, req::wants, env)
        if (changed) {
            taken.forEach { bak ->
                pruneBackups(bak, cfg.setup.backupFiles.toInt())
                out.append("backup $bak\n")
            }
        } else {
            taken.forEach { runCatching { removeFile(it) } }
        }
        return out.append(blocks).toString()
    }

    /** Dry-run [agent]'s plan on a throwaway config (T141): true when it would touch a file. */
    fun wouldChange(
        cfg: Config,
        agent: Agent,
        req: Request,
        env: Env = Env(),
    ): Boolean = applyAll(cfg.copy(setup = cfg.setup.copy(dryRun = true, backup = false)), req, listOf(agent), req::wants, env).second

    /** The blocks of every wanted variant, and whether any step changed a file. */
    fun applyAll(
        cfg: Config,
        req: Request,
        agents: List<Agent>,
        want: (Kind) -> Boolean,
        env: Env = Env(),
    ): Pair<String, Boolean> {
        val out = StringBuilder()
        var changed = false
        for (a in agents) {
            val shared = Hosts.host(a.id)?.shared == true
            var done: String? = null
            val wanted = variants(a).filter { want(it.kind) }
            for (v in wanted) {
                val step = applyOne(cfg, req, a, v, done.takeIf { shared }, env)
                out.append(step.text)
                if (step.applied) done = v.name
                changed = changed || step.changed
            }
            if (wanted.isEmpty()) out.append("skip ${a.id}: not selected\n")
        }
        return out.toString() to changed
    }

    private class Step(
        val text: String,
        val applied: Boolean = false,
        val changed: Boolean = false,
    )

    /** One variant: not found, same files as [sharedWith], nothing to update, or applied and read back. */
    private fun applyOne(
        cfg: Config,
        req: Request,
        a: Agent,
        v: Variant,
        sharedWith: String?,
        env: Env,
    ): Step {
        val have = if (req.mode == Mode.Update) Modules.installed(a, v.kind, cfg) else null
        val skip =
            when {
                req.mode != Mode.Remove && !env.present(cfg, a, v) -> Outcome.NotFound
                sharedWith != null -> Outcome.Shared(sharedWith)
                have != null && have.isEmpty() -> Outcome.NotInstalled
                else -> null
            }
        if (skip != null) return Step(env.block(a, v, cfg, skip))
        val vcfg = if (have != null) carryFlags(cfg, have) else cfg
        val reports = a.apply(vcfg, v.kind, req.mode)
        val text = StringBuilder(env.block(a, v, vcfg, Outcome.Applied(req.mode, reports)))
        if ((req.mode == Mode.Install || req.mode == Mode.Update) && !vcfg.setup.dryRun) {
            Modules.missing(a, v.kind, vcfg).forEach { text.append("  warning: $it did not read back as installed\n") }
        }
        return Step(text.toString(), applied = true, changed = reports.any { it != NO_CHANGES })
    }

    /** `agents update`'s config (T242.2): a proxy read back keeps `--proxy`, an installed plugin keeps `--yes`. */
    fun carryFlags(
        cfg: Config,
        have: List<String>,
    ): Config = cfg.copy(setup = cfg.setup.copy(proxy = cfg.setup.proxy || "proxy" in have, yes = cfg.setup.yes || "plugin" in have))

    /** The machine the engine reads: overridden by tests. */
    class Env(
        val agent: (String) -> Agent? = Agents::agent,
        val present: (Config, Agent, Variant) -> Boolean = { c, a, v -> Locate.present(c, a.id, v) },
        val spawns: () -> Boolean = ::rtokSpawns,
        val block: (Agent, Variant, Config, Outcome) -> String = { a, v, c, o -> Blocks.block(a, v, c, o) },
    )
}

/** `rtok agents list|info` and `update`'s default host set: read-only. */
object AgentsList {
    /** `agents update` with no host: every host with an rtok module in a present variant, in registry order. */
    fun installedHosts(
        cfg: Config,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): List<String> =
        Hosts.ALL
            .filter { h ->
                val a = env.agent(h.id) ?: return@filter false
                h.variants.any { v -> env.present(cfg, a, v) && Modules.installed(a, v.kind, cfg).isNotEmpty() }
            }.map { it.id }

    /** `rtok agents list`: every ported host × variant as a block, nothing written. */
    fun list(
        cfg: Config,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): String = listIds(cfg, Hosts.ALL.map { it.id }, env)

    /** The blocks of [list], only for [ids]. */
    fun listIds(
        cfg: Config,
        ids: List<String>,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): String =
        ids.joinToString("") { id ->
            val a = env.agent(id) ?: return@joinToString ""
            AgentsRun.variants(a).joinToString("") { v -> env.block(a, v, cfg, if (env.present(cfg, a, v)) Outcome.Listed else Outcome.NotFound) }
        }

    /** `rtok agents info <host>`: [list] for the named hosts. */
    fun info(
        cfg: Config,
        hosts: List<String>,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): String = listIds(cfg, AgentsRun.resolve(hosts, env.agent).map { it.id }, env)
}
