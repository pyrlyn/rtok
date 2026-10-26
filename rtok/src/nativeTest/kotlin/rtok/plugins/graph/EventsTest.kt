package rtok.plugins.graph

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import rtok.plugin.Runtime
import rtok.plugin.sdk.Measurement
import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.graphEventsAfter
import rtok.store.insertMeasurement

class EventsTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun row(kind: String, ref: String? = null) = Measurement("graph", kind, 40u, 10u, 12u, 3u, ref, null)

    @Test fun targetPrefersTheSymbolThenTheQuery() {
        assertEquals("f", GraphCall.target(o("""{"name":"f","query":"q"}""")))
        assertEquals("a", GraphCall.target(o("""{"names":["a","b"]}""")))
        assertEquals("q", GraphCall.target(o("""{"query":"q"}""")))
        assertNull(GraphCall.target(o("""{"name":""}""")))
    }

    @Test fun symbolsCountsTheNamesAndTheIdAsked() {
        assertEquals(2u, GraphCall.symbols(o("""{"names":["a","","b"]}""")))
        assertEquals(1u, GraphCall.symbols(o("""{"name":"f"}""")))
        assertEquals(1u, GraphCall.symbols(o("""{"id":"x::f#function@1"}""")))
        assertNull(GraphCall.symbols(o("""{"path":"a.rs"}""")))
        assertEquals(5u, GraphCall.symbols(o("""{"query":"how does f call g, f"}""")))
        assertNull(GraphCall.symbols(o("""{"query":"?"}""")))
    }

    @Test fun backendReadsTheAnswerHeader() {
        assertEquals("lsp", GraphCall.backend("(lsp)\nx")); assertEquals("tags", GraphCall.backend("(tags; lsp: down)\nx"))
        assertNull(GraphCall.backend("a.rs:1 f")); assertNull(GraphCall.backend(""))
    }

    @Test fun aCallWritesThreeEventsWithItsOwnRows() {
        val cx = Runtime.inMemory("mcp-1")
        val call = GraphCall.start(cx, "symbol", o("""{"names":["f","g"]}"""))
        call.progress(2)
        cx.record(row("cap", "ab")); cx.store.insertMeasurement("mcp-2", row("cap", "ab"))
        call.end(Result.success("(lsp)\na.rs:1 f"))
        val ev = cx.store.graphEventsAfter(0, 10)
        assertEquals(listOf(EventPhase.Start, EventPhase.Progress, EventPhase.End), ev.map { it.phase })
        val end = ev[2]
        assertEquals(ev[0].call, end.call); assertEquals("lsp", end.backend)
        assertEquals(2u to 2u, end.done to end.total)
        assertEquals(1, end.samples.size); assertEquals(12, end.samples[0].estBefore); assertEquals("ab", end.samples[0].refId)
        assertTrue(ev.all { it.symbols == 2u }); assertEquals("f", ev[0].target); assertEquals(ev[0].target, end.target)
    }

    @Test fun theEndEventCarriesWhatTheBackendsCounted() {
        val cx = Runtime.inMemory("mcp-1")
        GraphCall.start(cx, "callers", o("""{"name":"f"}""")).also { Tally.hit("/p", "f", listOf("a.rs", "b.rs")) }.end(Result.success("a.rs ×1\nb.rs ×1\n"))
        GraphCall.start(cx, "graph_export", o("{}")).also { Tally.hit("/p", "f", listOf("a.rs")) }.end(Result.success("x"))
        GraphCall.start(cx, "explore", o("""{"query":"f"}""")).end(Result.success("no symbols resolved"))
        val ends = cx.store.graphEventsAfter(0, 10).filter { it.phase == EventPhase.End }
        fun counts(e: GraphEvent) = Triple(e.symbolsReturned, e.filesTouched, e.projectsHit)
        assertEquals(Triple(1u, 2u, 1u), counts(ends[0]))
        assertEquals(Triple<UInt?, UInt?, UInt?>(null, null, null), counts(ends[1]))
        assertEquals(Triple(0u, 0u, 0u), counts(ends[2]))
    }

    @Test fun aFailedCallEndsWithItsError() {
        val cx = Runtime.inMemory("mcp-1")
        GraphCall.start(cx, "impact", o("{}")).end(Result.failure(IllegalStateException("no backend")))
        val end = cx.store.graphEventsAfter(0, 10).last()
        assertFalse(end.ok); assertEquals("no backend", end.error); assertTrue(end.samples.isEmpty())
    }
}
