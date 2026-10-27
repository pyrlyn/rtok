// Page filters kept in the location query (no history entry), with toggle groups and a search box.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.browser.window
import org.w3c.dom.url.URL

/** The query parameter [name], or [default]; reads [AppState.query] so pages recompose on change. */
fun AppState.param(
    name: String,
    default: String,
): String {
    query.length
    return URL(window.location.href).searchParams.get(name)?.takeIf { it.isNotEmpty() } ?: default
}

/** Sets (or, at its [default], drops) the parameter [name] without a history entry. */
fun AppState.setParam(
    name: String,
    value: String,
    default: String,
) {
    val url = URL(window.location.href)
    if (value == default) url.searchParams.delete(name) else url.searchParams.set(name, value)
    window.history.replaceState(null, "", url.href)
    query = window.location.search
}

/** A row of mutually exclusive options bound to `?[name]=` (the first option is the default). */
@Composable
fun Toggles(
    app: AppState,
    name: String,
    options: List<String>,
) {
    val p = LocalPalette.current
    val cur = app.param(name, options.first())
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o ->
            val on = o == cur
            Box(
                Modifier
                    .background(if (on) p.accent.copy(alpha = 0.18f) else p.surface, RoundedCornerShape(6.dp))
                    .clickable { app.setParam(name, o, options.first()) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Txt(o, color = if (on) p.accent else p.muted, size = 12) }
        }
    }
}

/** A search box bound to `?[name]=`. */
@Composable
fun SearchBox(
    app: AppState,
    hint: String,
    name: String = "q",
) {
    val p = LocalPalette.current
    val v = app.param(name, "")
    BasicTextField(
        v,
        { app.setParam(name, it, "") },
        Modifier.width(220.dp).border(1.dp, p.border, RoundedCornerShape(6.dp)).padding(6.dp),
        singleLine = true,
        textStyle = TextStyle(color = p.fg, fontSize = 13.sp),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner ->
            Box {
                if (v.isEmpty()) Muted(hint)
                inner()
            }
        },
    )
}

/** A toolbar row. */
@Composable
fun Toolbar(content: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }

/** Local `HH:MM:SS` of unix seconds. */
@JsFun("(t) => new Date(t * 1000).toTimeString().slice(0, 8)")
external fun hms(ts: Double): String
