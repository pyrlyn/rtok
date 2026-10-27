// Port of src/agents/restart.rs `run` / `with_restart` (T141, T434, T629): quit a running desktop app before the write
// when the write would change that host, write, then reopen it. A CLI-only host is never killed, only reminded. Fails
// open: a quit or reopen failure is a warning, never a command failure.
package rtok.agents

import rtok.appendLog
import rtok.config.Config
import kotlin.time.TimeSource

object RestartFlow {
    /** How long a quit app gets to exit: Electron apps often need well over 5 s (T434). */
    const val QUIT_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 100L

    /** `agents install|uninstall|update` with the restart around [write]; [noRestart] skips it. */
    fun run(
        cfg: Config,
        req: Request,
        noRestart: Boolean,
        env: AgentsRun.Env,
        warn: (String) -> Unit,
        write: (Config) -> String,
    ): String =
        withRestart(
            cfg,
            req,
            noRestart,
            env.procs,
            { c, a -> AgentsRun.wouldChange(c, a, req, env) },
            write,
            { msg -> warnLog(cfg, msg, warn) },
            QUIT_TIMEOUT_MS,
            env.agent,
        )

    private fun warnLog(
        cfg: Config,
        msg: String,
        warn: (String) -> Unit,
    ) {
        warn("warning: $msg")
        runCatching { appendLog(cfg, "warn", "agents", "restart", msg) }
    }

    /** Poll [Procs.appRunning] until it goes false or [timeoutMs] elapses; true if it exited. */
    private fun waitUntilNotRunning(
        procs: Procs,
        name: String,
        timeoutMs: Long,
    ): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (procs.appRunning(name)) {
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) return false
            platform.posix.usleep((POLL_MS * US_PER_MS).toUInt())
        }
        return true
    }

    private class Planned(
        val host: String,
        val target: Pair<String, String>?,
        val bin: String?,
        val changed: Boolean,
    )

    /** Test seam for [run]: [procs], [wouldChange], [write] and [warn] are injected. */
    internal fun withRestart(
        cfg: Config,
        req: Request,
        noRestart: Boolean,
        procs: Procs,
        wouldChange: (Config, Agent) -> Boolean,
        write: (Config) -> String,
        warn: (String) -> Unit,
        quitTimeoutMs: Long = QUIT_TIMEOUT_MS,
        agent: (String) -> Agent? = Agents::agent,
    ): String {
        val out = StringBuilder()
        val toReopen = mutableListOf<Pair<String, String>>()
        if (!noRestart) {
            // Plan every host before closing any: a failing `wouldChange` throws, and an app closed by then would stay
            // closed (T629).
            val plan = AgentsRun.resolve(req.hosts, agent).mapNotNull { plan(cfg, it, procs, wouldChange) }
            for (p in plan) {
                if (p.target != null && p.changed) close(cfg, p.host, p.target, procs, quitTimeoutMs, warn, out, toReopen)
                if (p.changed && p.bin != null) out.append("${p.host}: restart your ${p.bin} session to load the new config\n")
            }
        }
        // Always reopen what was quit, even when the write fails, before the write's error propagates (T141 review).
        val written = runCatching { write(cfg) }
        for ((name, path) in toReopen) {
            runCatching { procs.open(name, path) }.exceptionOrNull()?.let { warn("could not reopen $name: ${it.message}; open it manually") }
        }
        return out.append(written.getOrThrow()).toString()
    }

    /** Quit one running app ([target] name and path), queueing it for the reopen once it has exited. */
    private fun close(
        cfg: Config,
        host: String,
        target: Pair<String, String>,
        procs: Procs,
        quitTimeoutMs: Long,
        warn: (String) -> Unit,
        out: StringBuilder,
        toReopen: MutableList<Pair<String, String>>,
    ) {
        val name = target.first
        if (cfg.setup.dryRun) {
            out.append("$host: would close/reopen $name\n")
            return
        }
        val quit = runCatching { procs.quit(name) }.exceptionOrNull()
        when {
            quit != null -> warn("could not quit $name: ${quit.message}; restart it manually to load the new config")
            waitUntilNotRunning(procs, name, quitTimeoutMs) -> toReopen += target
            else -> warn("$name did not quit within ${quitTimeoutMs / MS_PER_S} s; restart it manually to load the new config")
        }
    }

    /** One host's plan, or null when nothing of it runs (no dry-run then, T141 review). */
    private fun plan(
        cfg: Config,
        a: Agent,
        procs: Procs,
        wouldChange: (Config, Agent) -> Boolean,
    ): Planned? {
        val host = Hosts.host(a.id)
        val target = host?.variants?.filter { it.kind == Kind.Desktop }?.firstNotNullOfOrNull { runningDesktopApp(cfg, a.id, it, procs) }
        val bin = host?.let { runningCliBin(it, procs) }
        return if (target == null && bin == null) null else Planned(a.id, target, bin, wouldChange(cfg, a))
    }

    private const val MS_PER_S = 1000
    private const val US_PER_MS = 1000L
}
