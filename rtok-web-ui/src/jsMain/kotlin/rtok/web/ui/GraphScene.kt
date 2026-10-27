// The drill-down's 2D scene (stands in for Scene2D.tsx): our own SVG over `forceLayout`, change marks from
// `markGraph` as colours and text cues, a health ring for project rows (HealthRing.tsx). 3D is deferred.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onKeyDownFunction
import kotlinx.html.unsafe
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.PI

private const val W = 640
private const val H = 400
private const val R_FILE = 7.0
private const val R_SYM = 4.5
private const val RING = 9.0
private const val RING_BOX = 22
private const val PERCENT = 100.0

private fun colorOf(change: String?) =
    when (change) {
        "added" -> "var(--ok)"
        "removed" -> "var(--fail)"
        "changed" -> "var(--warn)"
        "moved" -> "var(--accent)"
        else -> "var(--fg-muted)"
    }

private fun edgeSvg(
    e: DEdge,
    c: String?,
    x: (String) -> String,
    y: (String) -> String,
): String {
    val dash = if (e.kind == "contains") " stroke-dasharray=\"2 3\"" else ""
    val w = if (c != null) 2 else 1
    return "<line x1=\"${x(e.from)}\" y1=\"${y(e.from)}\" x2=\"${x(e.to)}\" y2=\"${y(e.to)}\" stroke=\"${colorOf(c)}\" stroke-width=\"$w\"$dash/>"
}

private fun nodeSvg(
    n: DNode,
    c: String?,
    x: (String) -> String,
    y: (String) -> String,
): String {
    val r = if (n.kind == "file") R_FILE else R_SYM
    val mark = c?.let { "${CHANGE_MARK[it]} " } ?: ""
    val fill = if (c == null && n.kind == "file") "var(--accent)" else colorOf(c)
    val at = if (n.line > 0) ":${n.line}" else ""
    val dim = if (n.stale) " opacity=\"0.5\"" else ""
    return "<g class=\"node\" data-node=\"${esc(n.id)}\" tabindex=\"0\" role=\"button\"><title>${esc("$mark${n.kind} ${n.label} ${n.path}$at")}</title>" +
        "<circle cx=\"${x(n.id)}\" cy=\"${y(n.id)}\" r=\"$r\" fill=\"$fill\"$dim/>" +
        "<text x=\"${x(n.id)}\" y=\"${y(n.id)}\" dx=\"9\" dy=\"3\" font-size=\"10\" fill=\"var(--fg)\">${esc(mark + n.label)}</text></g>"
}

/** The node id under a click or key event in the scene, if any. */
private fun nodeAt(e: Event): String? = (e.target as? Element)?.closest("[data-node]")?.getAttribute("data-node")

/** The scene of a (marked) drill frame; clicking a node (or Enter on it) calls [onNode] with its id. */
fun FlowContent.drillScene(
    m: Marked,
    onNode: (String) -> Unit,
) {
    val g = m.graph
    if (g.nodes.isEmpty()) return
    val at = forceLayout(g.nodes.map { it.id }, g.edges.map { it.from to it.to })

    fun x(id: String) = fixed((at[id]?.x ?: 0.0) * W, 1)

    fun y(id: String) = fixed((at[id]?.y ?: 0.0) * H, 1)
    val sb = StringBuilder()
    g.edges.forEach { sb.append(edgeSvg(it, m.edges[edgeId(it)], ::x, ::y)) }
    m.graph.nodes.forEach { sb.append(nodeSvg(it, m.nodes[it.id], ::x, ::y)) }
    div("scene-box") {
        onClickFunction = { e -> nodeAt(e)?.let(onNode) }
        onKeyDownFunction = { e -> if ((e as? KeyboardEvent)?.key == "Enter") nodeAt(e)?.let(onNode) }
        unsafe {
            +"<svg class=\"scene\" viewBox=\"0 0 $W $H\" role=\"img\" aria-label=\"drill scene\"><title>${esc(g.name)} drill-down</title>$sb</svg>"
        }
    }
}

/** A small ring for a health score (HealthRing.tsx); grey while indexing or missing. */
fun FlowContent.healthRing(
    score: Double?,
    level: String,
    title: String,
) {
    val c = 2 * PI * RING
    val frac = ((score ?: 0.0) / PERCENT).coerceIn(0.0, 1.0)
    val stroke =
        when (healthTone(level)) {
            "ok" -> "var(--ok)"
            "warn" -> "var(--warn)"
            "fail" -> "var(--fail)"
            else -> "var(--fg-muted)"
        }
    val half = RING_BOX / 2
    consumer.onTagContentUnsafe {
        +(
            "<svg class=\"ring\" width=\"$RING_BOX\" height=\"$RING_BOX\" viewBox=\"0 0 $RING_BOX $RING_BOX\" role=\"img\" aria-label=\"${esc(title)}\">" +
                "<title>${esc(title)}</title>" +
                "<circle cx=\"$half\" cy=\"$half\" r=\"$RING\" fill=\"none\" stroke=\"var(--border)\" stroke-width=\"3\"/>" +
                "<circle cx=\"$half\" cy=\"$half\" r=\"$RING\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"3\" " +
                "stroke-dasharray=\"${fixed(c * frac, 1)} ${fixed(c, 1)}\" transform=\"rotate(-90 $half $half)\"/></svg>"
        )
    }
}
