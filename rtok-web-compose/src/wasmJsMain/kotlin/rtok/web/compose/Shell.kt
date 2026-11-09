// The app shell in the Pyrlyn style: the grouped sidebar (Nav.kt), the screen header with the live status, ⌘K and the
// theme toggle, and the connecting, not found and offline states.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.Frame
import rtok.web.ui.LinkState
import rtok.web.ui.PAGES
import rtok.web.ui.Page
import rtok.web.ui.pageFor

@Composable
internal fun Shell(state: AppState) {
    val page = pageFor(state.path)
    SideEffect { state.live.want(page?.id == "graph", state.link == LinkState.OPEN) }
    val nav = navFor(page?.id)
    document.title = "${nav?.second?.title ?: page?.id ?: "Not found"} · rtok"
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(249.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { Sidebar(state, page) }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(state, page, nav)
            state.error?.let { Notice("fail", "Snapshot error: $it") }
            val snap = state.snapshot
            when {
                page == null -> NotFound(state)
                snap == null -> Connecting()
                else -> VIEWS[page.id]?.Render(snap, state) ?: Panel(page.id) { Muted("Not ported to Compose yet.") }
            }
        }
    }
}

@Composable
private fun Sidebar(
    state: AppState,
    page: Page?,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().glass(p).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(p.well, FIELD).border(1.dp, p.outline, FIELD), contentAlignment = Alignment.Center) {
                Icon(ICON_BRAND, p.fg)
            }
            Column {
                Txt("rtok", size = 14, bold = true)
                Txt("Token savings", color = p.muted, size = 11)
            }
        }
        val known = SIDEBAR.flatMap { g -> g.entries.map { it.id } }.toSet()
        // A page added to PAGES without a SIDEBAR entry still gets a link instead of vanishing.
        val rest = PAGES.filter { it.id !in known }.map { NavEntry(it.id, it.id, "", "M5 12h14") }
        (SIDEBAR + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { NavGroup("Other", it) })).forEach { g ->
            Kicker(g.name, Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
            g.entries.forEach { NavItem(it, it.id == page?.id, state) }
        }
        Row(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .well(p, CARD)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).accentFill(p, CODE), contentAlignment = Alignment.Center) {
                Txt("RT", color = p.onAccent, size = 11, bold = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(window.location.host, size = 12, bold = true)
                LinkPill(state.link)
            }
        }
    }
}

@Composable
private fun NavItem(
    e: NavEntry,
    current: Boolean,
    state: AppState,
) {
    val p = LocalPalette.current
    val look = if (current) Modifier.accentFill(p, NAV_ITEM) else Modifier
    val ink = if (current) p.onAccent else p.label
    Row(
        look
            .fillMaxWidth()
            .clickable { state.go("/${e.id}") }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(e.icon, ink)
        Txt(e.title, color = ink, bold = current)
    }
}

@Composable
private fun LinkPill(link: LinkState) {
    val (tone, label) =
        when (link) {
            LinkState.OPEN -> "ok" to "live"
            LinkState.CONNECTING -> "info" to "connecting"
            LinkState.CLOSED -> "fail" to "offline"
        }
    Pill(tone, label)
}

@Composable
private fun Header(
    state: AppState,
    page: Page?,
    nav: Pair<NavGroup, NavEntry>?,
) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Kicker(nav?.first?.name ?: "rtok")
            Txt(nav?.second?.title ?: page?.id ?: "Not found", size = 28, bold = true, tracking = -0.02)
            nav
                ?.second
                ?.subtitle
                ?.takeIf { it.isNotEmpty() }
                ?.let { Txt(it, color = p.muted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.updatedAt?.let { Muted("updated ${ago(nowMs() - it)}") }
            LinkPill(state.link)
            Action("⌘K") { state.palette.show() }
            Box(Modifier.glass(p, FIELD).clickable { state.toggleTheme() }.padding(8.dp)) {
                Icon(if (state.dark) ICON_SUN else ICON_MOON, p.fg, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Connecting() =
    Panel("Waiting for the first snapshot", "rtok web sends one as soon as the link opens") {
        ShareBar(0.35)
    }

@Composable
private fun NotFound(state: AppState) =
    Panel("Page not found", "No screen lives at ${state.path}. Pick one from the navigation.") {
        Row { Action("Go to Overview", kind = ButtonKind.PRIMARY) { state.go("/") } }
    }

@Composable
internal fun Offline(state: AppState) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).glass(p, lit = true).padding(horizontal = 26.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Kicker("Link closed", color = p.fail)
            Txt("rtok is offline", size = 22, bold = true, tracking = -0.02)
            Muted("The link to rtok web dropped. It retries on its own; if the server restarted, open the URL it printed again.")
            Row { Action("Reconnect", kind = ButtonKind.PRIMARY) { state.reconnect() } }
        }
    }
}
