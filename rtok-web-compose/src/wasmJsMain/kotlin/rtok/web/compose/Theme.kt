// The Pyrlyn kit's type, radii and surface effects (Figma "Web Pyrlyn UI"): IBM Plex Mono, glass panels, wells.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get

/** The UI typeface; null until the font files load (or if they fail), when text falls back to the canvas default. */
val LocalFonts = staticCompositionLocalOf<FontFamily?> { null }

/**
 * IBM Plex Mono, fetched from the bundle's `fonts/` (plain dist files: Compose resources would need a Res class in
 * commonMain, which the build-wide macosArm64 target cannot compile). The kit's Bold styles map to SemiBold, the
 * heaviest file shipped.
 */
object Fonts {
    var family by mutableStateOf<FontFamily?>(null)
        private set

    private val files = listOf("fonts/plex-mono-regular.ttf" to FontWeight.Normal, "fonts/plex-mono-semibold.ttf" to FontWeight.SemiBold)

    fun load() {
        val got = HashMap<FontWeight, ByteArray>()
        files.forEach { (url, weight) ->
            fetchBytes(url) { a ->
                // A missing file keeps the default font: the UI stays usable, only the typeface differs.
                if (a == null) return@fetchBytes
                got[weight] = ByteArray(a.length) { a[it] }
                if (got.size == files.size) family = FontFamily(files.map { (u, w) -> Font(u, got.getValue(w), w) })
            }
        }
    }
}

@JsFun("(url, done) => fetch(url).then(r => r.ok ? r.arrayBuffer() : Promise.reject(r.status)).then(b => done(new Int8Array(b)), () => done(null))")
private external fun fetchBytes(
    url: String,
    done: (Int8Array?) -> Unit,
)

/** Button kinds of the kit. */
enum class ButtonKind { GLASS, PRIMARY, WARN, DANGER }

val PANEL = RoundedCornerShape(20.dp)
val CARD = RoundedCornerShape(14.dp)
val NAV_ITEM = RoundedCornerShape(12.dp)
val FIELD = RoundedCornerShape(10.dp)
val CODE = RoundedCornerShape(6.dp)
val PILL = RoundedCornerShape(999.dp)

/** Glass/Panel: a translucent fill lit from the top, a hairline edge and a soft drop shadow; [lit] adds the accent glow. */
fun Modifier.glass(
    p: Palette,
    shape: Shape = PANEL,
    lit: Boolean = false,
): Modifier {
    val base =
        dropShadow(shape, Shadow(48.dp, p.shadow, spread = (-12).dp, offset = DpOffset(0.dp, 24.dp)))
            .dropShadow(shape, Shadow(16.dp, p.shadow.copy(alpha = p.shadow.alpha * 0.66f), spread = (-4).dp, offset = DpOffset(0.dp, 8.dp)))
    // Compose has no backdrop blur, so a glow under translucent glass would tint the whole panel: lit panels
    // get an opaque base over their shadows.
    val glow = if (lit) base.dropShadow(shape, Shadow(60.dp, p.accent.copy(alpha = 0.55f), spread = (-20).dp, offset = DpOffset(0.dp, 30.dp))).background(p.bg, shape) else base
    return glow
        .background(p.surface, shape)
        .background(Brush.verticalGradient(listOf(p.sheen, Color.Transparent)), shape)
        .border(1.dp, Brush.verticalGradient(listOf(if (lit) p.accent.copy(alpha = 0.45f) else p.outline, p.border)), shape)
}

/** Field/Well: a sunken fill for inputs, tracks and selected rows. */
fun Modifier.well(
    p: Palette,
    shape: Shape = FIELD,
): Modifier = background(p.well, shape).border(1.dp, p.hairline, shape)

/** Button/Primary and Nav/Active: the accent gradient with its glow. */
fun Modifier.accentFill(
    p: Palette,
    shape: Shape,
): Modifier =
    dropShadow(shape, Shadow(24.dp, p.accent.copy(alpha = 0.7f), spread = (-12).dp, offset = DpOffset(0.dp, 10.dp)))
        .background(Brush.verticalGradient(listOf(p.accentTop, p.accent)), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), p.accent)), shape)
