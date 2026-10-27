// Port of src/web/calls_store.rs `fixtures`: shared with the view's and the TUI pane's tests.
// The figures are the Rust tests' own, kept literal so the two suites read side by side.
@file:Suppress("MagicNumber")

package rtok.web

import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.MeasurementSample

const val T = 1_000_000L

fun event(call: String): GraphEvent = GraphEvent.new(call, EventPhase.Start, "session-abcdef", "callers").copy(project = "rtok", target = "open_index")

fun end(
    call: String,
    before: Int,
    after: Int,
): GraphEvent =
    event(call).copy(
        phase = EventPhase.End,
        backend = "tags",
        ms = 12.0,
        samples = listOf(MeasurementSample(1, "graph.callers", 0, 0, before, after, null)),
    )

/** A batch whose summary counts exactly the events it lists, plus [omitted] ends of 10 to 4. */
fun batch(
    events: List<GraphEvent>,
    omitted: Int,
): CallBatch {
    val ends = events.filter { it.phase == EventPhase.End }
    val samples = ends.flatMap { it.samples }
    return CallBatch(
        events = events,
        omitted = omitted.toLong(),
        ended = ends.map { it.call }.toSet(),
        head = events.size.toLong(),
        summary =
            CallSummary(
                starts = events.count { it.phase == EventPhase.Start }.toLong(),
                ends = ends.size.toLong() + omitted,
                failed = ends.count { !it.ok }.toLong(),
                estBefore = samples.sumOf { it.estBefore.toLong() } + omitted * 10L,
                estAfter = samples.sumOf { it.estAfter.toLong() } + omitted * 4L,
                fallbacks = samples.count { it.kind == "lsp_fallback" }.toLong(),
                caps = samples.count { it.refId != null }.toLong(),
                symbols = ends.sumOf { (it.symbols ?: 0u).toLong() },
                symbolsReturned = ends.sumOf { (it.symbolsReturned ?: 0u).toLong() },
                filesTouched = ends.sumOf { (it.filesTouched ?: 0u).toLong() },
                projectsHit = ends.sumOf { (it.projectsHit ?: 0u).toLong() },
                crossed = ends.count { (it.total ?: 0u) > 1u }.toLong(),
            ),
    )
}
