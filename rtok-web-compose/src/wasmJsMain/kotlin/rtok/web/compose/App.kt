// The Compose app shell: sidebar from PAGES, header with the live status and theme toggle, the Offline screen,
// client-side routing over the History API, one `/ws` link; pages render from the latest snapshot.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** A page body: renders [snap] and may send requests through [app]. */
fun interface PageView {
    @Composable
    fun Render(
        snap: JsonObject,
        app: AppState,
    )
}

/** The pages ported to Compose so far; the rest show a placeholder until they land. */
val VIEWS: Map<String, PageView> =
    mapOf(
        "overview" to OverviewPage,
        "calls" to CallsPage,
        "sessions" to SessionsPage,
        "logs" to LogsPage,
        "config" to ConfigPage,
        "hosts" to HostsPage,
        "services" to ServicesPage,
        "plugins" to PluginsPage,
        "usage" to UsagePage,
    )

/** Observable app state: link, last snapshot, route and theme. */
class AppState {
    var link by mutableStateOf(LinkState.CONNECTING)
    var snapshot by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf<String?>(null)
    var updatedAt by mutableStateOf<Double?>(null)
    var path by mutableStateOf(window.location.pathname)

    /** The location query; page filters live there (`?q=`, `?surface=`, ...), as in the kotlinx.html UI. */
    var query by mutableStateOf(window.location.search)
    var dark by mutableStateOf(runCatching { localStorage.getItem("rtok-theme") }.getOrNull() != "light")

    private val link0 = Link({ link = it }, ::onFrame)

    fun start() {
        window.addEventListener("popstate", {
            path = window.location.pathname
            query = window.location.search
        })
        link0.start()
    }

    fun send(text: String): Boolean = link0.send(text)

    fun reconnect() = link0.reconnect()

    fun go(to: String) {
        window.history.pushState(null, "", to)
        path = window.location.pathname
        query = window.location.search
    }

    fun toggleTheme() {
        dark = !dark
        runCatching { localStorage.setItem("rtok-theme", if (dark) "dark" else "light") }
    }

    private fun onFrame(f: Frame) {
        when (f) {
            is Frame.Snapshot -> {
                snapshot = f.body
                error = null
                updatedAt = nowMs()
            }

            is Frame.SnapshotError -> {
                error = f.error
            }

            is Frame.Reply -> {
                Unit
            }
        }
    }
}

@JsFun("() => Date.now()")
external fun nowMs(): Double

/** The whole app. */
@Composable
fun App(state: AppState) {
    CompositionLocalProvider(LocalPalette provides if (state.dark) DARK else LIGHT) {
        val p = LocalPalette.current
        Box(Modifier.fillMaxSize().background(p.bg)) {
            if (state.link == LinkState.CLOSED && state.snapshot == null) Offline(state) else Shell(state)
        }
    }
}

@Composable
private fun Shell(state: AppState) {
    val p = LocalPalette.current
    val page = pageFor(state.path)
    document.title = "${page?.id ?: "not found"} · rtok"
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .background(p.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt("RTOK", bold = true, size = 16)
            PAGES.forEach { NavItem(it, it == page, state) }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(state, page)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Panel("snapshot error") { Txt(it) } }
                val snap = state.snapshot
                when {
                    page == null -> Panel("not found") { Txt("Page not found. Pick a screen from the navigation.") }
                    snap == null -> Muted("waiting for the first snapshot...")
                    else -> VIEWS[page.id]?.Render(snap, state) ?: Panel(page.id) { Muted("Not ported to Compose yet.") }
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    page: Page,
    current: Boolean,
    state: AppState,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (current) p.accent.copy(alpha = 0.15f) else p.surface)
            .clickable { state.go("/${page.id}") }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) { Txt(page.id, color = if (current) p.accent else p.fg) }
}

@Composable
private fun Header(
    state: AppState,
    page: Page?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(page?.id ?: "not found", size = 20, bold = true)
        state.updatedAt?.let { Muted("updated ${ago(nowMs() - it)}") }
        val (tone, label) =
            when (state.link) {
                LinkState.OPEN -> "ok" to "live"
                LinkState.CONNECTING -> "info" to "connecting"
                LinkState.CLOSED -> "fail" to "closed"
            }
        Pill(tone, label)
        Box(Modifier.clickable { state.toggleTheme() }.padding(4.dp)) { Txt("theme") }
    }
}

@Composable
private fun Offline(state: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Panel("rtok is offline") {
            Muted("The link to rtok web dropped. It retries on its own; if the server restarted, open the URL it printed again.")
            Box(Modifier.clickable { state.reconnect() }.padding(6.dp)) { Txt("Reconnect", color = LocalPalette.current.accent) }
        }
    }
}

private const val MS = 1000.0

/** "3s ago" / "2m ago" / "1h ago". */
fun ago(ms: Double): String {
    val s = (ms / MS).toInt().coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
