// Which graphs form the Graph page's cloud: one cluster per registered project, sized by its indexed files; a project
// whose drill graph is loaded also carries its nodes and edges with change marks. Tones are palette words
// (ok/warn/fail/accent/muted) so the renderer stays rtok-agnostic.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** One node of a cloud cluster. */
data class CloudNode(
    val id: String,
    val label: String,
    val tone: String,
    val big: Boolean,
    val dim: Boolean,
)

/** One edge of a cloud cluster; [tone] is null for an unchanged edge. */
data class CloudEdge(
    val from: String,
    val to: String,
    val tone: String?,
    val dashed: Boolean,
)

/** One project's cluster. */
data class CloudCluster(
    val id: String,
    val title: String,
    val tone: String,
    val count: Int,
    val nodes: List<CloudNode>,
    val edges: List<CloudEdge>,
)

/** The palette word for a change mark. */
fun changeTone(change: String?): String? =
    when (change) {
        "added" -> "ok"
        "removed" -> "fail"
        "changed" -> "warn"
        "moved" -> "accent"
        else -> null
    }

/** The clusters of [projects] rows; [loaded] holds marked drill graphs by project id. */
fun cloudClusters(
    projects: List<JsonObject>,
    loaded: Map<String, Marked>,
): List<CloudCluster> =
    projects.mapNotNull { p ->
        val id = p.num("id")?.toLong()?.toString() ?: return@mapNotNull null
        val health = p.obj("health")
        val level = health?.str("level") ?: health?.num("score")?.let(::levelOf)
        val m = loaded[id]
        val files = p.obj("index")?.num("files")?.toInt() ?: 0
        CloudCluster(
            id = id,
            title = p.str("name") ?: "project $id",
            tone = if (p.flag("missing")) "fail" else level?.let(::healthTone) ?: "muted",
            count = maxOf(files, m?.graph?.nodes?.size ?: 0),
            nodes = m?.let(::cloudNodes) ?: emptyList(),
            edges = m?.let(::cloudEdges) ?: emptyList(),
        )
    }

private fun cloudNodes(m: Marked) =
    m.graph.nodes.map { n ->
        val file = n.kind == "file"
        CloudNode(n.id, n.label, changeTone(m.nodes[n.id]) ?: if (file) "accent" else "muted", file, n.stale)
    }

private fun cloudEdges(m: Marked) = m.graph.edges.map { e -> CloudEdge(e.from, e.to, changeTone(m.edges[edgeId(e)]), e.kind == "contains") }
