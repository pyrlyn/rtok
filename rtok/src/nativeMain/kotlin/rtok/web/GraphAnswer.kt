// Port of src/web/mod.rs `project_reply`/`project_write`, `graph_reply`, `diff_reply`, `export_reply`, `import_reply`
// and protocol.rs `ProjectRequest`: the graph page's `/ws` requests (T329.12, T329.14, T329.35, T329.40, T329.54).
package rtok.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugin.Runtime
import rtok.plugins.graph.DiffReport
import rtok.plugins.graph.DiffRequest
import rtok.plugins.graph.Drill
import rtok.plugins.graph.DrillGraph
import rtok.plugins.graph.DrillRequest
import rtok.plugins.graph.Export
import rtok.plugins.graph.ExportFile
import rtok.plugins.graph.ExportRequest
import rtok.plugins.graph.ExportView
import rtok.plugins.graph.GraphDiffs
import rtok.plugins.graph.ImportRequest
import rtok.plugins.graph.Jobs
import rtok.plugins.graph.ProjectAction
import rtok.plugins.graph.Projects

/** The registry writes the graph page offers; `project` is an id or a root path, as in `rtok graph projects`. */
sealed interface ProjectRequest {
    data class Select(
        val project: String,
    ) : ProjectRequest

    data class Link(
        val from: String,
        val to: String,
        val both: Boolean = false,
    ) : ProjectRequest

    data class Unlink(
        val from: String,
        val to: String,
        val both: Boolean = false,
    ) : ProjectRequest

    /** Re-read the project's files; answered at once, the row reads `indexing` until the worker ends. */
    data class Reindex(
        val project: String,
    ) : ProjectRequest

    /** Drop the project and its index rows, never its files; unconfirmed, nothing is written and the dry run comes back. */
    data class Remove(
        val project: String,
        val confirmed: Boolean = false,
    ) : ProjectRequest

    /** The wire shape, `action` the tag as in serde's `#[serde(tag = "action")]`. */
    @Serializable
    private data class Wire(
        val action: String,
        val project: String? = null,
        val from: String? = null,
        val to: String? = null,
        val both: Boolean = false,
        val confirmed: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** [e] as a request, null for an unknown action or a missing field. */
        fun parse(e: JsonElement): ProjectRequest? {
            val w = runCatching { json.decodeFromJsonElement(Wire.serializer(), e) }.getOrNull() ?: return null
            val pair = w.from != null && w.to != null
            return when (w.action) {
                "select" -> w.project?.let(::Select)
                "link" -> if (pair) Link(w.from!!, w.to!!, w.both) else null
                "unlink" -> if (pair) Unlink(w.from!!, w.to!!, w.both) else null
                "reindex" -> w.project?.let(::Reindex)
                "remove" -> w.project?.let { Remove(it, w.confirmed) }
                else -> null
            }
        }
    }
}

