package rtok.store

import kotlin.test.*

/** Port of symbol_graph.rs and graph_events.rs tests. */
class GraphTest {
    private val s = Store.openInMemory()
    private fun def(name: String, line: Int, end: Int) = rtok.plugin.sdk.SymbolRow(name, "function", line, true, end, "")
    private fun call(name: String, line: Int, scope: String) = rtok.plugin.sdk.SymbolRow(name, "call", line, false, line, scope)
    private fun import(spec: String, line: Int) = rtok.plugin.sdk.SymbolRow("X", "import", line, false, line, spec)
    private fun event(call: String, phase: EventPhase) = GraphEvent.new(call, phase, "mcp-1", "callers")

    @Test fun scansGroupReferencesByTheirEnclosingDefinition() {
        s.index("/r", "x.rs", def("a", 1, 5), call("b", 2, "a"), call("b", 3, "a"), call("b", 9, ""))
        val defs = s.symbolDefScan("/r"); assertEquals("a" to 5, defs[0].name to defs[0].endLine)
        assertEquals(listOf("" to 1L, "a" to 2L), s.symbolRefScan("/r").map { it.scope to it.count }); assertTrue(s.symbolRefScan("/other").isEmpty())
    }

    @Test fun anImportPointsAtTheLongestMatchingFileOnly() {
        s.replaceSymbols("/r", "src/a/mod.rs", "1", 0L to 0L, listOf(def("m", 1, 2))); s.replaceSymbols("/r", "src/a/b.rs", "2", 0L to 0L, listOf(def("b", 1, 2)))
        s.replaceSymbols("/r", "src/main.rs", "3", 0L to 0L, listOf(def("main", 1, 3), import("crate::a::b::X", 1), import("Y", 2)))
        assertEquals(listOf("src/main.rs" to "src/a/b.rs"), s.symbolImportEdges("/r"))
    }

    @Test fun defsNamedReadsOnlyTheAskedNames() {
        s.index("/r", "x.rs", def("a", 1, 2), def("b", 3, 4)); val got = s.symbolDefsNamed("/r", listOf("b")); assertEquals(1, got.size); assertEquals(3, got[0].line)
    }

    @Test fun eventsComeBackAfterTheCursorInOrder() {
        assertEquals(0L, s.graphEventHead()); val a = s.insertGraphEvent(event("c1", EventPhase.Start))
        val end = event("c1", EventPhase.End).copy(ms = 1.5, answerTokens = 7u, samples = listOf(MeasurementSample(3, "cap", 10, 4, 5, 2, "ab")),
            symbols = 3u, symbolsReturned = 2u, filesTouched = 5u, projectsHit = 1u)
        val b = s.insertGraphEvent(end); assertEquals(b, s.graphEventHead())
        val all = s.graphEventsAfter(0, 10); assertEquals(listOf(a, b), all.map { it.id })
        assertEquals(end.samples, all[1].samples); assertEquals(7u, all[1].answerTokens); assertEquals(null to 3u, all[0].symbols to all[1].symbols)
        assertEquals(listOf(null, 2u, 5u, 1u), listOf(all[0].filesTouched, all[1].symbolsReturned, all[1].filesTouched, all[1].projectsHit))
        assertTrue(s.graphEventsAfter(b, 10).isEmpty()); assertEquals(1, s.graphEventsAfter(0, 1).size)
    }

    @Test fun theTableKeepsOnlyTheNewestRowsAndClipsText() {
        val e = event("c", EventPhase.Start).copy(target = "x".repeat(1000))
        s.conn { db -> db.transaction { repeat((GRAPH_EVENTS_KEEP + 20).toInt()) { s.insertGraphEvent(e) } } }
        val all = s.graphEventsAfter(0, GRAPH_EVENTS_KEEP * 2); assertEquals(GRAPH_EVENTS_KEEP.toInt(), all.size); assertEquals(GRAPH_TEXT_MAX, all[0].target?.length)
    }

    @Test fun measurementsAfterFiltersBySessionPluginAndId() {
        s.insertMeasurement("mcp-1", meas("graph", "cap", 9u, 3u, 4u, 1u)); val head = s.measurementHead()
        s.insertMeasurement("mcp-1", meas("graph", "tags.callers", 9u, 3u, 4u, 1u)); s.insertMeasurement("mcp-2", meas("graph", "cap", 9u, 3u, 4u, 1u))
        s.insertMeasurement("mcp-1", meas("read", "cap", 9u, 3u, 4u, 1u))
        val got = s.graphMeasurementsAfter("mcp-1", head); assertEquals(1, got.size); assertEquals("tags.callers", got[0].kind); assertEquals(4 to 1, got[0].estBefore to got[0].estAfter)
    }

    @Test fun samplesJsonMatchesSerdeFieldOrderAndKeepsANullRefId() {
        s.insertGraphEvent(event("c", EventPhase.End).copy(samples = listOf(MeasurementSample(1, "cap", 2, 1, 1, 0))))
        assertEquals("""[{"id":1,"kind":"cap","before_bytes":2,"after_bytes":1,"est_before":1,"est_after":0,"ref_id":null}]""", s.conn { it.stringOf("SELECT rows_json FROM graph_events") })
    }
}
