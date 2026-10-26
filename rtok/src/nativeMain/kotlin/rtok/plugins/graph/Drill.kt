// Port of src/plugins/graph/drill.rs (T329.14): the graph page's level 2, the inside of one
// project as nodes and edges, read from the index only. Files with the call, import and
// implements links between them; an expanded file shows its definitions; a focused symbol shows
// its callers and callees; a call into a linked project ends at a node of that project.
package rtok.plugins.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.DefRow
import rtok.store.Project
import rtok.store.RefGroup
import rtok.store.projectScope
import rtok.store.symbolDefScan
import rtok.store.symbolDefsNamed
import rtok.store.symbolFts
import rtok.store.symbolImportEdges
import rtok.store.symbolRefScan

@Serializable data class DrillFocus(val path: String = "", val name: String = "")

@Serializable
data class DrillRequest(
    /** An id or a root path, as in `rtok graph projects`. */
    val project: String = "",
    /** Files shown as their definitions. */
    val expand: List<String> = emptyList(),
    /** A symbol shown with its callers and callees. */
    val focus: DrillFocus? = null,
    val depth: Int? = null,
    /** Nodes shown before "+N more"; the page raises it on a click. */
    val limit: Int? = null,
    /** Finds symbols in the project and its scope. */
    val query: String = "",
)

@Serializable enum class DrillNodeKind {
    @SerialName("file") File, @SerialName("type") Type, @SerialName("module") Module, @SerialName("function") Function,
    /** A symbol of a linked project: `project` says which. */
    @SerialName("external") External,
}

/** Declaration order is the sort order (Rust derives `Ord`). */
@Serializable enum class DrillEdgeKind { @SerialName("contains") Contains, @SerialName("calls") Calls, @SerialName("implements") Implements, @SerialName("imports") Imports }

@Serializable
data class DrillNode(
    val id: String, val kind: DrillNodeKind, val label: String, val path: String, val line: Int,
    /** The project that owns the node: the one asked for, or the linked one for `external`. */
    val project: Int, val signature: String,
    /** Edge count through the node; the page sizes it by this. */
    var weight: Long,
    /** The file changed since the last index run. */
    val stale: Boolean,
)

@Serializable data class DrillEdge(val from: String, val to: String, val kind: DrillEdgeKind, val count: Long)
@Serializable data class DrillHit(val project: Int, val name: String, val path: String, val kind: String, val line: Int)

@Serializable
data class DrillGraph(
    val project: Int, val name: String, val root: String,
    /** `ok`, `not indexed` or `missing`. */
    var state: String = "ok",
    var nodes: List<DrillNode> = emptyList(),
    var edges: List<DrillEdge> = emptyList(),
    /** Nodes left out by the cap. */
    var more: Int = 0,
    /** Some files changed since the last index run, so the picture lags the tree. */
    var partial: Boolean = false,
    var hits: List<DrillHit> = emptyList(),
)

object Drill {
    const val DEFAULT_LIMIT = 500
    /** The depth `impact` allows, so a focus never reaches further than the tools do. */
    private const val MAX_DEPTH = 4
    /** A name defined in more files than this resolves to none of them. */
    private const val AMBIGUOUS = 3
    private const val HITS = 8L

    /** Where a link starts or ends, as an index into the scans. */
    private sealed class End {
        data class Def(val i: Int) : End()
        data class File(val i: Int) : End()
        data class Ext(val i: Int) : End()
    }

    private class Link(val from: End, val to: End, val kind: DrillEdgeKind, val n: Long)

    internal fun nodeKind(kind: String) = when (kind) {
        "class", "struct", "enum", "interface", "trait", "type", "union", "impl" -> DrillNodeKind.Type
        "module", "namespace", "package" -> DrillNodeKind.Module
        else -> DrillNodeKind.Function
    }

    /** The scans of one project and what they resolve to. */
    private class Model(rt: Runtime, p: Project, key: String) {
        val defs: List<DefRow> = rt.store.symbolDefScan(key)
        val files: List<String>
        val fileOf: Map<String, Int>
        val byName = HashMap<String, MutableList<Int>>()
        /** The innermost definition around each definition, for `contains`. */
        val parent: List<Int?> = parents(defs)
        /** Definitions of linked projects, with the project that holds each. */
        val ext: List<Pair<Int, DefRow>>
        val links: List<Link>

