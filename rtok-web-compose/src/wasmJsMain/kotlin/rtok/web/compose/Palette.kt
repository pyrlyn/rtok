// Colors of the dark/light themes (the same tokens as :rtok-web-ui Styles.kt) and small text helpers.
package rtok.web.compose

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** One theme's palette. */
data class Palette(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val fail: Color,
)

val DARK =
    Palette(
        bg = Color(0xFF06101A),
        surface = Color(0xFF0C1824),
        border = Color(0xFF1C2C3C),
        fg = Color(0xFFE6EEF5),
        muted = Color(0xFF8A9BAC),
        accent = Color(0xFF4CC2FF),
        ok = Color(0xFF3DD68C),
        warn = Color(0xFFF5B841),
        fail = Color(0xFFFF5C6C),
    )

val LIGHT =
    Palette(
        bg = Color(0xFFF6F8FA),
        surface = Color(0xFFFFFFFF),
        border = Color(0xFFD8DEE4),
        fg = Color(0xFF0D1B2A),
        muted = Color(0xFF5A6B7C),
        accent = Color(0xFF0A7BC2),
        ok = Color(0xFF1A8F5A),
        warn = Color(0xFFB7791F),
        fail = Color(0xFFD03050),
    )

val LocalPalette = staticCompositionLocalOf { DARK }

/** The palette color for a tone name ("ok", "warn", "fail", "info"). */
fun Palette.tone(t: String): Color =
    when (t) {
        "ok", "pass" -> ok
        "warn" -> warn
        "fail", "error" -> fail
        else -> accent
    }
