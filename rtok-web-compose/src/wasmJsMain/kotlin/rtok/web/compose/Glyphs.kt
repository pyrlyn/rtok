// Symbols the bundled UI font has no glyph for (they rendered as tofu boxes): drawn as small SVG-path vectors
// inline with the text instead. `Txt` swaps them in automatically, so callers keep writing "⌘K" or "a → b".
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** One symbol: SVG path data on a 24×24 view box, stroked unless [filled]. */
private class Glyph(
    val path: String,
    val filled: Boolean = false,
)

private val GLYPHS =
    mapOf(
        '⌘' to Glyph("M9 9V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V9"),
        '●' to Glyph("M12 6a6 6 0 1 0 0.01 0z", filled = true),
        '←' to Glyph("M20 12H4M10 6l-6 6 6 6"),
        '→' to Glyph("M4 12h16M14 6l6 6-6 6"),
        '≈' to Glyph("M4 9.5c2.7-2.6 5.3 2.6 8 0s5.3 2.6 8 0M4 15.5c2.7-2.6 5.3 2.6 8 0s5.3 2.6 8 0"),
        '−' to Glyph("M5 12h14"),
        '×' to Glyph("M7 7l10 10M17 7L7 17"),
        '✓' to Glyph("M4 12.5l5 5L20 6.5"),
        '✗' to Glyph("M6 6l12 12M18 6L6 18"),
        '☐' to Glyph("M4.5 4.5h15v15h-15z"),
        '☑' to Glyph("M4.5 4.5h15v15h-15zM8 12l3 3 5-6"),
        'Σ' to Glyph("M17.5 5H6.5l6.5 7-6.5 7h11"),
        '§' to Glyph("M15 6.5a3 3 0 0 0-6 .5c0 3 6 2.5 6 6 0 1.4-1.3 2.4-3 2.5M9 17.5a3 3 0 0 0 6-.5c0-3-6-2.5-6-6 0-1.4 1.3-2.4 3-2.5"),
    )

/** Whether [s] holds a symbol drawn as a vector. */
internal fun hasGlyph(s: String) = s.any { it in GLYPHS }

private val VECTORS = HashMap<Char, ImageVector>()

private fun vectorOf(c: Char): ImageVector =
    VECTORS.getOrPut(c) {
        val g = GLYPHS.getValue(c)
        ImageVector
            .Builder("glyph-$c", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(g.path),
                fill = if (g.filled) SolidColor(Color.Black) else null,
                stroke = if (g.filled) null else SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()
    }

/** [s] with each vector symbol replaced by an inline placeholder, and the content that draws them in [color]. */
internal fun withGlyphs(
    s: String,
    color: Color,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val used = s.filter { it in GLYPHS }.toSet()
    val text =
        buildAnnotatedString {
            s.forEach { c -> if (c in GLYPHS) appendInlineContent("g$c", c.toString()) else append(c) }
        }
    val content =
        used.associate { c ->
            "g$c" to
                InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Image(rememberVectorPainter(vectorOf(c)), c.toString(), Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(color))
                }
        }
    return text to content
}