        init {
            val refs = rt.store.symbolRefScan(key)
            val imports = rt.store.symbolImportEdges(key)
            val set = HashSet<String>()
            defs.forEach { set += it.path }; refs.forEach { set += it.path }; imports.forEach { set += it.first; set += it.second }
            files = set.sorted()
            fileOf = files.withIndex().associate { it.value to it.index }
            defs.forEachIndexed { i, d -> byName.getOrPut(d.name) { ArrayList() } += i }
            ext = externalDefs(rt, p, refs)
            links = resolve(refs, imports)
        }

        fun defIn(path: String, name: String): Int? = byName[name]?.firstOrNull { defs[it].path == path }

        private fun externalDefs(rt: Runtime, p: Project, refs: List<RefGroup>): List<Pair<Int, DefRow>> {
            val linked = rt.store.projectScope(p.id).drop(1)
            if (linked.isEmpty()) return emptyList()
            val names = refs.filter { it.name !in byName }.map { it.name }.distinct().sorted()
            return linked.flatMap { q -> rt.store.symbolDefsNamed(Index.canon(q.root), names).map { q.id to it } }
        }

        private fun resolve(refs: List<RefGroup>, imports: List<Pair<String, String>>): List<Link> {
            val extByName = HashMap<String, MutableList<Int>>()
            ext.forEachIndexed { i, (_, d) -> extByName.getOrPut(d.name) { ArrayList() } += i }
            val out = ArrayList<Link>()
            for (r in refs) {
                val from: End = defIn(r.path, r.scope)?.takeIf { r.scope.isNotEmpty() }?.let { End.Def(it) } ?: End.File(fileOf.getValue(r.path))
                val kind = if (r.kind == "implementation") DrillEdgeKind.Implements else DrillEdgeKind.Calls
                val local = byName[r.name].orEmpty()
                // A definition in the caller's own file wins over the same name elsewhere.
                val same = local.filter { defs[it].path == r.path }
                val to: List<End> = when {
                    same.isNotEmpty() -> same.map { End.Def(it) }
                    local.isNotEmpty() -> local.take(AMBIGUOUS + 1).map { End.Def(it) }
                    else -> extByName[r.name].orEmpty().take(AMBIGUOUS + 1).map { End.Ext(it) }
                }
                if (to.size > AMBIGUOUS) continue
                to.filter { it != from }.forEach { out += Link(from, it, kind, r.count) }
            }
            for ((a, b) in imports) out += Link(End.File(fileOf.getValue(a)), End.File(fileOf.getValue(b)), DrillEdgeKind.Imports, 1)
            return out
        }
    }

    /** `contains` between definitions: a stack of the spans still open at each line. */
    private fun parents(defs: List<DefRow>): List<Int?> {
        val out = MutableList<Int?>(defs.size) { null }
        val open = ArrayList<Int>()
        defs.forEachIndexed { i, d ->
            while (open.isNotEmpty() && (defs[open.last()].path != d.path || defs[open.last()].endLine < d.line)) open.removeAt(open.size - 1)
            out[i] = open.lastOrNull()
            if (d.endLine > d.line) open += i
        }
        return out
    }

    /** Definitions within [depth] calls of [focus], in either direction. */
    private fun neighbourhood(m: Model, focus: Int, depth: Int): Set<Int> {
        val adj = HashMap<Int, MutableList<Int>>()
        for (l in m.links) if (l.kind == DrillEdgeKind.Calls && l.from is End.Def && l.to is End.Def) {
            adj.getOrPut(l.from.i) { ArrayList() } += l.to.i; adj.getOrPut(l.to.i) { ArrayList() } += l.from.i
        }
        val seen = hashSetOf(focus)
        var level = listOf(focus)
        repeat(depth) { level = level.flatMap { d -> adj[d].orEmpty().filter { seen.add(it) } } }
        return seen
    }

    private fun symId(d: DefRow) = "s:${d.path}:${d.line}:${d.name}"

    private class Draw(val m: Model, val pid: Int, val shown: Set<Int>, val focusOnly: Boolean) {
        /** A definition outside the shown set stands for its file. */
        fun id(e: End): String = when (e) {
            is End.Def -> if (e.i in shown) symId(m.defs[e.i]) else "f:${m.defs[e.i].path}"
            is End.File -> "f:${m.files[e.i]}"
            is End.Ext -> m.ext[e.i].let { (p, d) -> "x:$p:${d.path}:${d.name}" }
        }

