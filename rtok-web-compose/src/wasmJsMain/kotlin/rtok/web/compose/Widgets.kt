// Shared Compose building blocks in the Pyrlyn kit's style: text, glass panels, chips, KPI tiles and tables
// (controls in Controls.kt, charts in Charts.kt).
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Text in IBM Plex Mono, the theme's foreground (or [color]); [tracking] is letter spacing in em. */
@Composable
fun Txt(
    s: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    size: Int = 13,
    bold: Boolean = false,
    tracking: Double = 0.0,
) {
    val p = LocalPalette.current
    val c = color ?: p.fg
    val style =
        TextStyle(
            color = c,
            fontSize = size.sp,
            fontFamily = LocalFonts.current,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = tracking.em,
        )
    if (hasGlyph(s)) {
        // Symbols missing from the font are drawn as vectors (Glyphs.kt).
        val (text, content) = withGlyphs(s, c)
        BasicText(text, modifier, style = style, inlineContent = content)
    } else {
        BasicText(s, modifier, style = style)
    }
}

/** Muted secondary text (Body/Small). */
@Composable
fun Muted(
    s: String,
    modifier: Modifier = Modifier,
) = Txt(s, modifier, LocalPalette.current.muted, 12)

/** Label/Kicker: the small upper-case label over titles, stat values and table columns. */
@Composable
fun Kicker(
    s: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) = Txt(s.uppercase(), modifier, color ?: LocalPalette.current.subtle, 11, bold = true, tracking = 0.08)

/** A glass panel with a Heading/Section title, an optional caption and an optional [action] on the right. */
@Composable
fun Panel(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().glass(p).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title.replaceFirstChar { it.uppercaseChar() }, size = 15, bold = true)
                sub?.let { Muted(it) }
            }
            action?.invoke()
        }
        content()
    }
}

/** A chip: the tone's tint, a faint outline of the same tone and its color for the text. */
@Composable
fun Pill(
    tone: String,
    label: String,
) {
    val c = LocalPalette.current.tone(tone)
    Box(
        Modifier
            .background(c.copy(alpha = 0.1f), PILL)
            .border(1.dp, c.copy(alpha = 0.22f), PILL)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Txt(label, color = c, size = 11, bold = true, tracking = 0.02)
    }
}

/** A KPI tile: kicker label, a Figure value, a hint and an optional chart. */
@Composable
fun RowScope.Kpi(
    label: String,
    value: String,
    hint: String,
    chart: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .glass(p, CARD)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Kicker(label)
        Txt(value, size = 26, bold = true, tracking = -0.02)
        Muted(hint)
        chart?.invoke()
    }
}

/** A row of KPI tiles, all as tall as the tallest. */
@Composable
fun KpiRow(content: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

/** One table column: header, weight and the cell. */
class Col<T>(
    val head: String,
    val weight: Float = 1f,
    val cell: @Composable (T) -> Unit,
)

/** A table: kicker headers, 44dp rows split by hairlines; [empty] text when there are no rows. */
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
    val line = LocalPalette.current.hairline
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            cols.forEach { c -> Box(Modifier.weight(c.weight)) { Kicker(c.head) } }
        }
        rows.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cols.forEach { c -> Box(Modifier.weight(c.weight)) { c.cell(r) } }
            }
        }
    }
}

/** A flexible gap in a row. */
@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))
