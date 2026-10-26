// Port of src/plugins/graph/draw.rs (T329.31): the export as a picture. SVG is drawn from an `Export` and
// nothing else, so a saved file draws the same picture as the live scope. The layout is a pure function of
// the export (no random seed, no clock). PNG (resvg in Rust) is not ported: it needs a rasterizer.
package rtok.plugins.graph

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object Draw {
    /** More nodes than this are an unreadable hairball; the JSON keeps every node. */
    const val MAX_DRAWN = 200
    private const val MAX_LABELS = 40
    private const val SPACING = 30.0
    private const val NODE_R = 7.0
    private const val GAP = 56.0
    private const val HEAD = 44.0
    private const val MARGIN = 24.0
    private const val TITLE = 40.0
    private const val MIN_WIDTH = 900.0
    private const val MIN_PANEL = 220.0
    private const val LINE = 15.0
    private const val GOLDEN = 2.399_963
    private const val FONT = "Helvetica, Arial, 'DejaVu Sans', sans-serif"

    /** Okabe-Ito: distinguishable with the common colour-vision deficiencies. */
    private val COLORS = listOf("#0072B2", "#E69F00", "#009E73", "#CC79A7", "#56B4E9", "#D55E00", "#8E6BBF", "#7A7A7A")
    private const val BG = "#ffffff"
    private const val PANEL = "#f6f7f9"
    private const val INK = "#1b1f24"
    private const val MUTE = "#6b7280"
    private const val WARN = "#b45309"
    private const val DASH = " stroke-dasharray=\"4 3\""
    private const val ARROW = " marker-end=\"url(#arrow)\""

    /** `{:.N}` formatting without the exponent `Double.toString` may pick. */
    internal fun fixed(
        v: Double,
        digits: Int = 1,
    ): String {
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val n = round(v * scale).toLong()
        val neg = n < 0
        val a = abs(n)
        val whole = (a / scale).toString()
        val frac = if (digits == 0) "" else "." + (a % scale).toString().padStart(digits, '0')
        return (if (neg) "-" else "") + whole + frac
    }

    private fun f(v: Double) = fixed(v)

    /** Control characters are not legal XML, and one in a file name must not break the file. */
    internal fun esc(s: String) =
        s
            .map { if (it.isISOControl()) '?' else it }
            .joinToString("")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    internal fun short(
        s: String,
        max: Int,
    ): String {
        val cps = s.codePoints()
        if (cps.size <= max) return s
        return cps.take((max - 1).coerceAtLeast(0)).joinToString("") + "\u2026"
    }

    private fun String.codePoints(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < length) {
            val two = this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()
            out += substring(i, if (two) i + 2 else i + 1)
            i += if (two) 2 else 1
        }
        return out
    }

    private fun whenOf(secs: Long) = "${rtok.log.stamp(max(secs, 0))} UTC"

    private fun text(
        x: Double,
        y: Double,
        size: Int,
        fill: String,
        extra: String,
        s: String,
    ) = "<text x=\"${f(x)}\" y=\"${f(y)}\" font-size=\"$size\" fill=\"$fill\"$extra>${esc(s)}</text>\n"

    /** Text that stays readable over lines: a halo in the background colour. */
    private fun halo(bg: String) = " stroke=\"$bg\" stroke-width=\"3\" paint-order=\"stroke\""

    private fun line(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        width: Double,
        extra: String,
    ) = "<line x1=\"${f(a.first)}\" y1=\"${f(a.second)}\" x2=\"${f(b.first)}\" y2=\"${f(b.second)}\" stroke=\"$MUTE\" stroke-width=\"${f(width)}\"$extra/>\n"

    /** Where a line from [a] to [b] leaves a box of half size `(hw, hh)` around [a]. */
    private fun edgeOf(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        hw: Double,
        hh: Double,
    ): Pair<Double, Double> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val t = min(min(hw / abs(dx), hh / abs(dy)), 1.0)
        return if (t.isFinite()) (a.first + dx * t) to (a.second + dy * t) else a
    }

    /** `{}` of an f64 as Rust prints it: no `.0` on a whole number. */
    private fun num(v: Double) = if (v == round(v)) v.toLong().toString() else v.toString()

    /** Circle: function, square: type, diamond: module (the kinds `drill` tells apart). */
    private fun shape(
        kind: String,
        at: Pair<Double, Double>,
        fill: String,
        stroke: String,
        sw: Double,
    ): String {
        val (x, y) = at
        val style = "fill=\"$fill\" stroke=\"$stroke\" stroke-width=\"${num(sw)}\""
        val r = NODE_R
        return when (Drill.nodeKind(kind)) {
            DrillNodeKind.Type -> {
                "<rect x=\"${f(x - r)}\" y=\"${f(y - r)}\" width=\"${f(2 * r)}\" height=\"${f(2 * r)}\" rx=\"2\" $style/>"
            }

            DrillNodeKind.Module -> {
                val d = r + 2
                "<polygon points=\"${f(x)},${f(y - d)} ${f(x + d)},${f(y)} ${f(x)},${f(y + d)} ${f(x - d)},${f(y)}\" $style/>"
            }

            else -> {
                "<circle cx=\"${f(x)}\" cy=\"${f(y)}\" r=\"${num(r)}\" $style/>"
            }
        } + "\n"
    }

    private class Spot(
        val node: ExportNode,
        val at: Pair<Double, Double>,
    )

    private class Grid(
        val pw: Double,
        val ph: Double,
        val cols: Int,
        val x0: Double,
        val y0: Double,
        val gh: Double,
        val width: Double,
    ) {
        fun corner(pi: Int) = (x0 + (pi % cols) * (pw + GAP)) to (y0 + (pi / cols) * (ph + GAP))

        fun centre(pi: Int) = corner(pi).let { (it.first + pw / 2) to (it.second + ph / 2) }
    }

    private fun color(pi: Int) = COLORS[pi % COLORS.size]

    private fun grid(
        projects: Int,
        widest: Int,
    ): Grid {
        val (pw, ph) =
            if (widest == 0) {
                MIN_PANEL to 80.0
            } else {
                val side = max(2 * (SPACING * sqrt(widest.toDouble()) + SPACING + NODE_R), MIN_PANEL)
                side to side + HEAD
            }
        val n = max(projects, 1)
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = (n + cols - 1) / cols
        val gw = cols * pw + (cols - 1) * GAP
        val gh = rows * ph + (rows - 1) * GAP
        val width = max(gw + 2 * MARGIN, MIN_WIDTH)
        return Grid(pw, ph, cols, (width - gw) / 2, TITLE + MARGIN, gh, width)
    }

    /** [transparent] leaves the background out, for a picture that goes onto someone else's page. */
    fun svg(
        e: Export,
        transparent: Boolean,
    ): String {
        val at = HashMap<Int, Int>()
        e.projects.forEachIndexed { i, p -> at.getOrPut(p.id) { i } }
        val degree = HashMap<String, Int>()
        for (edge in e.edges) for (end in listOf(edge.from, edge.to)) degree[end] = (degree[end] ?: 0) + 1
        // The best connected nodes survive the cap: they are the ones a reader is looking for.
        val all = e.nodes.indices.sortedWith(compareBy({ -(degree[e.nodes[it].id] ?: 0) }, { it }))
        val hidden = (all.size - MAX_DRAWN).coerceAtLeast(0)
        val order = all.take(MAX_DRAWN)
        val per = List(e.projects.size) { ArrayList<Int>() }
        for (i in order) at[e.nodes[i].project]?.let { per.getOrNull(it)?.add(i) }
        val g = grid(e.projects.size, per.maxOfOrNull { it.size } ?: 0)
        val spots = ArrayList<Spot>()
        val spotOf = HashMap<String, Int>()
        per.forEachIndexed { pi, group ->
            val cx = g.corner(pi).first + g.pw / 2
            val cy = g.corner(pi).second + HEAD + (g.ph - HEAD) / 2
            group.forEachIndexed { rank, i ->
                // Sunflower spiral: the hubs sit in the middle and no two nodes land on one point.
                val r = SPACING * sqrt(rank + 0.5)
                val a = rank * GOLDEN
                spotOf[e.nodes[i].id] = spots.size
                spots += Spot(e.nodes[i], (cx + r * cos(a)) to (cy + r * sin(a)))
            }
        }
        val body = StringBuilder()
        val linkLabels = StringBuilder()
        for (l in e.links) {
            val a = at[l.from] ?: continue
            val b = at[l.to] ?: continue
            if (a == b) continue
            val s = edgeOf(g.centre(a), g.centre(b), g.pw / 2, g.ph / 2)
            val t = edgeOf(g.centre(b), g.centre(a), g.pw / 2, g.ph / 2)
            body.append(line(s, t, 1.0 + min(log2(1.0 + l.references), 4.0) * 0.6, ARROW))
            val mid = ((s.first + t.first) / 2) to ((s.second + t.second) / 2 - 4)
            linkLabels.append(text(mid.first, mid.second, 10, MUTE, " text-anchor=\"middle\"${halo(BG)}", "${l.kind} \u00b7 ${l.references} calls"))
        }
        e.projects.forEachIndexed { pi, pr ->
            val (x, y) = g.corner(pi)
            body.append("<rect x=\"${f(x)}\" y=\"${f(y)}\" width=\"${f(g.pw)}\" height=\"${f(g.ph)}\" rx=\"10\" fill=\"$PANEL\" stroke=\"${color(pi)}\" stroke-width=\"2\"/>\n")
            body.append(text(x + 12, y + 20, 14, INK, " font-weight=\"bold\"", short(pr.name, 28)))
            body.append(text(x + 12, y + 35, 10, MUTE, "", "${pr.backend} \u00b7 ${pr.health}"))
        }
        for (edge in e.edges) {
            val s = spots[spotOf[edge.from] ?: continue]
            val t = spots[spotOf[edge.to] ?: continue]
            val len = hypot(t.at.first - s.at.first, t.at.second - s.at.second)
            if (len < 1.0) continue
            // Stop short of the callee so the arrow head is not under its shape.
            val k = (len - NODE_R - 3) / len
            val end = (s.at.first + (t.at.first - s.at.first) * k) to (s.at.second + (t.at.second - s.at.second) * k)
            val dash = if (s.node.project == t.node.project) "" else DASH
            body.append(line(s.at, end, 1.0, " stroke-opacity=\"0.7\"$dash$ARROW"))
        }
        val labels = StringBuilder()
        spots.forEachIndexed { i, sp ->
            val focus = e.meta.focus == sp.node.name
            val pi = at[sp.node.project] ?: 0
            body.append(shape(sp.node.kind, sp.at, color(pi), if (focus) INK else PANEL, if (focus) 3.0 else 1.5))
            if (focus || i < MAX_LABELS) labels.append(text(sp.at.first + NODE_R + 3, sp.at.second + 3, 9, INK, halo(PANEL), short(sp.node.name, 22)))
        }
        body.append(labels).append(linkLabels)
        val ly = g.y0 + g.gh + MARGIN
        body.append(legend(e, ly))
        val foot = footer(e, hidden)
        val fy = ly + 4 * 20.0 + 10
        foot.forEachIndexed { i, (s, warn) -> body.append(text(MARGIN, fy + i * LINE, 11, if (warn) WARN else MUTE, "", s)) }
        val height = fy + foot.size * LINE + MARGIN
        val bg = if (transparent) "" else "<rect width=\"100%\" height=\"100%\" fill=\"$BG\"/>"
        val title = text(MARGIN, 30.0, 18, INK, " font-weight=\"bold\"", "Code graph: ${short(e.meta.scope.joinToString(", "), 90)}")
        val w = fixed(g.width, 0)
        val h = fixed(height, 0)
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" font-family=\"$FONT\">\n" +
            "<defs><marker id=\"arrow\" viewBox=\"0 0 8 8\" refX=\"7\" refY=\"4\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\"><path d=\"M0 0L8 4L0 8z\" fill=\"$MUTE\"/></marker></defs>\n" +
            "$bg\n$title$body</svg>\n"
    }

    /** Three rows: project colours, node shapes, edge styles; each entry is a mark and a label. */
    private fun legend(
        e: Export,
        y: Double,
    ): String {
        val out = StringBuilder()
        var x = MARGIN

        fun put(
            row: Double,
            mark: String,
            label: String,
        ) {
            out.append(mark).append(text(x + 18, y + row * 20 + 4, 11, INK, "", label))
            x += 30 + label.codePoints().size * 6.2
        }
        val shown = min(e.projects.size, COLORS.size)
        e.projects.take(shown).forEachIndexed { i, pr ->
            put(0.0, "<rect x=\"${f(x)}\" y=\"${f(y - 6)}\" width=\"12\" height=\"12\" rx=\"2\" fill=\"${COLORS[i]}\"/>\n", short(pr.name, 24))
        }
        if (e.projects.size > shown) put(0.0, "", "+${e.projects.size - shown} more")
        x = MARGIN
        for ((kind, label) in listOf("function" to "function or method", "struct" to "type", "module" to "module")) {
            put(1.0, shape(kind, (x + 6) to (y + 20), MUTE, MUTE, 1.0), label)
        }
        x = MARGIN
        for ((extra, width, label) in listOf(
            Triple("", 1.0, "call within a project (caller to callee)"),
            Triple(DASH, 1.0, "call across projects"),
            Triple("", 3.0, "project link (width: calls across it)"),
        )) {
            put(2.0, line(x to (y + 40), (x + 14) to (y + 40), width, extra), label)
        }
        return out.toString()
    }

    /** The lines under the picture; the flag marks the ones to draw as a warning. */
    private fun footer(
        e: Export,
        hidden: Int,
    ): List<Pair<String, Boolean>> {
        val m = e.meta
        val focus = m.focus?.let { " $it, depth ${m.depth ?: 0}" } ?: ""
        val redacted = if (m.redacted) " \u00b7 paths redacted" else ""
        val lines = mutableListOf("rtok ${m.rtokVersion} \u00b7 exported ${whenOf(m.exportedAt)} \u00b7 scope: ${m.scope.joinToString(", ")} \u00b7 level: ${m.level}$focus$redacted" to false)
        if (m.partial) lines += "PARTIAL: a project was still indexing, not indexed or could not answer" to true
        if (hidden > 0) lines += "$hidden nodes hidden: the $MAX_DRAWN best connected are drawn, the JSON export has all ${e.nodes.size}" to true
        for (pr in e.projects) lines += "${short(pr.name, 40)}: backend ${pr.backend}, ${pr.health}, indexed ${pr.indexedAt?.let(::whenOf) ?: "never"}" to false
        for (n in m.notes.take(3)) lines += short(n, 150) to false
        return lines
    }
}
