// Port of the alert half of src/plugins/graph/health.rs (T329.17): `Kind`, `Alert`, the mirrored alerts in
// plugin state, `describe`/`grouped`/`texts`, `reach` and the answer `notice`. The checker loop is HealthCheck.kt.
package rtok.plugins.graph

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.Project
import rtok.store.Store
import rtok.store.projectByRoot
import rtok.store.projectLinks
import rtok.store.projects

@Serializable
enum class AlertKind(val words: String, val effect: String) {
    @SerialName("missing") Missing("missing", "results exclude"),
    @SerialName("unreachable") Unreachable("unreachable", "results exclude"),
    @SerialName("backend_down") BackendDown("backend down", "answered from the tags index for"),
    @SerialName("link_broken") LinkBroken("link broken", "the broken link leaves out a project of"),
}

/** One raised alert, as stored and as `graph projects --json` / `doctor --json` print it. */
@Serializable
data class Alert(val kind: AlertKind, val project: String, val root: String, val since: Long, val detail: String)

/** A project of a scope and the names from the scope's start to it. */
data class Reached(val project: Project, val chain: List<String>)

object Alerts {
    private val list = ListSerializer(Alert.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun key(root: String) = "alerts:$root"

    /** The alerts some process raised for the project registered at [root]. */
    fun mirrored(cx: Ctx, root: String): List<Alert> =
        runCatching { cx.pluginStateGet("graph", key(root))?.let { json.decodeFromString(list, it) } }.getOrNull().orEmpty()

    /** A lost write is rewritten by the next check that sees a difference. */
    fun store(cx: Ctx, root: String, alerts: List<Alert>) { runCatching { cx.pluginStateSet("graph", key(root), Json.encodeToString(list, alerts)) } }

    /** Every alert on the registry, for `rtok doctor`. */
    fun all(rt: Runtime): List<Alert> {
        val cx = Ctx(rt)
        return runCatching { rt.store.projects() }.getOrDefault(emptyList()).flatMap { mirrored(cx, it.root) }
            .sortedWith(compareBy<Alert> { it.kind }.thenBy { it.root })
    }

    @OptIn(ExperimentalForeignApi::class)
    fun hhmm(ts: Long): String = memScoped {
        val t = alloc<time_tVar>(); t.value = ts
        val out = alloc<tm>()
        if (localtime_r(t.ptr, out.ptr) == null) "?" else "${out.tm_hour.toString().padStart(2, '0')}:${out.tm_min.toString().padStart(2, '0')}"
    }

    /** One line per kind: projects hit by the same problem at once read as one line. */
    fun describe(kind: AlertKind, group: List<Pair<Alert, List<String>>>, notice: Boolean): String {
        val since = hhmm(group.minOf { it.first.since })
        val names = group.joinToString(", ") { it.first.project }
        val one = group.singleOrNull()
        val line = StringBuilder(
            if (one != null && one.second.size > 2) "${one.second.joinToString(" to ")}: ${one.first.project} ${kind.words}" else "$names ${kind.words}",
        )
        line.append(" since $since")
        line.append(if (one != null) " (${one.first.detail})" else " (${group.size} projects)")
        if (notice) line.append("; ${kind.effect} $names")
        return line.toString()
    }

    fun grouped(alerts: List<Pair<Alert, List<String>>>, notice: Boolean): List<String> =
        alerts.groupBy { it.first.kind }.entries.sortedBy { it.key }.map { (k, g) -> describe(k, g, notice) }

    /** The lines `rtok doctor` prints. */
    fun texts(alerts: List<Alert>): List<String> = grouped(alerts.map { it to emptyList() }, false)

    /** [start] and what it links to, level by level; a missing project stays in. */
    fun reach(store: Store, start: Project): List<Reached> {
        val byId = store.projects().associateBy { it.id }
        val links = store.projectLinks()
        val seen = hashSetOf(start.id)
        val out = ArrayList<Reached>()
        val queue = ArrayDeque(listOf(Reached(start, listOf(start.displayName()))))
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (l in links.filter { it.from == r.project.id }) {
                val next = byId[l.to] ?: continue
                if (seen.add(l.to)) queue.addLast(Reached(next, r.chain + next.displayName()))
            }
            out += r
        }
        return out
    }

    /** The notice that heads a graph answer for the scope of [project] (else the project at [cwd]). */
    fun notice(rt: Runtime, project: String?, cwd: String): String? {
        if (!rt.config.plugins.graph.alerts) return null
        val start = if (!project.isNullOrEmpty()) runCatching { Projects.resolve(rt.store, project) }.getOrNull() ?: return null
        else runCatching { rt.store.projectByRoot(cwd) }.getOrNull() ?: return null
        val cx = Ctx(rt)
        val reached = runCatching { reach(rt.store, start) }.getOrNull() ?: return null
        val found = reached.flatMap { r -> mirrored(cx, r.project.root).map { it to r.chain } }
        val alerting = found.map { it.first.root }.toSet()
        val text = grouped(found, true).joinToString("") { "notice: $it\n" } + Score.note(rt, reached, alerting).orEmpty()
        return text.ifEmpty { null }
    }
}
