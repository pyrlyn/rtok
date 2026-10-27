// Port of src/plugins/graph/export.rs (T329.16): the graph as a file. One `Export` is built from
// the scope's registry rows and symbol scans, or read back from a saved file, and every output is
// a view of it. Source text is never included; file and symbol names are.
package rtok.plugins.graph

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.DefRow
import rtok.store.RefGroup
import rtok.store.projectByRoot
import rtok.store.symbolDefScan
import rtok.store.symbolRefScan
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Export(
    val schema: String,
    val projects: List<ExportProj>,
    val links: List<ExportLink>,
    val nodes: List<ExportNode>,
    val edges: List<ExportEdge>,
    val meta: ExportMeta,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExportProj(
    /** 0 for a directory that is not in the registry. */
    val id: Int,
    val name: String,
    val root: String,
    val origin: String,
    /** `tags`, `lsp` or `text`: the mode that answers for the project. */
    val backend: String,
    /** `ok`, `stale`, `not indexed`, `missing` or `unknown`. */
    val health: String,
    @SerialName("indexed_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val indexedAt: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExportLink(
    val from: Int,
    val to: Int,
    val kind: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reason: String? = null,
    /** Call references from `from` into `to` found in the indexes. */
    val references: Long,
)

@Serializable data class ExportNode(
    val id: String,
    val project: Int,
    val kind: String,
    val name: String,
    val path: String,
    val line: Int,
)

@Serializable data class ExportEdge(
    val from: String,
    val to: String,
    val kind: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExportMeta(
    val scope: List<String>,
    /** `overview`, `symbols` or `focus`. */
    val level: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val focus: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val depth: Int? = null,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("rtok_version") val rtokVersion: String,
    val redacted: Boolean,
    /** A project was still indexing, not indexed, or could not answer. */
    val partial: Boolean,
    val notes: List<String>,
)

@Serializable enum class ExportLevel {
    @SerialName("overview")
    Overview,

    @SerialName("symbols")
    Symbols,
}

@Serializable enum class ExportFormat {
    @SerialName("json")
    Json,

    @SerialName("svg")
    Svg,

    @SerialName("png")
    Png,
}

data class ExportQuery(
    val level: ExportLevel = ExportLevel.Overview,
    val focus: String? = null,
    val depth: Int = 2,
    val redact: Boolean = true,
    val pretty: Boolean = false,
    /** A saved export to show instead of the store. */
    val from: String? = null,
)

/** How [ExportView.render] turns the export into bytes; the JSON ignores the rest. */
data class ExportImage(
    val format: ExportFormat,
    val transparent: Boolean = false,
    val scale: Int = 1,
)

/** A download from the graph page's Export menu (T329.40). */
@Serializable
data class ExportRequest(
    val project: String,
    val level: ExportLevel,
    val focus: String? = null,
    val depth: Int? = null,
    val format: ExportFormat,
    val scale: Int? = null,
    val transparent: Boolean = false,
)

/** A saved JSON export the page read itself, to show read-only. */
@Serializable data class ImportRequest(
    val name: String,
    val text: String,
)

/** A finished download; the bytes travel as base64. */
@Serializable data class ExportFile(
    val name: String,
    val mime: String,
    val data: String,
)

object ExportView {
    const val SCHEMA = "rtok.graph.v1"
    const val SCHEMA_PATH = "docs/schemas/rtok.graph.v1.schema.json"

    /** Past this many definitions of one name a call draws no edge. */
    private const val AMBIGUOUS = 8

    /** A saved export is read whole; this keeps a wrong path from filling memory. */
    private const val MAX_IMPORT = 256L shl 20

    private val compact =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    private val strict = Json { ignoreUnknownKeys = false }

    private class Part(
        val row: ProjectRow?,
        val defs: List<DefRow>,
        val refs: List<RefGroup>,
        val text: Boolean,
    )

    private data class Call(
        val from: Int,
        val to: Int,
        val kind: String,
    ) : Comparable<Call> {
        override fun compareTo(other: Call) = compareValuesBy(this, other, { it.from }, { it.to }, { it.kind })
    }

    private fun backendOf(p: Part) =
        when {
            p.text -> "text"
            p.row?.backend?.backend == Chosen.Lsp -> "lsp"
            else -> "tags"
        }

    private class Graph(
        val nodes: List<ExportNode>,
        val edges: MutableList<Call>,
        val cross: Map<Pair<Int, Int>, Long>,
    )

    /** Definitions and call groups become nodes and edges, preferring the caller's own project. */
    private fun graph(
        done: List<Pair<Member, Part>>,
        ids: List<Int>,
    ): Graph {
        val nodes = ArrayList<ExportNode>()
        val owner = ArrayList<Int>()
        val at = HashMap<Triple<Int, String, String>, Int>()
        val byName = HashMap<String, MutableList<Int>>()
        done.forEachIndexed { pi, (_, part) ->
            for (d in part.defs) {
                at.getOrPut(Triple(pi, d.path, d.name)) { nodes.size }
                byName.getOrPut(d.name) { ArrayList() } += nodes.size
                owner += pi
                nodes += ExportNode("${ids[pi]}:${d.path}:${d.line}:${d.name}", ids[pi], d.kind, d.name, d.path, d.line)
            }
        }
        val edges = HashSet<Call>()
        val cross = HashMap<Pair<Int, Int>, Long>()
        done.forEachIndexed { pi, (_, part) ->
            for (r in part.refs) {
                if (r.scope.isEmpty()) continue
                val from = at[Triple(pi, r.path, r.scope)] ?: continue
                val all = byName[r.name] ?: continue
                val own = all.filter { owner[it] == pi }
                val to = own.ifEmpty { all }
                if (to.size > AMBIGUOUS) continue
                for (t in to) {
                    if (t != from) {
                        edges += Call(from, t, r.kind)
                        if (owner[t] != pi) cross[pi to owner[t]] = (cross[pi to owner[t]] ?: 0L) + r.count.coerceAtLeast(0)
                    }
                }
            }
        }
        return Graph(nodes, edges.sorted().toMutableList(), cross)
    }

    /** The symbols within [depth] calls of the definitions named [name], callers and callees alike. */
    private fun near(
        nodes: List<ExportNode>,
        edges: List<Call>,
        name: String,
        depth: Int,
    ): Set<Int> {
        val seen = nodes.indices.filter { nodes[it].name == name }.toHashSet()
        if (seen.isEmpty()) throw IllegalArgumentException("no definition of $name in scope")
        val ring = ArrayDeque(seen.sorted().map { it to 0 })
        while (ring.isNotEmpty()) {
            val (n, d) = ring.removeFirst()
            if (d >= depth) break
            for (e in edges) {
                val other =
                    when (n) {
                        e.from -> e.to
                        e.to -> e.from
                        else -> continue
                    }
                if (seen.add(other)) ring.addLast(other to d + 1)
            }
        }
        return seen
    }

    /** The export of [scope]. Reads the registry and the index, apart from the usual incremental refresh. */
    fun collect(
        rt: Runtime,
        scope: List<Member>,
        q: ExportQuery,
    ): Export {
        val cx = Ctx(rt)
        val (done, skipped) =
            Scope.fanOut(scope) { m ->
                Scope.walkable(m)
                GraphCore.indexFor(cx, m.root)
                val key = Index.canon(m.root)
                Part(rt.store.projectByRoot(m.root)?.let { Projects.row(rt, it) }, rt.store.symbolDefScan(key), rt.store.symbolRefScan(key), Text.applies(cx, m.root, Tools.lsp::probe))
            }
        val ids = done.map { it.second.row?.id ?: 0 }
        val g = graph(done, ids)
        val notes = skipped.lines().filter { it.isNotEmpty() }.toMutableList()
        var partial = notes.isNotEmpty()
        val proj =
            done.map { (m, p) ->
                val health = p.row?.state ?: "unknown"
                partial = partial || (health != "ok" && health != "unknown")
                if (p.text) notes += "${m.name}: text search only, no call edges"
                ExportProj(p.row?.id ?: 0, m.name, p.row?.root ?: m.root, p.row?.origin?.str ?: "unregistered", backendOf(p), health, p.row?.index?.indexedAt)
            }
        val links = ArrayList<ExportLink>()
        done.forEachIndexed { pi, (_, p) ->
            for (l in p.row?.links.orEmpty()) {
                val pj = ids.indexOf(l.to).takeIf { it >= 0 } ?: continue
                links += ExportLink(ids[pi], l.to, l.kind.str, l.reason, g.cross[pi to pj] ?: 0L)
            }
        }
        var keep: Set<Int>? = null
        val level =
            when {
                q.focus != null -> {
                    keep = near(g.nodes, g.edges, q.focus, q.depth)
                    "focus"
                }

                q.level == ExportLevel.Symbols -> {
                    "symbols"
                }

                else -> {
                    g.edges.clear()
                    keep = emptySet()
                    "overview"
                }
            }

        fun kept(i: Int) = keep?.contains(i) ?: true
        return Export(
            SCHEMA,
            proj,
            links,
            g.nodes.filterIndexed { i, _ -> kept(i) },
            g.edges.filter { kept(it.from) && kept(it.to) }.map { ExportEdge(g.nodes[it.from].id, g.nodes[it.to].id, it.kind) },
            ExportMeta(done.map { it.first.name }, level, q.focus, q.focus?.let { q.depth }, rtok.log.now(), rtok.VERSION, false, partial, notes),
        )
    }

    /** How one string is redacted: home becomes `~` and the user name `<user>`. */
    internal fun redactor(home: String?): (String) -> String {
        val user = home?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        // Only a whole path component is home: `/Users/al` must not turn `/Users/alice` into `~ice`.
        val re = home?.takeIf { it.length > 1 }?.let { Regex(Regex.escape(it) + "([/\\\\\\s]|$)") }
        return { s ->
            val t = re?.replace(s) { "~" + it.groupValues[1] } ?: s
            // Both separators on every platform: an export made on one system is read on another.
            buildString {
                var start = 0
                for (i in t.indices) {
                    if (t[i] == '/' || t[i] == '\\') {
                        piece(t.substring(start, i), t[i].toString(), user)
                        start = i + 1
                    }
                }
                if (start < t.length) piece(t.substring(start), "", user)
            }
        }
    }

    private fun StringBuilder.piece(
        seg: String,
        sep: String,
        user: String?,
    ) {
        append(if (seg == user) "<user>" else seg).append(sep)
    }

    /** Home, the user name and absolute roots out. */
    internal fun redact(
        e: Export,
        home: String?,
    ): Export {
        val text = redactor(home)
        val projects =
            e.projects.map { p ->
                val root = text(p.root)
                val name = root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                p.copy(root = if ((root.startsWith('/') || root.startsWith('\\')) && name.isNotEmpty() && name != "..") "…/$name" else root, name = text(p.name))
            }
        val ids = HashMap<String, String>()
        val nodes =
            e.nodes.map { n ->
                val path = text(n.path)
                val id = n.id.replaceFirst(":${n.path}:", ":$path:")
                ids[n.id] = id
                n.copy(id = id, path = path)
            }
        return e.copy(
            projects = projects,
            links = e.links.map { it.copy(reason = it.reason?.let(text)) },
            nodes = nodes,
            edges = e.edges.map { it.copy(from = ids[it.from] ?: it.from, to = ids[it.to] ?: it.to) },
            meta = e.meta.copy(notes = e.meta.notes.map(text), scope = e.meta.scope.map(text), redacted = true),
        )
    }

    /** Reads a saved export for viewing; nothing is written, the registry and the index are not consulted. */
    fun read(path: String): Export {
        val size = rtok.sys.fileLen(path) ?: throw IllegalArgumentException("$path: No such file or directory")
        if (size > MAX_IMPORT) throw IllegalArgumentException("$path: $size bytes is more than a graph export can be")
        return parse(rtok.sys.readToString(path), path)
    }

    /** The same checks on text from elsewhere (the page's file picker); [label] names the source. */
    fun parse(
        text: String,
        label: String,
    ): Export {
        if (text.length.toLong() > MAX_IMPORT) throw IllegalArgumentException("$label: ${text.length} bytes is more than a graph export can be")
        // kotlinx reads a bare word such as a path as a primitive where serde fails, so only an object or array is JSON here.
        val value =
            runCatching { StrictJson.parse(text) }.getOrNull()?.takeIf { it !is JsonPrimitive || it.isString || text.trim() in setOf("null", "true", "false") || text.trim().toDoubleOrNull() != null }
                ?: throw IllegalArgumentException("$label is not a graph export")
        val schema = ((value as? JsonObject)?.get("schema") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (schema != SCHEMA) throw IllegalArgumentException("$label: schema ${schema?.let { "Some(\"$it\")" } ?: "None"}, this rtok reads $SCHEMA")
        return runCatching { strict.decodeFromJsonElement(Export.serializer(), value) }.getOrElse { throw IllegalArgumentException("$label does not match $SCHEMA") }
    }

    /** The export every format is a view of, then redacted, so a picture never shows more than the JSON. */
    fun build(
        rt: Runtime,
        scope: List<Member>,
        q: ExportQuery,
    ): Export {
        val e = q.from?.let(::read) ?: collect(rt, scope, q)
        return if (q.redact) redact(e, rtok.sys.homeDir()) else e
    }

    fun run(
        rt: Runtime,
        scope: List<Member>,
        q: ExportQuery,
    ): String {
        val e = build(rt, scope, q)
        return (if (q.pretty) Projects.pretty.encodeToString(Export.serializer(), e) else compact.encodeToString(Export.serializer(), e)) + "\n"
    }

    /** SVG comes from [Draw]; PNG needs a rasterizer, which needs a dependency. */
    var draw: ((Export, Boolean) -> String)? = Draw::svg

    fun render(
        rt: Runtime,
        scope: List<Member>,
        q: ExportQuery,
        img: ExportImage,
    ): ByteArray {
        if (img.format == ExportFormat.Json) return run(rt, scope, q).encodeToByteArray()
        val svg = draw ?: throw UnsupportedOperationException("graph images are not available in this build")
        if (img.format == ExportFormat.Png) throw UnsupportedOperationException("png export is not available in this build; use svg")
        return svg(build(rt, scope, q), img.transparent).encodeToByteArray()
    }

    /** Renders and writes [file]; `rtok graph export -o` and the TUI's export key both end here. */
    fun write(
        rt: Runtime,
        scope: List<Member>,
        q: ExportQuery,
        img: ExportImage,
        file: String,
    ): Int {
        val bytes = render(rt, scope, q, img)
        rtok.sys.writeBytes(file, bytes)
        return bytes.size
    }

    /** The page's download; redaction is always on. */
    @OptIn(ExperimentalEncodingApi::class)
    fun pageFile(
        rt: Runtime,
        r: ExportRequest,
    ): ExportFile {
        if (r.project.isEmpty()) throw IllegalArgumentException("export needs a project")
        val scope = Scope.resolve(rt.store, r.project, ".")
        val focus = r.focus?.takeIf { it.isNotEmpty() }
        val bytes =
            render(
                rt,
                scope,
                ExportQuery(r.level, focus, (r.depth ?: 2).coerceIn(1, 8), redact = true, pretty = true),
                ExportImage(r.format, r.transparent, (r.scale ?: 1).coerceIn(1, 4)),
            )
        val (ext, mime) =
            when (r.format) {
                ExportFormat.Json -> "json" to "application/json"
                ExportFormat.Svg -> "svg" to "image/svg+xml"
                ExportFormat.Png -> "png" to "image/png"
            }
        val level =
            when {
                focus != null -> "focus"
                r.level == ExportLevel.Symbols -> "symbols"
                else -> "overview"
            }
        return ExportFile(downloadName(scope[0].name, level, ext, rtok.sys.homeDir()), mime, Base64.encode(bytes))
    }

    /** Built from the redacted project name, as the file itself is. */
    internal fun downloadName(
        project: String,
        level: String,
        ext: String,
        home: String?,
    ): String {
        val slug = redactor(home)(project).map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        return "rtok-graph-$slug-$level.$ext"
    }

    /** MCP `graph_export`: JSON, always redacted, compact; past `max_tokens` it archives like any answer. */
    fun call(
        rt: Runtime,
        args: JsonObject,
        scope: List<Member>,
    ): String {
        fun arg(k: String) = Outline.str(args[k]).ifEmpty { null }
        val depth =
            (args["depth"] as? JsonPrimitive)
                ?.longOrNull
                ?.takeIf { it >= 0 }
                ?.coerceIn(1, 8)
                ?.toInt() ?: 2
        val text = run(rt, scope, ExportQuery(if (arg("level") == "symbols") ExportLevel.Symbols else ExportLevel.Overview, arg("focus"), depth, redact = true, pretty = false))
        return Tools.cap(Ctx(rt), text)
    }
}