        fun node(id: String, e: End, stale: Set<String>): DrillNode {
            fun file(path: String) = DrillNode(id, DrillNodeKind.File, path.substringAfterLast('/'), path, 0, pid, "", 0, path in stale)
            return when {
                e is End.Def && e.i in shown -> m.defs[e.i].let { d -> DrillNode(id, nodeKind(d.kind), d.name, d.path, d.line, pid, d.signature, 0, d.path in stale) }
                e is End.Def -> file(m.defs[e.i].path)
                e is End.File -> file(m.files[e.i])
                else -> m.ext[(e as End.Ext).i].let { (p, d) -> DrillNode(id, DrillNodeKind.External, d.name, d.path, d.line, p, d.signature, 0, d.path in stale) }
            }
        }
    }

    fun run(rt: Runtime, req: DrillRequest): DrillGraph {
        val p = Projects.resolve(rt.store, req.project)
        val g = DrillGraph(p.id, p.displayName(), p.root)
        if (p.missing()) return g.apply { state = "missing" }
        val key = Index.canon(p.root)
        val cx = Ctx(rt)
        if (cx.symbolCount(key) == 0L) return g.apply { state = "not indexed" }
        if (req.query.isNotBlank()) g.hits = search(rt, p, req.query)
        val pending = GraphCore.pendingPaths(cx, p.root).toSet()
        g.partial = pending.isNotEmpty()
        val m = Model(rt, p, key)
        val focus = req.focus?.let { m.defIn(it.path, it.name) }
        val shown = m.defs.indices.filter { m.defs[it].path in req.expand }.toHashSet()
        val around = focus?.let { neighbourhood(m, it, (req.depth ?: 1).coerceIn(1, MAX_DEPTH)) }
        around?.let { shown += it }
        draw(g, Draw(m, p.id, shown, around != null), pending, req.limit ?: DEFAULT_LIMIT)
        return g
    }

    private data class EdgeKey(val a: String, val b: String, val kind: DrillEdgeKind) : Comparable<EdgeKey> {
        override fun compareTo(other: EdgeKey) = compareValuesBy(this, other, { it.a }, { it.b }, { it.kind })
    }

    private fun draw(g: DrillGraph, d: Draw, stale: Set<String>, limit: Int) {
        val m = d.m
        val ends = HashMap<String, End>()
        val edges = HashMap<EdgeKey, Long>()
        fun add(a: End, b: End, kind: DrillEdgeKind, n: Long) {
            val ia = d.id(a); val ib = d.id(b)
            if (ia == ib) return
            ends.getOrPut(ia) { a }; ends.getOrPut(ib) { b }
            val k = EdgeKey(ia, ib, kind); edges[k] = (edges[k] ?: 0L) + n
        }
        fun sym(e: End) = e is End.Def && e.i in d.shown
        for (l in m.links) {
            // Around a focus only the calls between shown symbols matter; the other files are noise.
            if (d.focusOnly && !(sym(l.from) && (sym(l.to) || l.to is End.Ext))) continue
            add(l.from, l.to, l.kind, l.n)
        }
        for (i in d.shown) {
            val parent = m.parent[i]?.takeIf { it in d.shown }?.let { End.Def(it) } ?: End.File(m.fileOf.getValue(m.defs[i].path))
            add(parent, End.Def(i), DrillEdgeKind.Contains, 1)
        }
        // Files with no edge at all are still part of the project.
        if (!d.focusOnly) m.files.forEachIndexed { f, path -> ends.getOrPut("f:$path") { End.File(f) } }
        val nodes = ends.entries.sortedBy { it.key }.map { (id, e) -> d.node(id, e, stale) }.toMutableList()
        val weight = HashMap<String, Long>()
        for ((k, n) in edges) { weight[k.a] = (weight[k.a] ?: 0L) + n; weight[k.b] = (weight[k.b] ?: 0L) + n }
        nodes.forEach { it.weight = weight[it.id] ?: 0L }
        // What the user asked to see outranks degree, so the cap never hides it.
        val asked = d.shown.map { symId(m.defs[it]) }.toSet()
        nodes.sortWith(compareBy<DrillNode>({ it.id !in asked }, { -it.weight }, { it.id }))
        g.more = (nodes.size - limit).coerceAtLeast(0)
        val kept = nodes.take(limit)
        val keptIds = kept.map { it.id }.toSet()
        g.edges = edges.entries.sortedBy { it.key }.filter { (k, _) -> k.a in keptIds && k.b in keptIds }.map { (k, n) -> DrillEdge(k.a, k.b, k.kind, n) }
        g.nodes = kept
    }

    private fun search(rt: Runtime, p: Project, query: String): List<DrillHit> =
        rt.store.projectScope(p.id).flatMap { q ->
            rt.store.symbolFts(Index.canon(q.root), query, HITS).map { DrillHit(q.id, it.b, it.a, it.c, it.d) }
        }
}
