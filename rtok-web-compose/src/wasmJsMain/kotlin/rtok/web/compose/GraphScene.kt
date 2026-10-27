// The drill-down's 2D scene on Compose Canvas: `forceLayout` positions, change marks from `markGraph` as colours and
// text cues; a tap opens the nearest node.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rtok.web.ui.CHANGE_MARK
import rtok.web.ui.DGraph
import rtok.web.ui.DNode
import rtok.web.ui.Marked
import rtok.web.ui.edgeId
import rtok.web.ui.forceLayout
import kotlin.math.hypot

private fun changeColor(
    p: Palette,
    change: String?,
): Color =
    when (change) {
        "added" -> p.ok
        "removed" -> p.fail
        "changed" -> p.warn
        "moved" -> p.accent
        else -> p.muted
    }

/** The 2D scene: `forceLayout` positions, change marks as colours and text cues; a tap opens the nearest node. */
@Composable
fun Scene(
    m: Marked,
    onNode: (String) -> Unit,
) {
    val g = m.graph
    if (g.nodes.isEmpty()) return
    val at = remember(g) { forceLayout(g.nodes.map { it.id }, g.edges.map { it.from to it.to }) }
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .semantics { contentDescription = "${g.name} drill scene" }
            .pointerInput(g) {
                detectTapGestures { tap -> nodeAt(g, at, tap, size.width.toFloat(), size.height.toFloat(), 14.dp.toPx())?.let(onNode) }
            },
    ) {
        fun pos(id: String) = at[id]?.let { Offset(it.x.toFloat() * size.width, it.y.toFloat() * size.height) } ?: Offset.Zero
        g.edges.forEach { e ->
            val ch = m.edges[edgeId(e)]
            drawLine(
                changeColor(p, ch),
                pos(e.from),
                pos(e.to),
                strokeWidth = if (ch != null) 2f else 1f,
                pathEffect = if (e.kind == "contains") PathEffect.dashPathEffect(floatArrayOf(2f, 3f)) else null,
            )
        }
        g.nodes.forEach { n -> drawNode(n, m.nodes[n.id], pos(n.id), p, measurer) }
    }
}

/** The node within [radius] of [tap], nearest first. */
private fun nodeAt(
    g: DGraph,
    at: Map<String, rtok.web.ui.LayoutPoint>,
    tap: Offset,
    w: Float,
    h: Float,
    radius: Float,
): String? =
    g.nodes
        .mapNotNull { n -> at[n.id]?.let { n.id to hypot(it.x.toFloat() * w - tap.x, it.y.toFloat() * h - tap.y) } }
        .filter { it.second < radius }
        .minByOrNull { it.second }
        ?.first

private fun DrawScope.drawNode(
    n: DNode,
    ch: String?,
    at: Offset,
    p: Palette,
    measurer: TextMeasurer,
) {
    val fill = if (ch == null && n.kind == "file") p.accent else changeColor(p, ch)
    val r = (if (n.kind == "file") 7f else 4.5f).dp.toPx()
    drawCircle(fill.copy(alpha = if (n.stale) 0.5f else 1f), r, at)
    val mark = ch?.let { "${CHANGE_MARK[it]} " } ?: ""
    drawText(measurer, mark + n.label, at + Offset(9.dp.toPx(), -6.dp.toPx()), TextStyle(color = p.fg, fontSize = 10.sp))
}
