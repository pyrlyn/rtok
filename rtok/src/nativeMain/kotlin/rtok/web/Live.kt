// Port of src/web/live.rs `Reader`, `LiveCalls`, `head_of` and the poller (T329.15, T329.34, T484): graph call events on
// `/ws`. A poller runs only while a socket is subscribed, reads `id > cursor` every [POLL_MS], folds what it found into
// one [CallBatch], adds that to its [CallsStore] and publishes the store's [CallsView] when it changed. [Reader] is that
// one reader without the coroutines: `rtok tui` drives it from its own loop, so both surfaces fold the same rows.
package rtok.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rtok.config.Config
import rtok.config.Graph
import rtok.store.AgentDetail
import rtok.store.GraphEvent
import rtok.store.Store
import rtok.store.agentsOfSessions
import rtok.store.graphEventHead
import rtok.store.graphEventsAfter
import rtok.store.sessionRow
import kotlin.concurrent.AtomicInt

/** Events read per poll; a burst larger than this drains over the next polls. */
private const val FETCH = 2000L

/** Characters of a session id after the host when the store knows the session only by its host. */
private const val HOST_SESSION_CHARS = 12

/** The `calls` frame: `{"type":"calls","calls":{...}}`, the wire `ServerFrame::Calls` writes. */
fun callsFrame(view: CallsView): String = JsonObject(mapOf("type" to JsonPrimitive("calls"), "calls" to Json.encodeToJsonElement(view))).toString()

/** The newest event id in the store at [path], or null when it will not open: where "since this surface started" begins. */
fun headOf(path: String): Long? = runCatching { Store.open(path).use { it.graphEventHead() } }.getOrNull()

/**
 * T329.34: `claude 3f9a1c2e` for a session an agent row belongs to, `claude mcp-4242…` for one the store only knows by
 * its host. Null when it knows neither: the column then shows the session id. Only `fixed` names are remembered, since a
 * host-only name can still gain an agent when a hook registers later.
 */
private fun callerName(
    store: Store,
    session: String,
    agents: List<AgentDetail>,
): Pair<String, Boolean>? {
    val agent = agents.firstOrNull { it.hostSessionId == session && it.parentId == null }
    return if (agent != null) {
        "${agent.host} ${agent.short}" to true
    } else {
        runCatching { store.sessionRow(session) }.getOrNull()?.first?.let { "$it ${session.take(HOST_SESSION_CHARS)}" to false }
    }
}

/** A store handle and a cursor: where a reader that wants only new events starts. */
class Reader private constructor(
    private val store: Store,
    private var cursor: Long,
    /** Events one poll lists. */
    private val cap: Int,
) : AutoCloseable {
    /** Sessions the store gave an agent: that name does not change, so it is asked once. */
    private val named = HashMap<String, String>()

    /** What was written since the last call, folded; null when nothing was, or the read failed (the cursor stays). */
    fun poll(): CallBatch? {
        val batch = runCatching { store.graphEventsAfter(cursor, FETCH) }.getOrNull()?.let(::batchOf)
        batch?.let { cursor = it.head }
        return batch
    }

    /**
     * The events above [since] up to where this reader opened, as batches of at most one poll's cap with the time of
     * their newest event, so a surface that starts later folds the calls made since [since] into the same totals.
     */
    fun replay(since: Long): List<Pair<CallBatch, Long>> {
        var at = since
        val end = cursor
        val out = ArrayList<Pair<CallBatch, Long>>()
        while (at < end) {
            val events = runCatching { store.graphEventsAfter(at, cap.toLong()) }.getOrNull()?.filter { it.id <= end }.orEmpty()
            val ts = events.lastOrNull()?.tsMs
            val batch = batchOf(events)
            if (ts == null || batch == null) break
            at = batch.head
            out += batch to ts
        }
        return out
    }

    private fun batchOf(events: List<GraphEvent>): CallBatch? = coalesce(events, cap)?.let { it.copy(callers = namesOf(it.events)) }

    private fun namesOf(events: List<GraphEvent>): Map<String, String> {
        val sessions = events.map { it.session }.distinct().sorted()
        val asked = sessions.filter { it !in named }
        val agents = runCatching { store.agentsOfSessions(asked) }.getOrNull().orEmpty()
        val names = LinkedHashMap<String, String>()
        for (s in sessions) {
            val known = named[s]
            val found = if (known == null) callerName(store, s, agents) else null
            when {
                known != null -> {
                    names[s] = known
                }

                found != null -> {
                    if (found.second) named[s] = found.first
                    names[s] = found.first
                }
            }
        }
        return names
    }

    override fun close() = store.close()

    companion object {
        /** Null when the file will not open; callers try again on their next pass, so a missing store costs a retry. */
        fun open(
            path: String,
            cfg: Graph,
        ): Reader? {
            val store = runCatching { Store.open(path) }.getOrNull()
            val head = store?.let { s -> runCatching { s.graphEventHead() }.getOrNull() }
            return if (store != null && head != null) {
                Reader(store, head, eventsPerPoll(cfg))
            } else {
                store?.close()
                null
            }
        }
    }
}

