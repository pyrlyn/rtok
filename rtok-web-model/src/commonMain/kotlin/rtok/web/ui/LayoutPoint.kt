// Our own 2D force layout for the Graph page's SVG scene (stands in for Scene2D.tsx/layout.ts and d3-force):
// a deterministic Fruchterman-Reingold pass, so one frame always draws the same picture.
package rtok.web.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A laid-out point. */
data class LayoutPoint(
    val x: Double,
    val y: Double,
)

private const val ITERATIONS = 120
private const val MIN_DIST = 0.01
private const val COOL = 0.95
private const val START_TEMP = 0.1
private const val MARGIN = 0.05

/**
 * Positions in the unit square [MARGIN, 1-MARGIN] for [ids], pulled together along [links].
 * Starts on a circle in id order, so the result depends only on the input.
 */
fun forceLayout(
    ids: List<String>,
    links: List<Pair<String, String>>,
): Map<String, LayoutPoint> {
    val n = ids.size
    if (n < 2) return ids.associateWith { LayoutPoint(HALF, HALF) }
    val index = ids.withIndex().associate { (i, id) -> id to i }
    val x = DoubleArray(n) { HALF + 0.4 * cos(2 * PI * it / n) }
    val y = DoubleArray(n) { HALF + 0.4 * sin(2 * PI * it / n) }
    val k = sqrt(1.0 / n)
    val e = links.mapNotNull { (a, b) -> index[a]?.let { i -> index[b]?.takeIf { it != i }?.let { j -> i to j } } }
    var t = START_TEMP
    repeat(ITERATIONS) {
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ux = x[i] - x[j]
                val uy = y[i] - y[j]
                val d = max(sqrt(ux * ux + uy * uy), MIN_DIST)
                val f = k * k / d / d
                dx[i] += ux * f
                dy[i] += uy * f
                dx[j] -= ux * f
                dy[j] -= uy * f
            }
        }
        for ((i, j) in e) {
            val ux = x[i] - x[j]
            val uy = y[i] - y[j]
            val d = max(sqrt(ux * ux + uy * uy), MIN_DIST)
            val f = d / k
            dx[i] -= ux * f
            dy[i] -= uy * f
            dx[j] += ux * f
            dy[j] += uy * f
        }
        for (i in 0 until n) {
            val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), MIN_DIST)
            x[i] += dx[i] / d * min(d, t)
            y[i] += dy[i] / d * min(d, t)
        }
        t *= COOL
    }
    return fit(ids, x, y)
}

private const val HALF = 0.5

private fun fit(
    ids: List<String>,
    x: DoubleArray,
    y: DoubleArray,
): Map<String, LayoutPoint> {
    val (x0, x1) = x.min() to x.max()
    val (y0, y1) = y.min() to y.max()
    val span = 1 - 2 * MARGIN

    fun s(
        v: Double,
        a: Double,
        b: Double,
    ) = if (b - a < MIN_DIST) HALF else MARGIN + (v - a) / (b - a) * span
    return ids.withIndex().associate { (i, id) -> id to LayoutPoint(s(x[i], x0, x1), s(y[i], y0, y1)) }
}
