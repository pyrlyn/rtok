// The `graph` reply of the Graph page's drill-down (drill.rs `DrillGraph`), as DrillView.tsx reads it.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** One node of a drill frame. */
data class DNode(
    val id: String,
    val kind: String,
    val label: String,
    val path: String,
    val line: Long,
    val project: Long,
    val weight: Long = 0,
    val stale: Boolean = false,
)

/** One edge of a drill frame. */
data class DEdge(
    val from: String,
    val to: String,
    val kind: String,
    val count: Long = 1,
)

/** A `graph` reply (drill.rs `DrillGraph`). */
data class DGraph(
    val project: Long,
    val name: String,
    val root: String,
    val state: String,
    val nodes: List<DNode>,
    val edges: List<DEdge>,
    val more: Long = 0,
    val partial: Boolean = false,
)

private fun JsonObject.long(k: String) = num(k)?.toLong() ?: 0L

/** The `graph` field of a `graph` frame, or null when it is not one. */
fun parseDrillGraph(o: JsonObject?): DGraph? {
    o ?: return null
    return DGraph(
        o.long("project"),
        o.str("name") ?: "",
        o.str("root") ?: "",
        o.str("state") ?: "ok",
        o.objects("nodes").map {
            DNode(it.str("id") ?: "", it.str("kind") ?: "", it.str("label") ?: "", it.str("path") ?: "", it.long("line"), it.long("project"), it.long("weight"), it.flag("stale"))
        },
        o.objects("edges").map { DEdge(it.str("from") ?: "", it.str("to") ?: "", it.str("kind") ?: "", it.long("count")) },
        o.long("more"),
        o.flag("partial"),
    )
}
