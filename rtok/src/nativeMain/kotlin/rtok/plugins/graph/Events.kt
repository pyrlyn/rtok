// Port of src/plugins/graph/events.rs (T329.15): the start, progress and end events of one graph tool call,
// appended to the store so `rtok web` can stream them. Every write fails open.
package rtok.plugins.graph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.graphMeasurementsAfter
import rtok.store.insertGraphEvent
import rtok.store.measurementHead
import kotlin.concurrent.AtomicLong
import kotlin.time.TimeSource

/** One tracked call: [start] writes the start event; [end] the end event with the measurement rows it left. */
class GraphCall private constructor(
    private val cx: Runtime,
    private val key: String,
    private val tool: String,
    private val mark: Int,
    private val target: String?,
    private val project: String?,
    private val symbols: UInt?,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var total: UInt? = null

    /** The scope is resolved: [n] projects will be asked. */
    fun progress(n: Int) {
        val t = n.toUInt()
        total = t
        write(event(EventPhase.Progress).copy(done = 0u, total = t))
    }

    fun end(result: Result<String>) {
        var e = event(EventPhase.End).copy(ms = started.elapsedNow().inWholeMicroseconds / 1000.0, total = total)
        val found = Tally.take()
        e =
            result.fold({ text ->
                val base = e.copy(done = total, backend = backend(text), answerTokens = cx.estimate(text, Class.Json))
                if (tool in COUNTED) base.copy(symbolsReturned = found.symbols.toUInt(), filesTouched = found.files.toUInt(), projectsHit = found.projects.toUInt()) else base
            }) { err -> e.copy(ok = false, error = err.message ?: err.toString()) }
        e = e.copy(samples = runCatching { cx.store.graphMeasurementsAfter(cx.session, mark) }.getOrDefault(emptyList()))
        write(e)
    }

    private fun event(phase: EventPhase) = GraphEvent.new(key, phase, cx.session, tool).copy(target = target, project = project, symbols = symbols)

    private fun write(e: GraphEvent) {
        runCatching { cx.store.insertGraphEvent(e) }
    }

    companion object {
        /** The tools whose backends call `Tally.hit` for every row they list. */
        val COUNTED = setOf("symbol", "callers", "impact", "outline", "explore", "graph_diff")
        private val calls = AtomicLong(0)

        fun start(
            cx: Runtime,
            tool: String,
            args: JsonObject,
        ): GraphCall {
            val key = "${cx.session}:${calls.incrementAndGet()}"
            Tally.arm()
            val mark = runCatching { cx.store.measurementHead() }.getOrDefault(0)
            return GraphCall(cx, key, tool, mark, target(args), project(args), symbols(args)).also { it.write(it.event(EventPhase.Start)) }
        }

        /** What the call is about: the symbol, the first of several, the query, the path or the id. */
        fun target(args: JsonObject): String? {
            val first = ((args["names"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
            return listOf(Outline.str(args["name"]), first.orEmpty(), Outline.str(args["query"]), Outline.str(args["path"]), Outline.str(args["id"])).firstOrNull { it.isNotEmpty() }
        }

        /** Symbols asked for, counted from the arguments; null when the tool takes none. */
        fun symbols(args: JsonObject): UInt? {
            val named = Outline.symbolArgNames(args).count { it.isNotEmpty() }
            val byId = if (Outline.str(args["id"]).isNotEmpty()) 1 else 0
            val tokens = (args["query"] as? JsonPrimitive)?.takeIf { it.isString }?.let { Explore.tokens(it.content).size } ?: 0
            return (named + byId + tokens).takeIf { it > 0 }?.toUInt()
        }

        private fun project(args: JsonObject): String? =
            Outline.str(args["project"]).ifEmpty { null } ?: runCatching {
                rtok.projectName(
                    rtok.plugins.read.Roots
                        .graphCwd(),
                )
            }.getOrNull()

        /** The `(lsp)` / `(tags)` / `(tags; lsp: …)` header `lsp_or_tags` puts on an `auto` answer. */
        fun backend(text: String): String? {
            val head = text.lineSequence().firstOrNull()?.takeIf { text.isNotEmpty() } ?: return null
            return listOf("lsp", "tags", "text").firstOrNull { head.startsWith("(") && head.substring(1).startsWith(it) }
        }
    }
}
