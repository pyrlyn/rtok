// The browser `/ws` link with reconnect backoff (the `/ws` protocol of docs/web.md), for Kotlin/Wasm.
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URL
import rtok.web.ui.Frame
import rtok.web.ui.LinkState
import rtok.web.ui.parseFrame
import rtok.web.ui.retryDelayMs
import rtok.web.ui.wsUrl

private const val TOKEN_KEY = "rtok.web.token"

/** The `/ws` token: taken from `?token=` once, kept in sessionStorage and removed from the address bar. */
fun pageToken(): String? =
    runCatching {
        val url = URL(window.location.href)
        url.searchParams.get("token")?.takeIf { it.isNotEmpty() }?.let {
            sessionStorage.setItem(TOKEN_KEY, it)
            url.searchParams.delete("token")
            window.history.replaceState(null, "", url.href)
        }
        sessionStorage.getItem(TOKEN_KEY)
    }.getOrNull()

@JsFun("(s) => encodeURIComponent(s)")
private external fun encodeUri(s: String): String

/** A reconnecting `/ws` link; callbacks run on the browser event loop. */
class Link(
    private val onState: (LinkState) -> Unit,
    private val onFrame: (Frame) -> Unit,
) {
    private var socket: WebSocket? = null
    private var timer: Int? = null
    private var attempt = 0

    fun start() = open()

    /** Sends one request; false when the link is not open. */
    fun send(text: String): Boolean {
        val s = socket ?: return false
        return s.readyState == WebSocket.OPEN && runCatching { s.send(text) }.isSuccess
    }

    /** Reconnect now, resetting the backoff. */
    fun reconnect() {
        timer?.let { window.clearTimeout(it) }
        attempt = 0
        socket?.let {
            socket = null
            it.close()
        }
        open()
    }

    private fun retry() {
        onState(LinkState.CLOSED)
        val delay = retryDelayMs(attempt).toInt()
        attempt++
        timer =
            window.setTimeout({
                open()
                null
            }, delay)
    }

    private fun open() {
        onState(LinkState.CONNECTING)
        val loc = window.location
        val current = runCatching { WebSocket(wsUrl(loc.protocol, loc.host, pageToken(), ::encodeUri)) }.getOrNull()
        if (current == null) {
            retry()
            return
        }
        socket = current
        current.onopen = {
            attempt = 0
            onState(LinkState.OPEN)
        }
        current.onmessage = { ev ->
            runCatching { (ev.data as? JsString)?.toString()?.let(::parseFrame)?.let(onFrame) }
        }
        current.onclose = {
            if (socket === current) {
                socket = null
                retry()
            }
        }
    }
}
