// Port of src/model/mod.rs `graph_page_text` (T230, T329.21) and `project_rows` (T329.12): the Graph page text and
// the project registry the snapshot sends under `graph` and `projects`.
package rtok.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.graph.Dead
import rtok.plugins.graph.DeadRow
import rtok.plugins.graph.ProjectRow
import rtok.plugins.graph.Projects
import rtok.plugins.graph.Scope
import rtok.plugins.graph.Status
import rtok.sys.cwd

object GraphPage {
    /** Dead-symbol lines shown before "and N more" (`GRAPH_DEAD_CAP`): a per-tick bound, not a token budget. */
    const val DEAD_CAP = 200

    /** `graph status` plus the store's dead candidates for the cwd's scope; null when a read fails. */
    fun text(
        cfg: Config,
        root: String = cwd(),
    ): String? =
        runCatching {
            Runtime.open(cfg, "web-graph").use { rt ->
                val cx = Ctx(rt)
                var health = Status.collect(cx, root)
                val members = runCatching { Scope.resolve(rt.store, null, root) }.getOrDefault(emptyList())
                val linked = members.size > 1
                if (linked) health = health.copy(pending = Scope.pendingPagePaths(cx, members))
                buildString {
                    append(Status.formatTable(health))
                    append("\ndead symbols\n")
                    val found =
                        runCatching {
                            if (linked) {
                                Scope.deadPageRows(cx, members).let { (rows, notes) -> rows.map { (p, r) -> p to r } to notes }
                            } else {
                                Dead.candidates(cx, root).map { null to it } to ""
                            }
                        }
                    found.fold({ (rows, notes) -> appendDead(rows, notes) }, { append(" dead scan failed: ${it.message}\n") })
                }
            }
        }.getOrNull()

    private fun StringBuilder.appendDead(
        rows: List<Pair<String?, DeadRow>>,
        notes: String,
    ) {
        if (rows.isEmpty()) append(" none\n")
        for ((project, r) in rows.take(DEAD_CAP)) {
            val tag = project?.let { "[$it] " }.orEmpty()
            append(" $tag${r.path}:${r.line} ${r.kind} ${r.name}\n")
        }
        if (rows.size > DEAD_CAP) {
            append(" … ${rows.size - DEAD_CAP} more, capped at $DEAD_CAP — `rtok graph dead --json` has the rest\n")
        }
        notes.lines().filter { it.isNotEmpty() }.forEach { append(" $it\n") }
    }

    /** Every registered project, the rows `rtok graph projects --json` prints; null when the store read fails. */
    fun projects(cfg: Config): List<ProjectRow>? = runCatching { Runtime.open(cfg, "web-projects").use { Projects.rows(it) } }.getOrNull()

    fun projectsJson(cfg: Config): JsonElement = projects(cfg)?.let { Json.encodeToJsonElement(ListSerializer(ProjectRow.serializer()), it) } ?: JsonNull
}