/** The reader, opened at the newest event, and a store holding what the events since [started] add up to. */
private fun replayed(
    path: String,
    graph: Graph,
    started: Long?,
): Pair<Reader?, CallsStore> {
    val store = CallsStore(graph)
    val reader = Reader.open(path, graph)
    if (reader != null && started != null) {
        for ((batch, at) in reader.replay(started)) store.fold(batch, at)
        store.sweep(clockMs())
    }
    return reader to store
}

/**
 * The poller and its fan-out, one per `rtok web` process. The newest `calls` frame is a [StateFlow], not a queue: every
 * socket reads the current state first and then each change, and a slow one skips frames instead of lagging.
 */
class LiveCalls(
    private val cfg: () -> Config,
    private val pollMs: Long = POLL_MS,
) : CallsFeed {
    private val initial = cfg()
    private val frames = MutableStateFlow(callsFrame(CallsView.of(CallsStore(initial.plugins.graph), clockMs())))
    private val running = AtomicInt(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The newest event id when `rtok web` started: "since started" counts the events above it. */
    private val started: Long? = headOf(initial.core.dbPath)

    val calls: StateFlow<String> = frames.asStateFlow()

    /** Whether a poller is running (it stops after the last subscriber left). */
    val polling: Boolean get() = running.value == 1

    override suspend fun subscribe(): Pair<String, StateFlow<String>> {
        if (running.compareAndSet(0, 1)) {
            val c = cfg()
            val (reader, store) = withContext(Dispatchers.Default) { replayed(c.core.dbPath, c.plugins.graph, started) }
            frames.value = callsFrame(CallsView.of(store, clockMs()))
            scope.launch { poll(c, reader, store) }
        }
        return frames.value to calls
    }

    private suspend fun poll(
        c: Config,
        first: Reader?,
        store: CallsStore,
    ) {
        var reader = first
        var sent = CallsView.of(store, clockMs())
        try {
            while (true) {
                delay(pollMs)
                if (frames.subscriptionCount.value == 0) {
                    running.value = 0
                    // A socket that subscribed between the check and the store found the flag still set and started no
                    // poller; take the role back if one did.
                    if (frames.subscriptionCount.value == 0 || !running.compareAndSet(0, 1)) return
                }
                val r = reader ?: Reader.open(c.core.dbPath, c.plugins.graph) ?: continue
                reader = r
                val batch = r.poll()
                val now = clockMs()
                batch?.let { store.fold(it, now) }
                store.sweep(now)
                val view = CallsView.of(store, now)
                // The clock moving is not a change: the page keeps time between frames.
                if (view != sent.copy(now = now)) {
                    frames.value = callsFrame(view)
                    sent = view
                }
            }
        } finally {
            reader?.close()
        }
    }
}
