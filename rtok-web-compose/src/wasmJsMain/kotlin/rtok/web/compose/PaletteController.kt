// The command palette in Compose: ⌘K / Ctrl+K opens a jump list over pages, plugins, recent sessions and hosts
// (arrows, Enter, Escape, outside click), `g <key>` opens a page and `?` lists the keys. Keys are read from the
// window, so they work whichever composable has focus; letters typed into a field stay text.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import rtok.web.ui.GO
import rtok.web.ui.GO_WINDOW_MS
import rtok.web.ui.PAGES
import rtok.web.ui.filterGroups
import rtok.web.ui.pageForKey
import rtok.web.ui.paletteGroups

/** The palette's state and the global shortcuts. */
class PaletteController(
    private val app: AppState,
) {
    var open by mutableStateOf(false)
        private set
    var help by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var focus by mutableStateOf(0)
    private var pendingG = 0.0

    fun install() = window.addEventListener("keydown", { e -> (e as? KeyboardEvent)?.let(::onKey) })

    fun show() {
        query = ""
        focus = 0
        help = false
        open = true
    }

    fun close() {
        open = false
        help = false
    }

    fun snapshot() = app.snapshot

    fun entries() = filterGroups(paletteGroups(app.snapshot), query).flatMap { it.second }

    fun pick(href: String) {
        close()
        app.go(href)
    }

    private fun onKey(e: KeyboardEvent) {
        val mod = e.metaKey || e.ctrlKey || e.altKey
        when {
            e.defaultPrevented -> {}

            e.key.lowercase() == "k" && (e.metaKey || e.ctrlKey) && !e.altKey -> {
                e.preventDefault()
                if (open) close() else show()
            }

            open -> {
                paletteKey(e)
            }

            mod || isTyping(e) -> {}

            else -> {
                shortcut(e)
            }
        }
    }

    private fun isTyping(e: KeyboardEvent): Boolean {
        val t = e.target as? HTMLElement ?: return false
        return t.isContentEditable || t.tagName in setOf("INPUT", "TEXTAREA", "SELECT")
    }

    private fun shortcut(e: KeyboardEvent) {
        val now = e.timeStamp.toDouble()
        if (pendingG > 0 && now - pendingG < GO_WINDOW_MS) {
            pendingG = 0.0
            pageForKey(e.key)?.let {
                e.preventDefault()
                app.go("/$it")
            }
        } else if (e.key == "g") {
            pendingG = now
        } else if (e.key == "?") {
            e.preventDefault()
            open = true
            help = true
        }
    }

    private fun paletteKey(e: KeyboardEvent) {
        val n = entries().size
        when (e.key) {
            "Escape" -> close()
            "ArrowDown" -> if (n > 0) focus = (focus + 1) % n
            "ArrowUp" -> if (n > 0) focus = (focus - 1 + n) % n
            "Enter" -> entries().getOrNull(focus)?.let { pick(it.href) }
            else -> return
        }
        e.preventDefault()
    }
}

/** The overlay, drawn over the app while the palette or the key list is open. */
@Composable
fun PaletteOverlay(c: PaletteController) {
    if (!c.open) return
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, null) { c.close() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 80.dp)
                .width(520.dp)
                .heightIn(max = 520.dp)
                .background(p.surface, RoundedCornerShape(10.dp))
                .border(1.dp, p.border, RoundedCornerShape(10.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { if (c.help) Keys(c) else Jump(c) }
    }
}

@Composable
private fun Jump(c: PaletteController) {
    val p = LocalPalette.current
    val fr = remember { FocusRequester() }
    BasicTextField(
        c.query,
        {
            c.query = it
            c.focus = 0
        },
        Modifier
            .fillMaxWidth()
            .border(1.dp, p.border, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .focusRequester(fr),
        singleLine = true,
        textStyle = TextStyle(color = p.fg, fontSize = 14.sp),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner ->
            Box {
                if (c.query.isEmpty()) Muted("Jump to…")
                inner()
            }
        },
    )
    LaunchedEffect(Unit) { fr.requestFocus() }
    val groups = filterGroups(paletteGroups(c.snapshot()), c.query)
    if (groups.isEmpty()) Muted("Nothing matches.")
    var i = 0
    Column(Modifier.verticalScroll(rememberScrollState())) {
        groups.forEach { (title, es) ->
            Txt(title, color = p.muted, size = 11, bold = true)
            es.forEach { e ->
                val mine = i++
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (mine == c.focus) p.accent.copy(alpha = 0.15f) else p.surface)
                        .clickable { c.pick(e.href) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Txt(e.label)
                    if (e.hint.isNotEmpty()) Muted(e.hint)
                }
            }
        }
    }
}

@Composable
private fun Keys(c: PaletteController) {
    Txt("Keyboard shortcuts", bold = true)
    Txt("⌘K / Ctrl+K  command palette")
    PAGES.forEach { Txt("g ${GO[it.id]}  ${it.id}") }
    Action("Close") { c.close() }
}
