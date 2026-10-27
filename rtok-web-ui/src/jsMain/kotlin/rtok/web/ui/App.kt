// Port of web/src/Shell.tsx and main.tsx: the app shell (sidebar from PAGES, header with the live status and
// theme toggle, the Offline screen) over one `/ws` link; pages render from the latest snapshot.
package rtok.web.ui

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h1
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.js.onClickFunction
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.js.Date

/** What the shell knows: the link, the last snapshot and when it came. */
class AppState {
    var link = LinkState.CONNECTING
    var snapshot: JsonObject? = null
    var error: String? = null
    var updatedAt: Double? = null
    var everOpen = false
}

/** The app: owns the state, the link and the render loop. */
class App(
    private val root: HTMLElement,
) {
    private val state = AppState()
    private val link = Link(::onState, ::onFrame)

    private val palette = Palette({ state.snapshot }) { go(it) }

    fun start() {
        palette.install()
        window.addEventListener("popstate", { render() })
        // Every in-app link (`/page`) routes client-side, panels' "page →" links included.
        root.addEventListener("click", { e ->
            val href = (e.target as? org.w3c.dom.Element)?.closest("a[href^='/']")?.getAttribute("href")
            if (href != null) navigate(e, href)
        })
        link.start()
        render()
    }

    /** Sends a request frame (pages use this for actions). */
    fun send(text: String): Boolean = link.send(text)

    private fun onState(s: LinkState) {
        state.link = s
        if (s == LinkState.OPEN) state.everOpen = true
        render()
    }

    private fun onFrame(f: Frame) {
        when (f) {
            is Frame.Snapshot -> {
                state.snapshot = f.body
                state.error = null
                state.updatedAt = Date.now()
            }

            is Frame.SnapshotError -> {
                state.error = f.error
            }

            is Frame.Reply -> {
                Replies.dispatch(f)
            }
        }
        render()
    }

    private fun go(path: String) {
        window.history.pushState(null, "", path)
        render()
    }

    /** Re-renders from the current state (a filter changed). */
    fun refresh() = render()

    private fun render() {
        // A snapshot tick re-renders the page; the focused filter keeps its focus and caret.
        val focused = document.activeElement as? org.w3c.dom.HTMLInputElement
        val focusId = focused?.id?.takeIf { it.isNotEmpty() }
        val caret = focused?.selectionStart
        renderNow()
        focusId?.let { id ->
            (document.getElementById(id) as? org.w3c.dom.HTMLInputElement)?.let {
                it.focus()
                caret?.let { c -> it.setSelectionRange(c, c) }
            }
        }
    }

    private fun renderNow() {
        root.innerHTML = ""
        if (state.link == LinkState.CLOSED) {
            renderOffline()
            return
        }
        val page = pageFor(window.location.pathname)
        LiveCalls.want(page?.id == "graph", state.link == LinkState.OPEN)
        val title = page?.id ?: "not found"
        document.title = "$title · rtok"
        root.append {
            div("layout") {
                div("sidebar") {
                    div("brand") { +"RTOK" }
                    nav("nav") {
                        PAGES.forEach { p ->
                            a("/${p.id}") {
                                if (p == page) attributes["aria-current"] = "page"
                                +p.id
                            }
                        }
                    }
                }
                div {
                    header {
                        h1 { +title }
                        state.updatedAt?.let { span("muted") { +"updated ${ago(Date.now() - it)}" } }
                        val (tone, label) =
                            when (state.link) {
                                LinkState.OPEN -> "ok" to "live"
                                LinkState.CONNECTING -> "info" to "connecting"
                                LinkState.CLOSED -> "fail" to "closed"
                            }
                        span("pill $tone") { +label }
                        button {
                            attributes["aria-label"] = "Open the command palette"
                            onClickFunction = { palette.open() }
                            +"⌘K"
                        }
                        button {
                            attributes["aria-label"] = "Toggle theme"
                            onClickFunction = { toggleTheme() }
                            +"theme"
                        }
                    }
                    main {
                        id = "main"
                        state.error?.let { div("card") { p { +"snapshot error: $it" } } }
                        val snap = state.snapshot
                        when {
                            page == null -> div("card") { p { +"Page not found. Pick a screen from the navigation." } }
                            snap == null -> div("card muted") { +"waiting for the first snapshot..." }
                            else -> Pages.render(this, page, snap, this@App)
                        }
                    }
                }
            }
        }
    }

    private fun navigate(
        e: Event,
        path: String,
    ) {
        if (opensElsewhere(e as? MouseEvent)) return
        e.preventDefault()
        go(path)
    }

    private fun renderOffline() {
        document.title = "offline · rtok"
        root.append {
            div("center") {
                div("card") {
                    h1 { +"rtok is offline" }
                    p("muted") {
                        +"The link to rtok web dropped. It retries on its own; if the server restarted, open the URL "
                        +"it printed again (it carries a fresh token)."
                    }
                    button {
                        onClickFunction = { link.reconnect() }
                        +"Reconnect"
                    }
                }
            }
        }
    }
}

/** A modified or non-primary click: let the browser open the link itself. */
private fun opensElsewhere(m: MouseEvent?): Boolean = m != null && (m.metaKey || m.ctrlKey || m.shiftKey || m.button.toInt() != 0)

private const val MS = 1000.0
private const val MINUTE = 60
private const val HOUR = 3600

/** "3s ago" / "2m ago" / "1h ago". */
fun ago(ms: Double): String {
    val s = (ms / MS).toInt().coerceAtLeast(0)
    return when {
        s < MINUTE -> "${s}s ago"
        s < HOUR -> "${s / MINUTE}m ago"
        else -> "${s / HOUR}h ago"
    }
}

/** Flips dark/light, kept under the same key theme-init.js reads. */
fun toggleTheme() {
    val root = document.documentElement ?: return
    val dark = root.getAttribute("data-theme") != "light"
    val next = if (dark) "light" else "dark"
    root.setAttribute("data-theme", next)
    root.classList.toggle("dark", !dark)
    root.classList.toggle("light", dark)
    runCatching { localStorage.setItem("rtok-theme", next) }
}

fun main() {
    installStyles()
    Pages.register("overview", OverviewPage)
    Pages.register("plugins", PluginsPage)
    Pages.register("calls", CallsPage)
    Pages.register("sessions", SessionsPage)
    Pages.register("logs", LogsPage)
    Pages.register("config", ConfigPage)
    Pages.register("hosts", HostsPage)
    Pages.register("services", ServicesPage)
    Pages.register("usage", UsagePage)
    Pages.register("graph", GraphPage)
    Pages.register("doctor", DoctorPage)
    Pages.register("stats", pendingPage("stats", "rtok stats"))
    Pages.register("skills", pendingPage("skills", "rtok skills list"))
    Pages.register("worktrees", WorktreesPage)
    Pages.register("tasks", pendingPage("tasks", "rtok tasks"))
    val root = document.getElementById("root") as? HTMLElement ?: error("#root element not found")
    val app = App(root)
    LiveCalls.install(app)
    JunkCard.install(app)
    DoctorFixCard.install(app)
    app.start()
}
