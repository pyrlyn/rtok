// Port of web/src/api/ws.ts `connectWs` and `pageToken`: the browser WebSocket with reconnect backoff.
package rtok.web.ui

import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URL

private const val TOKEN_KEY = "rtok.web.token"

/**
 * The `/ws` token: taken from `?token=` once, kept in sessionStorage for the tab and removed from the address
 * bar so it does not linger in history (ws.ts `pageToken`).
 */
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

/** A reconnecting `/ws` link; [onState] and [onFrame] run on the page's event loop. */
class Link(
    private val onState: (LinkState) -> Unit,
    private val onFrame: (Frame) -> Unit,
) {
    private var socket: WebSocket? = null
    private var timer: Int? = null
    private var attempt = 0
    private var stopped = false

    fun start() = open()

    /** Sends one request; false when the link is not open. */
    fun send(text: String): Boolean {
        val s = socket ?: return false
        return s.readyState == WebSocket.OPEN && runCatching { s.send(text) }.isSuccess
    }

    /** Reconnect now (the Offline screen's button), resetting the backoff. */
    fun reconnect() {
        timer?.let { window.clearTimeout(it) }
        attempt = 0
        socket?.let {
            socket = null
            it.close()
        }
        open()
    }

    fun close() {
        stopped = true
        timer?.let { window.clearTimeout(it) }
        socket?.close()
        socket = null
        onState(LinkState.CLOSED)
    }

    private fun retry() {
        onState(LinkState.CLOSED)
        if (stopped) return
        val delay = retryDelayMs(attempt).toInt()
        attempt++
        timer = window.setTimeout({ open() }, delay)
    }

    private fun open() {
        onState(LinkState.CONNECTING)
        val loc = window.location
        val current =
            runCatching { WebSocket(wsUrl(loc.protocol, loc.host, pageToken(), ::encodeURIComponent)) }.getOrNull()
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
            // A frame that fails to render must not kill the link; it is reported on the console instead.
            runCatching { (ev.data as? String)?.let(::parseFrame)?.let(onFrame) }
                .onFailure { console.error("rtok: frame failed", it) }
        }
        current.onclose = {
            if (socket === current) {
                socket = null
                retry()
            }
        }
    }
}

external fun encodeURIComponent(s: String): String
