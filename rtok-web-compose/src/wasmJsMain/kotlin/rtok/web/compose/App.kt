// The Compose app shell in the Pyrlyn style: grouped sidebar (Nav.kt), screen header with the live status and theme toggle, the Offline screen,
// client-side routing over the History API, one `/ws` link; pages render from the latest snapshot.
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
        "stats" to StatsPage,
        "skills" to SkillsPage,
        "tasks" to TasksPage,
        "worktrees" to WorktreesPage,
        "doctor" to DoctorPage,
        "graph" to GraphPage,
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
    private val replies = HashMap<String, MutableList<(Frame.Reply) -> Unit>>()

    /** Registers a handler for typed reply frames ([type] = `junkplan`, `message`, ...). */
    fun on(
        type: String,
        handler: (Frame.Reply) -> Unit,
    ) {
        replies.getOrPut(type) { ArrayList() } += handler
    }

    /** The Hosts junk card's controller. */
    val junk by lazy { JunkController(this) }

    /** The Doctor fix panel's controller. */
    val fix by lazy { FixController(this) }

    /** The command palette and global shortcuts. */
    val palette = PaletteController(this)

    /** The Graph page's live calls. */
    val live by lazy { LiveCallsController(this) }

    /** The Graph drill-down's controller. */
    val drill by lazy { DrillController(this).also { installExport() } }

    fun start() {
        window.addEventListener("popstate", {
            path = window.location.pathname
            query = window.location.search
        })
        palette.install()
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
                replies[f.type]?.toList()?.forEach { it(f) }
            }
        }
    }
}

@JsFun("() => Date.now()")
external fun nowMs(): Double

/** The whole app. */
@Composable
fun App(state: AppState) {
    CompositionLocalProvider(LocalPalette provides if (state.dark) DARK else LIGHT, LocalFonts provides Fonts.family) {
        val p = LocalPalette.current
        Box(Modifier.fillMaxSize().background(p.bg).drawBehind { backdrop(p) }) {
            if (state.link == LinkState.CLOSED && state.snapshot == null) Offline(state) else Shell(state)
            PaletteOverlay(state.palette)
        }
    }
}

/** The kit's backdrop: accent and teal light pooling behind the glass. */
private fun DrawScope.backdrop(p: Palette) {
    val a = if (p.dark) 0.16f else 0.12f
    drawRect(Brush.radialGradient(listOf(p.accent.copy(alpha = a), Color.Transparent), Offset(size.width * 0.3f, 0f), size.maxDimension * 0.6f))
    drawRect(Brush.radialGradient(listOf(p.teal.copy(alpha = a / 2), Color.Transparent), Offset(size.width, size.height), size.maxDimension * 0.5f))
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
