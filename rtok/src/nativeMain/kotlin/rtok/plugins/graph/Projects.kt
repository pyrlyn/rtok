// Port of src/plugins/graph/projects.rs (T329.1-3): `rtok graph projects`, the project registry from the command
// line with each project's index status, health and links.
package rtok.plugins.graph

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.Col
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.*

@Serializable
data class ProjectIndex(
    val rows: Long,
    val files: Long,
    val pending: Int,
    val watch: String,
    @SerialName("indexed_at") val indexedAt: Long?,
)

@Serializable
data class ProjectLink(
    val to: Int,
    val name: String,
    val kind: LinkKind,
    val reason: String?,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProjectRow(
    val id: Int,
    val name: String,
    val root: String,
    val origin: Origin,
    val selected: Boolean,
    val missing: Boolean,
    val state: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val error: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
    val index: ProjectIndex?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val backend: CapabilityRecord? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val alerts: List<Alert> = emptyList(),
    val health: HealthScore,
    @SerialName("scope_health") @EncodeDefault(EncodeDefault.Mode.NEVER) val scopeHealth: Int? = null,
    val links: List<ProjectLink>,
)

sealed interface ProjectAction {
    data class Link(
        val to: String,
        val from: String?,
        val both: Boolean,
        val reason: String?,
    ) : ProjectAction

    data class Unlink(
        val to: String,
        val from: String?,
        val both: Boolean,
    ) : ProjectAction

    data object List : ProjectAction

    data class Add(
        val path: String,
    ) : ProjectAction

    data class Select(
        val target: String,
    ) : ProjectAction

    /** Re-reads the project's files into its index (T329.54); shared by the CLI, the web page and the TUI. */
    data class Reindex(
        val target: String,
    ) : ProjectAction

    data class Remove(
        val target: String,
    ) : ProjectAction
}

object Projects {
    @OptIn(ExperimentalSerializationApi::class)
    internal val pretty =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = true
            encodeDefaults = true
        }

    /** `<id|path>`: a number naming a known project is its id, anything else is a directory. */
    fun resolve(
        store: Store,
        target: String,
    ): Project =
        target.toIntOrNull()?.let { store.project(it) } ?: store.projectByRoot(target)
            ?: throw IllegalStateException("no project `$target`; see `rtok graph projects`")

    private fun linkRows(
        store: Store,
        from: Int,
    ) = store.projectLinks().filter { it.from == from }.map { l ->
        ProjectLink(l.to, store.project(l.to)?.displayName() ?: l.to.toString(), l.kind, l.reason)
    }

    fun row(
        rt: Runtime,
        p: Project,
    ): ProjectRow {
        val cx = Ctx(rt)
        val missing = p.missing()
        val status = if (missing) null else Status.collect(cx, p.root)
        val health = status?.let { Score.of(rt, p, it) } ?: Score.missing(p)
        val scopeHealth = runCatching { Alerts.reach(rt.store, p) }.getOrNull()?.let { Score.scopeLowest(rt, it, health) }
        val index = status?.let { ProjectIndex(it.rows, it.files, it.pending.size, it.watch, it.indexedAt) }
        var state =
            when {
                index == null -> "missing"
                index.indexedAt == null && index.rows == 0L -> "not indexed"
                index.pending > 0 -> "stale"
                else -> "ok"
            }
        var error: String? = null
        // A job of this process outranks what the index says: its rows are about to change.
        when (val j = Jobs.get(p.root)) {
            is Job.Running -> {
                if (!missing) state = "indexing"
            }

            is Job.Failed -> {
                if (!missing) {
                    state = "failed"
                    error = j.error
                }
            }

            null -> {}
        }
        return ProjectRow(
            p.id,
            p.displayName(),
            p.root,
            p.origin,
            p.selected,
            missing,
            state,
            error,
            p.createdAt,
            p.lastUsedAt,
            index,
            if (missing) null else Capability.mirrored(cx, p.root),
            Alerts.mirrored(cx, p.root),
            health,
            scopeHealth,
            linkRows(rt.store, p.id),
        )
    }

    fun render(
        rows: kotlin.collections.List<ProjectRow>,
        json: Boolean,
    ): String {
        if (json) return pretty.encodeToString(ListSerializer(ProjectRow.serializer()), rows) + "\n"
        if (rows.isEmpty()) return "no projects; `rtok graph projects add <path>`\n"
        val cols = listOf(Col.left(1), Col.right(2), Col.left(4), Col.left(6), Col.left(5), Col.right(4), Col.right(5), Col.right(7), Col.right(5), Col.right(6), Col.right(5), Col.left(4))
        val cells = ArrayList<kotlin.collections.List<String>>()
        cells += listOf("", "id", "name", "origin", "state", "rows", "files", "pending", "links", "health", "scope", "root")
        for (r in rows) {
            fun n(f: (ProjectIndex) -> String) = r.index?.let(f) ?: "-"
            cells +=
                listOf(
                    if (r.selected) "*" else "",
                    r.id.toString(),
                    r.name,
                    r.origin.str,
                    r.state,
                    n { it.rows.toString() },
                    n { it.files.toString() },
                    n { it.pending.toString() },
                    r.links.size.toString(),
                    r.health.score?.toString() ?: "-",
                    r.scopeHealth?.toString() ?: "-",
                    r.root,
                )
        }
        // `table` pads every column, so the free-text root would end each line in spaces.
        return rtok
            .table(cols, cells)
            .lines()
            .filter { it.isNotEmpty() }
            .joinToString("") { it.trimEnd() + "\n" }
    }

    fun rows(rt: Runtime) = rt.store.projects().map { row(rt, it) }

    private fun pair(
        rt: Runtime,
        to: String,
        from: String?,
    ): Pair<Project, Project> {
        val a =
            if (from != null) {
                resolve(rt.store, from)
            } else {
                rt.store.resolveProject(rtok.sys.cwd()).project ?: throw IllegalStateException("no selected project; pass --from <project> or `rtok graph projects select`")
            }
        return a to resolve(rt.store, to)
    }

    private fun changes(
        done: kotlin.collections.List<Triple<Project, Project, Boolean>>,
        yes: String,
        no: String,
        json: Boolean,
    ): String {
        if (json) {
            return pretty.encodeToString(
                JsonElement.serializer(),
                buildJsonArray {
                    for ((a, b, c) in done) {
                        add(
                            buildJsonObject {
                                put("from", a.id)
                                put("to", b.id)
                                put("changed", c)
                            },
                        )
                    }
                },
            ) + "\n"
        }
        return done.joinToString("") { (a, b, c) -> if (c) "$yes ${a.displayName()} -> ${b.displayName()}\n" else "${a.displayName()} -> ${b.displayName()} $no\n" }
    }

    private fun one(
        rt: Runtime,
        p: Project,
        json: Boolean,
    ): String {
        val r = row(rt, p)
        return if (json) pretty.encodeToString(ProjectRow.serializer(), r) + "\n" else render(listOf(r), false)
    }

    private fun gone(p: Project): Nothing = throw IllegalStateException("${p.root} no longer exists; remove it or restore the directory")

    fun run(
        rt: Runtime,
        action: ProjectAction,
        json: Boolean,
    ): String =
        when (action) {
            ProjectAction.List -> {
                render(rows(rt), json)
            }

            is ProjectAction.Add -> {
                if (!rtok.sys.isDir(action.path)) throw IllegalStateException("${action.path} is not a directory")
                one(rt, rt.store.registerProject(action.path, Origin.Manual), json)
            }

            is ProjectAction.Select -> {
                val p = resolve(rt.store, action.target)
                if (p.missing()) gone(p)
                rt.store.selectProject(p.id)
                one(rt, rt.store.project(p.id) ?: p, json)
            }

            is ProjectAction.Link -> {
                val (a, b) = pair(rt, action.to, action.from)
                for (p in listOf(a, b)) if (p.missing()) throw IllegalStateException("${p.root} no longer exists; it cannot be linked")
                val done = arrayListOf(Triple(a, b, rt.store.linkProjects(a.id, b.id, LinkKind.Manual, action.reason)))
                if (action.both) done += Triple(b, a, rt.store.linkProjects(b.id, a.id, LinkKind.Manual, action.reason))
                // Linking a never-indexed project starts indexing it, so the scope answers from it right away.
                if (row(rt, b).state == "not indexed") Index.run(Ctx(rt), b.root, false)
                changes(done, "linked", "already linked", json)
            }

            is ProjectAction.Unlink -> {
                val (a, b) = pair(rt, action.to, action.from)
                val done = arrayListOf(Triple(a, b, rt.store.unlinkProjects(a.id, b.id)))
                if (action.both) done += Triple(b, a, rt.store.unlinkProjects(b.id, a.id))
                changes(done, "unlinked", "was not linked", json)
            }

            is ProjectAction.Reindex -> {
                val p = resolve(rt.store, action.target)
                if (p.missing()) gone(p)
                val r = Index.run(Ctx(rt), p.root, false)
                if (json) {
                    pretty.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("reindexed", p.id)
                            put("root", p.root)
                            put("files", r.indexed)
                            put("rows_written", r.inserted)
                        },
                    ) + "\n"
                } else {
                    "indexed ${p.root}: ${r.indexed} files, ${r.inserted} rows written\n"
                }
            }

            is ProjectAction.Remove -> {
                val p = resolve(rt.store, action.target)
                rt.store.removeProject(p.id)
                if (json) {
                    pretty.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("removed", p.id)
                            put("root", p.root)
                        },
                    ) + "\n"
                } else {
                    "removed ${p.root} (index rows dropped, files untouched)\n"
                }
            }
        }

    /** What `Remove` or `Unlink` would drop, with nothing written (T416.4). */
    fun preview(
        rt: Runtime,
        action: ProjectAction,
        json: Boolean,
    ): String =
        when (action) {
            is ProjectAction.Unlink -> {
                val (a, b) = pair(rt, action.to, action.from)
                val links = rt.store.projectLinks()

                fun linked(
                    x: Project,
                    y: Project,
                ) = links.any { it.from == x.id && it.to == y.id }
                val done = arrayListOf(Triple(a, b, linked(a, b)))
                if (action.both) done += Triple(b, a, linked(b, a))
                changes(done, "would unlink", "is not linked", json)
            }

            is ProjectAction.Remove -> {
                val p = resolve(rt.store, action.target)
                val links = rt.store.projectLinks()
                val out = links.count { it.from == p.id }
                val incoming = links.count { it.to == p.id }
                val idx = row(rt, p).index
                val rows = idx?.rows ?: 0
                val files = idx?.files ?: 0
                if (json) {
                    pretty.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("would_remove", p.id)
                            put("root", p.root)
                            put("index_rows", rows)
                            put("indexed_files", files)
                            put("links_out", out)
                            put("links_in", incoming)
                        },
                    ) + "\n"
                } else {
                    "would remove ${p.root} (id ${p.id})\n  index rows: $rows over $files files\n  links: $out out, $incoming in\n  files untouched; nothing removed (dry run)\n"
                }
            }

            else -> {
                throw IllegalStateException("only `remove` and `unlink` have a dry run")
            }
        }
}
