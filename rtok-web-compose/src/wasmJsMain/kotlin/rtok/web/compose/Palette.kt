// Colors of the dark/light themes. Dark is the Web Pyrlyn UI kit's token set (Figma QyTinZvmfi2gNVrBh5V7ru);
// the kit has no light mode, so LIGHT keeps its accents and hue relations on a pale glass base.
package rtok.web.compose

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** One theme's palette; the first nine names predate the Pyrlyn kit and every page reads them. */
data class Palette(
    val bg: Color,
    /** Glass panel fill. */
    val surface: Color,
    /** Panel edge. */
    val border: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val fail: Color,
    val subtle: Color,
    val label: Color,
    val accentLight: Color,
    val accentTop: Color,
    val teal: Color,
    val onAccent: Color,
    /** Sunken fill: fields, tracks, selected rows. */
    val well: Color,
    /** Row separators. */
    val hairline: Color,
    /** Button and chip outlines. */
    val outline: Color,
    /** The lit top of glass panels and buttons. */
    val sheen: Color,
    val shadow: Color,
    val dark: Boolean,
)

val DARK =
    Palette(
        bg = Color(0xFF070910),
        surface = Color(0xA80E121B),
        border = Color(0x1FFFFFFF),
        fg = Color(0xFFEEF1F6),
        muted = Color(0xFFA4ADBB),
        accent = Color(0xFF4C8DFF),
        ok = Color(0xFF5EE3A1),
        warn = Color(0xFFFFC56E),
        fail = Color(0xFFFF8F82),
        subtle = Color(0xFF7D8797),
        label = Color(0xFFC3CAD6),
        accentLight = Color(0xFFA9C6FF),
        accentTop = Color(0xFF86B2FF),
        teal = Color(0xFF3EE6C4),
        onAccent = Color(0xFF050810),
        well = Color(0x99050810),
        hairline = Color(0x14FFFFFF),
        outline = Color(0x29FFFFFF),
        sheen = Color(0x14FFFFFF),
        shadow = Color(0x73000000),
        dark = true,
    )

val LIGHT =
    Palette(
        bg = Color(0xFFE9EEF6),
        surface = Color(0xC7FFFFFF),
        border = Color(0x1F0B1220),
        fg = Color(0xFF0D1424),
        muted = Color(0xFF4A5568),
        accent = Color(0xFF2F6BE0),
        ok = Color(0xFF138A5B),
        warn = Color(0xFFA86200),
        fail = Color(0xFFC63D2E),
        subtle = Color(0xFF6B7586),
        label = Color(0xFF2D3748),
        accentLight = Color(0xFF1F4FB0),
        accentTop = Color(0xFF5B8EF0),
        teal = Color(0xFF0E9C82),
        onAccent = Color(0xFFFFFFFF),
        well = Color(0x0F0B1220),
        hairline = Color(0x140B1220),
        outline = Color(0x290B1220),
        sheen = Color(0x99FFFFFF),
        shadow = Color(0x260B1220),
        dark = false,
    )

val LocalPalette = staticCompositionLocalOf { DARK }

/** The palette color for a tone name ("ok", "warn", "fail", "info"). */
fun Palette.tone(t: String): Color =
    when (t) {
        "ok", "pass" -> ok
        "warn" -> warn
        "fail", "error" -> fail
        "muted", "line", "skip", "off" -> muted
        else -> accentLight
    }