class GraphAnswer(
    private val cfg: LiveConfig,
) {
    /** The reply to the graph request in [v], whose key is one of [KEYS]. */
    fun reply(v: JsonObject): String? {
        val key = KEYS.first { it in v }
        val body = v.getValue(key)
        return when (key) {
            "project" -> ProjectRequest.parse(body).let { r -> if (r == null) Answer.message("unknown project request") else project(r) }
            "graph" -> decode(body, DrillRequest.serializer(), "project")?.let(::graph) ?: Answer.message("graph needs a project")
            "diff" -> decode(body, DiffRequest.serializer(), "project")?.let(::diff) ?: Answer.message("diff needs a project")
            "export" -> decode(body, ExportRequest.serializer())?.let(::export) ?: Answer.message("export needs a project, a level and a format")
            else -> decode(body, ImportRequest.serializer())?.let(::import) ?: Answer.message("import needs a name and the file's text")
        }
    }

    /** Nothing on success (the next snapshot shows it), a notice when refused, the dry run for an unconfirmed remove. */
    private fun project(r: ProjectRequest): String? {
        val planFor = (r as? ProjectRequest.Remove)?.takeIf { !it.confirmed }?.project
        return runCatching { projectWrite(cfg.value, r) }.fold(
            { plan -> planFor?.let { frame("project_plan", "project" to JsonPrimitive(it), "plan" to JsonPrimitive(plan)) } },
            { failure(it) },
        )
    }

    private fun graph(r: DrillRequest) = opened("web-drill") { frame("graph", "graph" to encode(DrillGraph.serializer(), Drill.run(it, r))) }

    private fun diff(r: DiffRequest) = opened("web-diff") { frame("diff", "project" to JsonPrimitive(r.project), "diff" to encode(DiffReport.serializer(), GraphDiffs.page(it, r))) }

    private fun export(r: ExportRequest) = opened("web-export") { frame("export", "file" to encode(ExportFile.serializer(), ExportView.pageFile(it, r))) }

    /** Only parses the text the page read: no runtime is opened, neither the registry nor the index is touched. */
    private fun import(r: ImportRequest): String =
        runCatching { ExportView.parse(r.text, r.name) }.fold(
            { frame("imported", "name" to JsonPrimitive(r.name), "export" to encode(Export.serializer(), it)) },
            { failure(it) },
        )

    private fun opened(
        session: String,
        body: (Runtime) -> String,
    ): String = runCatching { Runtime.open(cfg.value, session).use(body) }.getOrElse(::failure)

    companion object {
        /**
         * web/mod.rs `project_write`: the registry write behind the page's `project` request, shared with `rtok tui`'s
         * Graph keys so the surfaces cannot disagree. An unconfirmed remove answers with its dry run.
         */
        fun projectWrite(
            cfg: rtok.config.Config,
            r: ProjectRequest,
        ): String = Runtime.open(cfg, "web-projects").use { write(it, r) }

        private fun write(
            rt: Runtime,
            r: ProjectRequest,
        ): String =
            when (r) {
                is ProjectRequest.Select -> {
                    Projects.run(rt, ProjectAction.Select(r.project), false)
                }

                is ProjectRequest.Link -> {
                    Projects.run(rt, ProjectAction.Link(r.to, r.from, r.both, null), false)
                }

                is ProjectRequest.Unlink -> {
                    Projects.run(rt, ProjectAction.Unlink(r.to, r.from, r.both), false)
                }

                // The worker is the surface's own: the socket must keep answering while it indexes.
                is ProjectRequest.Reindex -> {
                    Jobs.start(rt, r.project)
                }

                // The unconfirmed request is the dry run, so the guard is here and not in each surface.
                is ProjectRequest.Remove -> {
                    if (r.confirmed) {
                        Projects.run(rt, ProjectAction.Remove(r.project), false)
                    } else {
                        Projects.preview(rt, ProjectAction.Remove(r.project), false)
                    }
                }
            }

        /** Their order is the order Rust's untagged `ClientMessage` tries them in. */
        val KEYS = listOf("project", "graph", "diff", "export", "import")

        /** Serde's defaults: unknown fields are ignored. */
        private val json = Json { ignoreUnknownKeys = true }

        /** [e] as [s], null when it does not parse or lacks the string field [required] Rust has no default for. */
        private fun <T> decode(
            e: JsonElement,
            s: KSerializer<T>,
            required: String? = null,
        ): T? {
            val present = required == null || ((e as? JsonObject)?.get(required) as? JsonPrimitive)?.isString == true
            return if (present) runCatching { json.decodeFromJsonElement(s, e) }.getOrNull() else null
        }

        private fun <T> encode(
            s: KSerializer<T>,
            value: T,
        ) = json.encodeToJsonElement(s, value)

        private fun failure(e: Throwable) = Answer.message(e.message ?: e.toString())

        private fun frame(
            type: String,
            vararg fields: Pair<String, JsonElement>,
        ) = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields).toString()
    }
}
