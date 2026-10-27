// Port of the tests of src/web/live.rs (`coalesce`).
// The figures are the Rust tests' own, kept literal so the two suites read side by side.
@file:Suppress("MagicNumber")

package rtok.web

import rtok.config.Graph
import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.MeasurementSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val CAP = 100

class LiveBatchTest {
    private fun ev(
        id: Long,
        call: String,
        phase: EventPhase,
    ) = GraphEvent.new(call, phase, "mcp-1", "callers").copy(id = id)

    @Test
    fun nothingFoundSendsNothing() {
        assertNull(coalesce(emptyList(), CAP))
    }

    @Test
    fun anEndedCallIsListedOnceAndStillCounted() {
        fun row(
            kind: String,
            before: Int,
            after: Int,
            refId: String?,
        ) = MeasurementSample(1, kind, 9, 3, before, after, refId)
        val end =
            ev(3, "a", EventPhase.End).copy(
                samples = listOf(row("cap", 10, 4, "ab"), row("lsp_fallback", 0, 0, null)),
                symbols = 3u,
                symbolsReturned = 2u,
                filesTouched = 4u,
                projectsHit = 2u,
                total = 2u,
            )
        val b = coalesce(listOf(ev(1, "a", EventPhase.Start), ev(2, "a", EventPhase.Progress), end, ev(4, "b", EventPhase.Start)), CAP)!!
        assertEquals(listOf(3L, 4L), b.events.map { it.id }, "a's start and progress are folded into its end; b still runs")
        assertEquals(4L, b.head)
        assertEquals(0L, b.omitted)
        assertEquals(CallSummary(2, 1, 0, 10, 4, 1, 1, 3, 1, 2, 4, 2), b.summary)
    }

    @Test
    fun aBurstIsCappedButItsSummaryCountsEveryCall() {
        val events = ArrayList<GraphEvent>()
        var id = 0L
        for (n in 0 until 500) {
            for (phase in listOf(EventPhase.Start, EventPhase.Progress, EventPhase.End)) {
                id++
                val e = ev(id, "c$n", phase)
                events += if (phase == EventPhase.End) e.copy(ok = n % 10 != 0) else e
            }
        }
        val b = coalesce(events, CAP)!!
        assertEquals(CAP, b.events.size)
        assertEquals(400L, b.omitted)
        assertEquals("c499", b.events.last().call, "the newest survive")
        assertEquals(Triple(500L, 500L, 50L), Triple(b.summary.starts, b.summary.ends, b.summary.failed))
    }

    @Test
    fun aPollListsTheConfiguredRateAtLeastOne() {
        assertEquals(13, eventsPerPoll(Graph()))
        assertEquals(1, eventsPerPoll(Graph(liveMaxEventsPerS = 0u)))
    }
}
