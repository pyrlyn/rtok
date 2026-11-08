// Kit controls: buttons, link text, notices, switches and filter chips.
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

/** A button; [enabled] false greys it out. */
@Composable
fun Action(
    label: String,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.GLASS,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val base =
        when (kind) {
            ButtonKind.PRIMARY -> if (enabled) Modifier.accentFill(p, FIELD) else Modifier.well(p)
            ButtonKind.WARN -> Modifier.background(p.warn.copy(alpha = 0.08f), FIELD).border(1.dp, p.warn.copy(alpha = 0.35f), FIELD)
            ButtonKind.DANGER -> Modifier.background(p.fail.copy(alpha = 0.08f), FIELD).border(1.dp, p.fail.copy(alpha = 0.35f), FIELD)
            ButtonKind.GLASS -> Modifier.glass(p, FIELD)
        }
    val color =
        when {
            !enabled -> p.subtle
            kind == ButtonKind.PRIMARY -> p.onAccent
            kind == ButtonKind.WARN -> p.warn
            kind == ButtonKind.DANGER -> p.fail
            else -> p.fg
        }
    Box(base.clickable(enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Txt(label, color = color, size = 12, bold = true)
    }
}

/** A link-like text action in the accent color. */
@Composable
fun LinkText(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Box(Modifier.clickable(enabled) { onClick() }) { Txt(label, color = LocalPalette.current.accentLight) }

/** A notice strip: the tone's tint with a bar on the left. */
@Composable
fun Notice(
    tone: String,
    text: String,
) {
    val p = LocalPalette.current
    val c = p.tone(tone)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.copy(alpha = 0.1f), FIELD)
            .border(1.dp, c.copy(alpha = 0.25f), FIELD)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(16.dp).background(c, PILL))
        Txt(text, color = p.fg, size = 12)
    }
}

/** Switch: an accent track with a glow when [on], a faint one when off. */
@Composable
fun Switch(
    on: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val track = if (on) Modifier.accentFill(p, PILL) else Modifier.background(p.outline, PILL)
    Box(
        track
            .width(34.dp)
            .height(20.dp)
            .clickable { onClick() }
            .padding(3.dp)
            .semantics { contentDescription = label },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).background(if (on || !p.dark) Color.White else p.muted, PILL))
    }
}

/** A chip that toggles a filter: accent tint and outline when [on]. */
@Composable
fun ToggleChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val look = if (on) Modifier.background(p.accent.copy(alpha = 0.16f), PILL).border(1.dp, p.accent.copy(alpha = 0.4f), PILL) else Modifier.border(1.dp, p.outline, PILL)
    Box(look.clickable { onClick() }.padding(horizontal = 10.dp, vertical = 3.dp)) {
        Txt(label, color = if (on) p.accentLight else p.muted, size = 11, bold = true)
    }
}
