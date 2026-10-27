// The Graph page's cloud of graphs on WebGL (kotlin-webgl): one cluster per project (rtok mapping in
// `cloudClusters`), summaries when zoomed out, nodes and edges when zoomed in; clicking a cluster flies into it and
// opens its drill-down, clicking a node opens the node, "back" returns to the whole cloud.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import pyrlyn.webgl.Gl
import pyrlyn.webgl.Rgba
import pyrlyn.webgl.compose.CanvasInput
import pyrlyn.webgl.compose.GlScene
import pyrlyn.webgl.compose.WebGlCanvas
import pyrlyn.webgl.graph.Cloud
import pyrlyn.webgl.graph.CloudListener
import pyrlyn.webgl.graph.CloudPalette
import pyrlyn.webgl.graph.CloudRenderer
import pyrlyn.webgl.graph.CloudState
import pyrlyn.webgl.graph.Edge
import pyrlyn.webgl.graph.Graph
import pyrlyn.webgl.graph.Node
import pyrlyn.webgl.graph.Vec
import rtok.web.ui.CloudCluster
import rtok.web.ui.Marked
import rtok.web.ui.diffProjectOf
import rtok.web.ui.markGraph

/** Palette words in style-index order; index 0 is the default. */
private val TONES = listOf("muted", "accent", "ok", "warn", "fail")

private fun style(tone: String?) = TONES.indexOf(tone).coerceAtLeast(0)

private fun Color.rgba(a: Float = alpha) = Rgba(red, green, blue, a)

/** The generic cloud of rtok clusters. */
fun cloudOf(clusters: List<CloudCluster>): Cloud =
    Cloud(
        clusters.map { c ->
            Graph(
                c.id,
                c.title,
                c.nodes.map { Node(it.id, it.label, style(it.tone), if (it.big) 1.4 else 1.0, it.dim) },
                c.edges.map { Edge(it.from, it.to, if (it.tone == null) 0 else style(it.tone), it.dashed) },
                style(c.tone),
                c.count,
            )
        },
    )

private fun paletteOf(p: Palette) =
    CloudPalette(
        p.bg.rgba(),
        listOf(p.muted, p.accent, p.ok, p.warn, p.fail).map { it.rgba() },
        p.muted.rgba(0.6f),
    )

/** Adapts the generic renderer to the Compose WebGL host. */
private class CloudScene(
    val state: CloudState,
    val renderer: CloudRenderer,
) : GlScene {
    override fun init(gl: Gl) = renderer.init(gl)

    override fun frame(
        gl: Gl,
        width: Int,
        height: Int,
        dpr: Double,
        timeMs: Double,
    ) = renderer.frame(gl, width, height, dpr, timeMs)

    override fun input(e: CanvasInput): Boolean {
        val at = Vec(e.x, e.y)
        return when (e) {
            is CanvasInput.Down -> {
                state.down(at)
                false
            }

            is CanvasInput.Move -> {
                state.move(at, e.dragging)
            }

            is CanvasInput.Up -> {
                state.up(at)
            }

            is CanvasInput.Wheel -> {
                state.wheel(at, e.delta)
            }

            is CanvasInput.DoubleClick -> {
                false
            }
        }
    }

    override fun animating() = state.animating
}

/**
 * The cloud: [focused] is the drilled project (null for the whole cloud); [onFocus] opens or leaves a drill-down,
 * [onNode] opens a node of the focused project.
 */
@Composable
fun GraphCloud(
    clusters: List<CloudCluster>,
    focused: String?,
    onFocus: (String?) -> Unit,
    onNode: (String, String) -> Unit,
) {
    val p = LocalPalette.current
    val cloud = cloudOf(clusters)
    val scene = remember { CloudState(cloud).let { CloudScene(it, CloudRenderer(it, paletteOf(p))) } }
    val state = scene.state
    state.listener =
        object : CloudListener {
            override fun focused(cluster: String?) = onFocus(cluster)

            override fun nodeClicked(
                cluster: String,
                node: String,
            ) = onNode(cluster, node)
        }
    SideEffect {
        scene.renderer.palette = paletteOf(p)
        state.update(cloud)
        if (focused != state.focused) {
            if (focused == null) {
                state.back(notify = false)
            } else {
                state.enter(focused, notify = false)
            }
        }
    }
    if (focused != null) Box(Modifier.clickable { onFocus(null) }) { Txt("← all projects", color = p.accent) }
    WebGlCanvas(scene, Modifier.fillMaxWidth().aspectRatio(1.6f), "graph cloud: ${clusters.size} projects")
    Muted("drag to pan · wheel to zoom · click a project to fly in, a node to open it")
}

/** The drilled project's graph with its change marks, keyed by project id, once loaded. */
fun DrillController.marked(): Map<String, Marked> {
    val s = state()
    val g = graph?.takeIf { it.project.toString() == s?.project }
    return if (s == null || g == null) emptyMap() else mapOf(s.project to markGraph(g, diffProjectOf(diff, g.name), s.expand))
}

/** Opens node [id] of the drilled graph (a click in the cloud). */
fun DrillController.openNode(id: String) {
    val s = state() ?: return
    val g = graph?.takeIf { it.project.toString() == s.project } ?: return
    g.nodes.firstOrNull { it.id == id }?.let { openIn(this, s, g, it) }
}
