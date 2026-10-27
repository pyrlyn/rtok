// Our own small SVG charts (docs/web.md step 6: no JS chart library), standing in for web/src/charts:
// sparkline, mini bars, stacked bars and a gap-aware line. Each chart is one <svg> with a <title> label.
package rtok.web.ui

import kotlinx.html.FlowContent

private fun esc(s: String) =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun num(x: Double) = fixed(x, 1)

/** One SVG element of [w]×[h] (viewBox units) labelled [label], stretched to its box. */
private fun FlowContent.svg(
    w: Int,
    h: Int,
    label: String,
    body: String,
) {
    consumer.onTagContentUnsafe {
        +(
            "<svg class=\"chart\" viewBox=\"0 0 $w $h\" preserveAspectRatio=\"none\" role=\"img\" aria-label=\"${esc(label)}\">" +
                "<title>${esc(label)}</title>$body</svg>"
        )
    }
}

private const val SPARK_W = 120
private const val SPARK_H = 32
private const val PAD = 2.0

/** A sparkline of [values] (Sparkline.tsx). */
fun FlowContent.sparkline(
    values: List<Double>,
    label: String,
) {
    if (values.isEmpty()) return
    val max = values.max().coerceAtLeast(1.0)
    val dx = if (values.size > 1) SPARK_W / (values.size - 1.0) else 0.0
    val pts = values.mapIndexed { i, v -> "${num(i * dx)},${num(SPARK_H - PAD - v / max * (SPARK_H - 2 * PAD))}" }
    svg(SPARK_W, SPARK_H, label, "<polyline fill=\"none\" stroke=\"var(--accent)\" stroke-width=\"1.5\" points=\"${pts.joinToString(" ")}\"/>")
}

/** Mini bars of [values] (MiniBars.tsx). */
fun FlowContent.miniBars(
    values: List<Double>,
    label: String,
) = stackedBars(values.map { listOf(it) }, listOf("var(--accent)"), label, SPARK_W, SPARK_H)

/** Bars stacked from [series] per column, colored by [colors] (the calls-over-time chart). */
fun FlowContent.stackedBars(
    columns: List<List<Double>>,
    colors: List<String>,
    label: String,
    w: Int = 600,
    h: Int = 140,
) {
    if (columns.isEmpty()) return
    val max = columns.maxOf { it.sum() }.coerceAtLeast(1.0)
    val bw = w.toDouble() / columns.size
    val rects = StringBuilder()
    columns.forEachIndexed { i, col ->
        var y = h.toDouble()
        col.forEachIndexed { k, v ->
            val bh = v / max * (h - PAD)
            y -= bh
            if (bh > 0) {
                rects.append(
                    "<rect x=\"${num(i * bw + 1)}\" y=\"${num(y)}\" width=\"${num((bw - 2).coerceAtLeast(1.0))}\" " +
                        "height=\"${num(bh)}\" fill=\"${colors[k % colors.size]}\"/>",
                )
            }
        }
    }
    svg(w, h, label, rects.toString())
}

/** A line over [values] where null is a gap, never a zero (the savings trend). */
fun FlowContent.gapLine(
    values: List<Double?>,
    label: String,
    w: Int = 600,
    h: Int = 120,
) {
    val present = values.filterNotNull()
    if (present.isEmpty()) return
    val max = present.max().coerceAtLeast(1.0)
    val min = present.min().coerceAtMost(0.0)
    val dx = if (values.size > 1) w / (values.size - 1.0) else 0.0
    val y = { v: Double -> h - PAD - (v - min) / (max - min) * (h - 2 * PAD) }
    val runs = mutableListOf<MutableList<String>>()
    var open = false
    values.forEachIndexed { i, v ->
        if (v == null) {
            open = false
        } else {
            if (!open) runs += mutableListOf<String>()
            runs.last() += "${num(i * dx)},${num(y(v))}"
            open = true
        }
    }
    val body =
        runs.joinToString("") { r ->
            if (r.size == 1) {
                val (cx, cy) = r[0].split(',')
                "<circle cx=\"$cx\" cy=\"$cy\" r=\"2\" fill=\"var(--ok)\"/>"
            } else {
                "<polyline fill=\"none\" stroke=\"var(--ok)\" stroke-width=\"1.5\" points=\"${r.joinToString(" ")}\"/>"
            }
        }
    svg(w, h, label, body)
}

/** A horizontal share bar: [share] of the track filled (ShareBar.tsx). */
fun FlowContent.shareBar(
    share: Double,
    label: String,
    color: String = "var(--ok)",
) {
    val wpct = (share.coerceIn(0.0, 1.0) * PERCENT_F)
    consumer.onTagContentUnsafe {
        +(
            "<div class=\"track\" role=\"img\" aria-label=\"${esc(label)}\" title=\"${esc(label)}\">" +
                "<div style=\"width:${num(wpct)}%;background:$color\"></div></div>"
        )
    }
}

private const val PERCENT_F = 100.0

/** A stacked composition bar with a legend (parts.tsx `TokenMix`). */
fun FlowContent.mixBar(parts: List<Triple<String, Double, String>>) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1.0)
    val label = parts.joinToString(", ") { "${it.first} ${pct(it.second / total)}" }
    val segs = parts.joinToString("") { "<div style=\"width:${num(it.second / total * PERCENT_F)}%;background:${it.third}\"></div>" }
    val legend =
        parts.joinToString("") {
            "<span><i style=\"background:${it.third}\"></i>${esc(it.first)} ${compact(it.second)} · ${pct(it.second / total)}</span>"
        }
    consumer.onTagContentUnsafe { +"<div class=\"mix\" role=\"img\" aria-label=\"${esc(label)}\">$segs</div><div class=\"legend\">$legend</div>" }
}
