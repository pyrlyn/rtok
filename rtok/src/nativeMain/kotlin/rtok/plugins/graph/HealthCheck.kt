// Port of the checker half of src/plugins/graph/health.rs (T329.17): `Health` (findings, backend backoff, tick,
// load, recovered, persist), `scope_of` and the `run` loop of a hosting process.
package rtok.plugins.graph

import platform.posix.usleep
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.Project
import rtok.store.projectByRoot
import rtok.store.projects
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

/** What the check remembers between rounds; one per hosting process. */
class Health(
    /** Manifest reference warnings of a root (project refs discovery); only `…, not found` ones alert. */
    private val refWarnings: (String) -> List<String> = { emptyList() },
) {
    private class Streak(
        var bad: Int = 0,
        var good: Int = 0,
        var alert: Alert? = null,
    )

    private val streaks = LinkedHashMap<Pair<String, AlertKind>, Streak>()

    /** Per root: when a broken server is tried again, and the wait after that. */
    internal val backoff = HashMap<String, Pair<Long, Long>>()
    private val written = HashMap<String, List<Alert>>()
    private val loaded = HashSet<String>()

    private fun findings(
        cx: Ctx,
        p: Project,
        probe: Probe,
        now: Long,
    ): List<Pair<AlertKind, String>> {
        rootState(p.root)?.let { return listOf(it) }
        val out = ArrayList<Pair<AlertKind, String>>()
        if (graphCfg(cx).autoLinkReferences) {
            val broken = refWarnings(p.root).filter { it.endsWith(", not found") }
            if (broken.isNotEmpty()) out += AlertKind.LinkBroken to Capability.clip(broken.joinToString("; "))
        }
        backend(cx, p, probe, now)?.let { out += AlertKind.BackendDown to it }
        return out
    }

    private fun backend(
        cx: Ctx,
        p: Project,
        probe: Probe,
        now: Long,
    ): String? {
        val config = GraphCore.backendName(cx, p.root)
        val held = Capability.held(p.root)?.takeIf { it.config == config }
        val rec = held ?: Capability.mirrored(cx, p.root) ?: return null
        val retry = { Capability.reprobe(cx, p.root, config, probe) }
        if (held != null && rec.backend == Chosen.Tags && rec.server && !rec.down) {
            if (probe(p.root) == null) retry()
            return null
        }
        if (!rec.down) {
            backoff.remove(p.root)
            return null
        }
        var step = backoff.getOrPut(p.root) { (now + RETRY_S) to RETRY_S }
        // The retry follows the report, so a break is seen by two checks before it can be undone.
        if (held != null && now >= step.first) {
            val wait = minOf(step.second * 2, BACKOFF_CAP_S)
            step = (now + wait) to wait
            backoff[p.root] = step
            retry()
        }
        return rec.reason ?: "language server stopped answering"
    }

    /** One round over [projects] at time [now]; [probe] is a parameter so tests need no server. */
    fun tick(
        rt: Runtime,
        projects: List<Project>,
        probe: Probe,
        now: Long,
    ) {
        val cx = Ctx(rt)
        load(cx, projects)
        val found = LinkedHashMap<Pair<String, AlertKind>, Pair<String, String>>()
        for (p in projects) for ((kind, detail) in findings(cx, p, probe, now)) found[p.root to kind] = p.displayName() to detail
        for ((k, v) in found) {
            val s = streaks.getOrPut(k) { Streak() }
            s.good = 0
            s.bad = minOf(s.bad + 1, CONFIRM)
            if (s.bad >= CONFIRM) {
                val alert = Alert(k.second, v.first, k.first, s.alert?.since ?: now, v.second)
                if (s.alert == null) cx.log("warn", "graph", "alert", Alerts.texts(listOf(alert))[0])
                s.alert = alert
            }
        }
        for (k in streaks.keys.filter { it !in found }) {
            // A project that left the scope stops alerting at once.
            val inScope = projects.any { it.root == k.first }
            val s = streaks[k] ?: continue
            s.bad = 0
            s.good++
            if (s.alert != null && inScope && s.good < CONFIRM) continue
            val a = streaks.remove(k)?.alert
            if (a != null && inScope) recovered(cx, a)
        }
        persist(cx)
    }

    /** Alerts another process raised are taken over, so they clear after two good checks here. */
    private fun load(
        cx: Ctx,
        projects: List<Project>,
    ) {
        for (p in projects.filter { loaded.add(it.root) }) {
            val stored = Alerts.mirrored(cx, p.root).sortedBy { it.kind }
            for (a in stored) streaks[p.root to a.kind] = Streak(CONFIRM, 0, a)
            written[p.root] = stored
        }
    }

    private fun recovered(
        cx: Ctx,
        a: Alert,
    ) {
        cx.log("info", "graph", "alert", "recovered: ${a.project} ${a.kind.words}")
        // Files may have changed while the project was away; the watcher does not index on start.
        if (a.kind == AlertKind.Missing || a.kind == AlertKind.Unreachable) {
            runCatching { Index.run(cx, a.root, false) }.onFailure { cx.log("warn", "graph", "alert", "re-index of ${a.root}: ${it.message}") }
        }
    }

    private fun persist(cx: Ctx) {
        val now = HashMap<String, MutableList<Alert>>()
        for ((k, s) in streaks) s.alert?.let { now.getOrPut(k.first) { ArrayList() } += it }
        for (root in now.keys + written.keys) {
            val alerts = now[root].orEmpty().sortedBy { it.kind }
            if (written[root] != alerts) Alerts.store(cx, root, alerts)
            written[root] = alerts
        }
    }

    companion object {
        /** Checks in a row that raise an alert, and that clear it. */
        const val CONFIRM = 2
        const val RETRY_S = 60L
        const val BACKOFF_CAP_S = 900L

        /**
         * Whether the root answers: a missing root under an existing parent was moved or deleted; one whose
         * parent is gone too (an unmounted disk) is unreachable. Null while it is up.
         */
        fun rootState(root: String): Pair<AlertKind, String>? {
            if (rtok.sys.isDir(root)) return null
            if (rtok.sys.exists(root)) return AlertKind.Missing to "not a directory"
            val parent = root.trimEnd('/').substringBeforeLast('/', "")
            return if (parent.isNotEmpty() && rtok.sys.exists(parent)) {
                AlertKind.Missing to "directory does not exist"
            } else {
                AlertKind.Unreachable to "No such file or directory (os error 2)"
            }
        }

        /** The scope of the project at [start], or every registered project when there is none. */
        fun scopeOf(
            rt: Runtime,
            start: String?,
        ): List<Project> {
            if (start == null) return runCatching { rt.store.projects() }.getOrDefault(emptyList())
            val p = runCatching { rt.store.projectByRoot(start) }.getOrNull() ?: return emptyList()
            return runCatching { Alerts.reach(rt.store, p).map { it.project } }.getOrDefault(emptyList())
        }

        /** The check of a hosting process (`rtok mcp` passes its cwd, `rtok web` nothing); ends when [stop] is set. */
        fun run(
            rt: Runtime,
            start: String?,
            stop: AtomicInt,
            probe: Probe = Tools.lsp::probe,
        ) {
            val cfg = rt.config.plugins.graph
            if (cfg.alerts && cfg.healthCheckIntervalS > 0u) runEvery(rt, start, cfg.healthCheckIntervalS.toLong() * 1000, probe, stop)
        }

        fun runEvery(
            rt: Runtime,
            start: String?,
            everyMs: Long,
            probe: Probe,
            stop: AtomicInt,
        ) {
            val h = Health()
            while (stop.value == 0) {
                val round = TimeSource.Monotonic.markNow()
                runCatching { h.tick(rt, scopeOf(rt, start), probe, Capability.now()) }
                while (round.elapsedNow().inWholeMilliseconds < everyMs && stop.value == 0) usleep(minOf(10_000L, everyMs * 1000).toUInt())
            }
        }
    }
}
