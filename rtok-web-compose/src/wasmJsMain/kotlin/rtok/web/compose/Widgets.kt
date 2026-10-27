// Shared Compose building blocks: card panels, pills, KPI tiles, tables and Canvas charts.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SHAPE = RoundedCornerShape(10.dp)

/** Plain text in the theme's foreground (or [color]). */
@Composable
fun Txt(
    s: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    size: Int = 13,
    bold: Boolean = false,
) {
    val p = LocalPalette.current
    BasicText(
        s,
        modifier,
        style = TextStyle(color = color ?: p.fg, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
    )
}

/** Muted secondary text. */
@Composable
fun Muted(
    s: String,
    modifier: Modifier = Modifier,
) = Txt(s, modifier, LocalPalette.current.muted, 12)

/** A titled card. */
@Composable
fun Panel(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier.background(p.surface, SHAPE).border(1.dp, p.border, SHAPE).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(title, bold = true)
        sub?.let { Muted(it) }
        content()
    }
}

/** A small colored status label. */
@Composable
fun Pill(
    tone: String,
    label: String,
) {
    val c = LocalPalette.current.tone(tone)
    Box(Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Txt(label, color = c, size = 11)
    }
}

/** A KPI tile: label, value, hint and an optional chart. */
@Composable
fun RowScope.Kpi(
    label: String,
    value: String,
    hint: String,
    chart: (@Composable () -> Unit)? = null,
) {
    Panel(label, modifier = Modifier.weight(1f)) {
        Txt(value, size = 22, bold = true)
        Muted(hint)
        chart?.invoke()
    }
}

/** One table column: header, right alignment, weight and the cell. */
class Col<T>(
    val head: String,
    val weight: Float = 1f,
    val cell: @Composable (T) -> Unit,
)

/** A simple table, or [empty] text when there are no rows. */
@Composable
fun <T> Table(
    rows: List<T>,
    cols: List<Col<T>>,
    empty: String,
) {
    if (rows.isEmpty()) {
        Muted(empty)
        return
    }
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            cols.forEach { c -> Box(Modifier.weight(c.weight)) { Txt(c.head, color = p.muted, size = 11, bold = true) } }
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().border(0.5.dp, p.border).padding(vertical = 6.dp, horizontal = 2.dp)) {
                cols.forEach { c -> Box(Modifier.weight(c.weight)) { c.cell(r) } }
            }
        }
    }
}

/** Stacked bars: one bar per entry of [series], segments colored by [colors]. */
@Composable
fun StackedBars(
    series: List<List<Double>>,
    colors: List<Color>,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth().height(140.dp),
) {
    val max = series.maxOfOrNull { it.sum() }?.coerceAtLeast(1.0) ?: 1.0
    Canvas(modifier.semantics { contentDescription = label }) {
        if (series.isEmpty()) return@Canvas
        val w = size.width / series.size
        series.forEachIndexed { i, seg ->
            var y = size.height
            seg.forEachIndexed { j, v ->
                val h = (v / max * size.height).toFloat()
                y -= h
                drawRect(colors[j % colors.size], Offset(i * w + 1f, y), Size((w - 2f).coerceAtLeast(1f), h))
            }
        }
    }
}

/** A sparkline over [values]. */
@Composable
fun Sparkline(
    values: List<Double?>,
    label: String,
    color: Color = LocalPalette.current.accent,
    modifier: Modifier = Modifier.fillMaxWidth().height(32.dp),
) {
    val nums = values.filterNotNull()
    val max = nums.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(modifier.semantics { contentDescription = label }) {
        if (values.size < 2) return@Canvas
        val dx = size.width / (values.size - 1)
        val path = Path()
        var pen = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                pen = false
                return@forEachIndexed
            }
            val pt = Offset(i * dx, size.height - (v / max * size.height).toFloat())
            if (pen) path.lineTo(pt.x, pt.y) else path.moveTo(pt.x, pt.y)
            pen = true
        }
        drawPath(path, color, style = Stroke(2f))
    }
}

/** A horizontal share bar, 0..1. */
@Composable
fun ShareBar(
    share: Double,
    color: Color = LocalPalette.current.accent,
) {
    val track = LocalPalette.current.border
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRect(track)
        drawRect(color, size = Size((size.width * share.coerceIn(0.0, 1.0)).toFloat(), size.height))
    }
}
