// Canvas charts in the kit's colors: stacked bars, sparklines and meters.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Stacked bars: one bar per entry of [series], segments colored by [colors], rounded on top. */
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
        val gap = (w * 0.18f).coerceIn(1f, 6f)
        val r = CornerRadius(((w - gap) / 4).coerceIn(0f, 4.dp.toPx()))
        series.forEachIndexed { i, seg ->
            var y = size.height
            seg.forEachIndexed { j, v ->
                val h = (v / max * size.height).toFloat()
                if (h <= 0f) return@forEachIndexed
                y -= h
                val c = colors[j % colors.size]
                drawRoundRect(
                    Brush.verticalGradient(listOf(c, c.copy(alpha = 0.55f)), y, y + h),
                    Offset(i * w + gap / 2, y),
                    Size((w - gap).coerceAtLeast(1f), (h - 1f).coerceAtLeast(1f)),
                    r,
                )
            }
        }
    }
}

/** A sparkline over [values] with a fading area under it. */
@Composable
fun Sparkline(
    values: List<Double?>,
    label: String,
    color: Color = LocalPalette.current.teal,
    modifier: Modifier = Modifier.fillMaxWidth().height(32.dp),
) {
    val nums = values.filterNotNull()
    val max = nums.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(modifier.semantics { contentDescription = label }) {
        if (values.size < 2) return@Canvas
        val dx = size.width / (values.size - 1)
        val line = Path()
        val area = Path()
        var pen = false
        var start = 0f
        values.forEachIndexed { i, v ->
            if (v == null) {
                if (pen) area.lineTo((i - 1) * dx, size.height)
                pen = false
                return@forEachIndexed
            }
            val pt = Offset(i * dx, size.height - (v / max * size.height).toFloat())
            if (pen) {
                line.lineTo(pt.x, pt.y)
                area.lineTo(pt.x, pt.y)
            } else {
                line.moveTo(pt.x, pt.y)
                start = pt.x
                area.moveTo(pt.x, size.height)
                area.lineTo(pt.x, pt.y)
            }
            pen = true
        }
        if (pen) area.lineTo((values.size - 1) * dx, size.height)
        area.lineTo(start, size.height)
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Meter: a share 0..1 as an accent-to-teal bar in a sunken pill track. */
@Composable
fun ShareBar(
    share: Double,
    color: Color? = null,
) {
    val p = LocalPalette.current
    val brush = color?.let { Brush.horizontalGradient(listOf(it.copy(alpha = 0.7f), it)) } ?: Brush.horizontalGradient(listOf(p.accent, p.teal))
    Box(Modifier.fillMaxWidth().well(p, PILL).padding(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val w = (size.width * share.coerceIn(0.0, 1.0)).toFloat()
            if (w > 0f) drawRoundRect(brush, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}
