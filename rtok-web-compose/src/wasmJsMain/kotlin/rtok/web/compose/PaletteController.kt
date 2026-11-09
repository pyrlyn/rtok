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
            .background(if (p.dark) Color(0xB3050810) else Color(0x400B1220))
            .clickable(remember { MutableInteractionSource() }, null) { c.close() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 80.dp)
                .width(560.dp)
                .heightIn(max = 540.dp)
                .glass(p, lit = true)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            .well(p)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .focusRequester(fr),
        singleLine = true,
        textStyle = TextStyle(color = p.fg, fontSize = 14.sp, fontFamily = LocalFonts.current),
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
            Kicker(title, Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp))
            es.forEach { e ->
                val mine = i++
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (mine == c.focus) Modifier.background(p.accent.copy(alpha = 0.16f), FIELD).border(1.dp, p.accent.copy(alpha = 0.35f), FIELD) else Modifier)
                        .clickable { c.pick(e.href) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Txt(e.label, color = if (mine == c.focus) p.fg else p.label)
                    if (e.hint.isNotEmpty()) Muted(e.hint)
                }
            }
        }
    }
}

@Composable
private fun Keys(c: PaletteController) {
    Txt("Keyboard shortcuts", size = 15, bold = true)
    Key("⌘K / Ctrl+K", "command palette")
    Key("?", "this list")
    PAGES.forEach { Key("g ${GO[it.id]}", navFor(it.id)?.second?.title ?: it.id) }
    Row { Action("Close") { c.close() } }
}

/** One shortcut: the keys in a sunken key cap, then what they do. */
@Composable
private fun Key(
    keys: String,
    what: String,
) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(120.dp)) { Box(Modifier.well(p, CODE).padding(horizontal = 8.dp, vertical = 2.dp)) { Txt(keys, size = 12, bold = true) } }
        Txt(what, color = p.label)
    }
}
