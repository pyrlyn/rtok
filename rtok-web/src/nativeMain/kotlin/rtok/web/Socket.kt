// Port of src/web/mod.rs `/ws`: the gate before the upgrade and `socket_loop` after it.
package rtok.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import rtok.http.server.ServerRequest
import rtok.http.server.WsHandler
import rtok.http.server.WsSocket

/** Snapshot period: a fixed tick, so frequent inbound frames never push the next snapshot out. */
const val SNAPSHOT_TICK_MS = 2_000L

/** One inbound text frame: the reply frame, or null for none (an accepted `set` shows in the next snapshot). */
fun interface Inbound {
    fun answer(text: String): String?
}

/**
 * The live calls frames a socket can subscribe to (live.rs `LiveCalls`). Every frame is the whole state, so a socket
 * that falls behind skips the ones in between and loses nothing.
 */
fun interface CallsFeed {
    /** The frame to send first (the totals so far) and the frames after it; the first subscriber starts the poller. */
    suspend fun subscribe(): Pair<String, StateFlow<String>>
}

/** Keys of the client messages Rust's untagged `ClientMessage` tries before `Calls`. */
private val BEFORE_CALLS = listOf("expand", "set", "project", "doctor", "junk", "graph", "diff", "export", "import")

/**
 * mod.rs `calls_subscription`: `on` for a call-event subscription message (T329.15), which belongs to the socket and
 * not to [Inbound], since the socket owns the subscription. Null for any other message.
 */
fun callsSubscription(text: String): Boolean? {
    val v = (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.takeUnless { o -> BEFORE_CALLS.any { it in o } }
    return ((v?.get("calls") as? JsonObject)?.get("subscribe") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}

private sealed interface Event {
    data object Tick : Event

    class Text(
        val text: String,
    ) : Event

    data object Closed : Event

    class Calls(
        val frame: String,
    ) : Event
}

class WsEndpoint(
    private val app: WebApp,
    private val snapshots: Snapshots,
    private val inbound: Inbound = Inbound { null },
    private val tickMs: Long = SNAPSHOT_TICK_MS,
    private val calls: CallsFeed? = null,
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

    /** The socket's calls subscription: a conflated channel, so a slow socket reads the newest frame only. */
    private class Subscription(
        val frames: Channel<String> = Channel(Channel.CONFLATED),
        var job: Job? = null,
    )

    private suspend fun CoroutineScope.loop(
        socket: WsSocket,
        events: Channel<Event>,
    ) {
        var due = true
        var open = true
        var sub = Subscription()
        while (open) {
            if (due) {
                due = false
                open = socket.send(snapshots.shared())
            }
            if (!open) break
            val frames = sub.frames
            val e =
                select {
                    events.onReceive { it }
                    frames.onReceive { Event.Calls(it) }
                }
            when (e) {
                Event.Tick -> {
                    due = true
                }

                Event.Closed -> {
                    open = false
                }

                is Event.Calls -> {
                    open = socket.send(e.frame)
                }

                is Event.Text -> {
                    val on = callsSubscription(e.text)
                    if (on != null) {
                        sub.job?.cancel()
                        sub = Subscription()
                        if (on && calls != null) open = subscribed(socket, calls, sub)
                    } else {
                        due = true
                        // A registry write can index a project: it runs off the socket's thread.
                        val reply = withContext(Dispatchers.Default) { runCatching { inbound.answer(e.text) }.getOrNull() }
                        if (reply != null) open = socket.send(reply)
                    }
                }
            }
        }
        sub.job?.cancel()
    }

    /** Sends the totals so far, then forwards every change into [sub]; false when the socket closed. */
    private suspend fun CoroutineScope.subscribed(
        socket: WsSocket,
        feed: CallsFeed,
        sub: Subscription,
    ): Boolean {
        val (first, flow) = feed.subscribe()
        if (!socket.send(first)) return false
        sub.job = launch { flow.dropWhile { it == first }.collect { sub.frames.send(it) } }
        return true
    }
}
