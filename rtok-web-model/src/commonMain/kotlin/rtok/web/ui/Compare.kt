// Port of web/src/pages/graph3d/compare.ts: a `diff` reply laid over a drill frame (`markGraph`).
package rtok.web.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The change marks of compare.ts. */
val CHANGE_MARK = mapOf("added" to "+", "removed" to "−", "changed" to "~", "moved" to "→")

/** The pill tone of a change. */
val CHANGE_TONE = mapOf("added" to "ok", "removed" to "fail", "changed" to "warn", "moved" to "info")

/** A drill frame with a diff laid over it: ghosts for removed symbols, and the change per node and edge id. */
data class Marked(
    val graph: DGraph,
    val nodes: Map<String, String>,
    val edges: Map<String, String>,
)

/** `kind:from>to`. */
fun edgeId(e: DEdge) = "${e.kind}:${e.from}>${e.to}"

private val TYPES = setOf("class", "struct", "enum", "interface", "trait", "type", "union", "impl")
private val MODULE_KINDS = setOf("module", "namespace", "package")

private fun nodeKind(kind: String) =
    when (kind) {
        in TYPES -> "type"
        in MODULE_KINDS -> "module"
        else -> "function"
    }

private data class DiffSym(
    val name: String,
    val kind: String,
    val path: String,
    val line: Long,
)

private fun JsonObject.sym() = DiffSym(str("name") ?: "", str("kind") ?: "", str("path") ?: "", num("line")?.toLong() ?: 0)

private fun JsonObject.syms(k: String) = objects(k).map { it.sym() }

private fun JsonObject.moves(k: String) = objects(k).mapNotNull { it.obj("to")?.sym() }

private class Marker(
    val g: DGraph,
    val d: JsonObject,
    expanded: List<String>,
) {
    private val byDef = HashMap<String, String>()
    private val inFile = HashMap<String, MutableSet<String>>()
    private val symbols = g.nodes.filter { it.kind != "file" && it.project == g.project }
    val nodes = LinkedHashMap<String, String>()
    val edges = LinkedHashMap<String, String>()
    val ghosts: List<DNode>
    val ghostEdges = mutableListOf<DEdge>()

    init {
        d.syms("added").forEach { note(it, "added") }
        d.syms("changed").forEach { note(it, "changed") }
        d.moves("renamed").forEach { note(it, "changed") }
        d.moves("moved").forEach { note(it, "moved") }
        val removed = d.syms("removed")
        removed.forEach { note(it, "removed") }
        g.nodes.forEach { n -> changeOf(n)?.let { nodes[n.id] = it } }
        ghosts = removed.filter { it.path in expanded }.map { DNode("d:${it.path}:${it.line}:${it.name}", nodeKind(it.kind), it.name, it.path, it.line, g.project) }
        ghosts.forEach { nodes[it.id] = "removed" }
        ghosts.mapTo(ghostEdges) { DEdge("f:${it.path}", it.id, "contains") }
    }

    private fun note(
        s: DiffSym,
        change: String,
    ) {
        if (change != "removed") byDef["${s.path}\u0000${s.name}"] = change
        inFile.getOrPut(s.path) { linkedSetOf() } += change
    }

    private fun changeOf(n: DNode): String? =
        when {
            n.kind == "file" -> inFile[n.path]?.let { if (it.size == 1) it.first() else "changed" }
            n.project == g.project -> byDef["${n.path}\u0000${n.label}"]
            else -> null
        }

    private fun caller(e: JsonObject) = symbols.firstOrNull { it.path == e.str("path") && it.label == e.str("scope") }?.id ?: "f:${e.str("path")}"

    private fun callee(e: JsonObject) = (symbols + ghosts).singleOrNull { it.label == e.str("name") }?.id

    fun markEdges() {
        val all = (g.nodes + ghosts).map { it.id }.toSet()
        d.objects("edges_removed").forEach { e ->
            val to = callee(e)
            val from = caller(e)
            if (to != null && from in all) {
                val edge = DEdge(from, to, "calls")
                ghostEdges += edge
                edges[edgeId(edge)] = "removed"
            }
        }
        d.objects("edges_added").forEach { e ->
            val to = callee(e)
            val from = caller(e)
            g.edges.firstOrNull { it.kind == "calls" && it.from == from && it.to == to }?.let { edges[edgeId(it)] = "added" }
        }
    }
}

/** compare.ts `markGraph`: [d] is one `DiffProject` of the `diff` reply, or null. */
fun markGraph(
    g: DGraph,
    d: JsonObject?,
    expanded: List<String>,
): Marked {
    d ?: return Marked(g, emptyMap(), emptyMap())
    val m = Marker(g, d, expanded)
    m.markEdges()
    return Marked(g.copy(nodes = g.nodes + m.ghosts, edges = g.edges + m.ghostEdges), m.nodes, m.edges)
}

/** The `DiffProject` named [name] in a `diff` reply's `diff`, or null. */
fun diffProjectOf(
    report: JsonObject?,
    name: String,
): JsonObject? = report?.objects("projects")?.firstOrNull { it.str("project") == name }

/** Counts per change kind of a `DiffProject`, for the compare summary. */
fun diffCounts(p: JsonObject): List<Pair<String, Int>> = listOf("added", "removed", "changed", "renamed", "moved", "edges_added", "edges_removed").map { it to ((p[it] as? JsonArray)?.size ?: 0) }
