// Port of src/web/mod.rs `/ws`: the gate before the upgrade and `socket_loop` after it.
package rtok.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rtok.http.server.ServerRequest
import rtok.http.server.WsHandler
import rtok.http.server.WsSocket

/** Snapshot period: a fixed tick, so frequent inbound frames never push the next snapshot out. */
const val SNAPSHOT_TICK_MS = 2_000L

/** One inbound text frame: the reply frame, or null for none (an accepted `set` shows in the next snapshot). */
fun interface Inbound {
    fun answer(text: String): String?
}

private sealed interface Event {
    data object Tick : Event

    class Text(
        val text: String,
    ) : Event

    data object Closed : Event
}

class WsEndpoint(
    private val app: WebApp,
    private val snapshots: Snapshots,
    private val inbound: Inbound = Inbound { null },
    private val tickMs: Long = SNAPSHOT_TICK_MS,
) : WsHandler {
    override fun admit(req: ServerRequest): Int? = app.admitted(req)

    override suspend fun run(socket: WsSocket) =
        coroutineScope {
            val events = Channel<Event>(Channel.UNLIMITED)
            val ticker =
                launch {
                    while (true) {
                        delay(tickMs)
                        events.send(Event.Tick)
                    }
                }
            val reader =
                launch {
                    while (true) {
                        val text = socket.receive()
                        events.send(if (text == null) Event.Closed else Event.Text(text))
                        if (text == null) break
                    }
                }
            loop(socket, events)
            ticker.cancel()
            reader.cancel()
        }

    private suspend fun loop(
        socket: WsSocket,
        events: Channel<Event>,
    ) {
        var due = true
        var open = true
        while (open) {
            if (due) {
                due = false
                open = socket.send(snapshots.shared())
            }
            if (!open) break
            when (val e = events.receive()) {
                Event.Tick -> {
                    due = true
                }

                Event.Closed -> {
                    open = false
                }

                is Event.Text -> {
                    due = true
                    // A registry write can index a project: it runs off the socket's thread.
                    val reply = withContext(Dispatchers.Default) { runCatching { inbound.answer(e.text) }.getOrNull() }
                    if (reply != null) open = socket.send(reply)
                }
            }
        }
    }
}
