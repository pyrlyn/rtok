// Port of the tests of src/web/calls_store.rs.
// The figures are the Rust tests' own, kept literal so the two suites read side by side.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.web

import rtok.config.Graph
import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.MeasurementSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallsStoreTest {
    private fun CallsStore.folded(
        events: List<GraphEvent>,
        now: Long,
    ) = fold(batch(events, 0), now)

    @Test
    fun aSparklineSpreadsTheBucketsOverEqualSlotsAndAddsUpToTheWindow() {
        val s = CallsStore()
        val now = T + 600_000
        s.folded(listOf(end("old", 100, 30)), T)
        s.folded(listOf(end("edge", 40, 10)), now - 60_000)
        s.folded(listOf(end("new", 20, 5), end("new2", 8, 8)), now - 1000)
        val long = s.spark(2, now)
        assertEquals(900_000L to SPARK_SLOTS, long.spanMs to long.calls.size)
        assertEquals(1L to 70L, long.calls[9] to long.saved[9])
        assertEquals(1L to 30L, long.calls[27] to long.saved[27])
        assertEquals(2L to 15L, long.calls[29] to long.saved[29])
        val w = s.windowTotals(2, now)
        assertEquals(w.calls, long.calls.sum())
        assertEquals(w.before - w.after, long.saved.sum())
        val short = s.spark(0, now)
        assertEquals(listOf(60_000L, 1L, 2L), listOf(short.spanMs, short.calls[0], short.calls[29]))
        assertEquals(3L, short.calls.sum())
        assertEquals(long, s.spark(3, now))
    }

    @Test
    fun theScopeIsTheDrilledOrSelectedProjectAndWhatItsLinksReach() {
        val rows = listOf(ScopeRow(1, "a", true, listOf(2, 9)), ScopeRow(2, "b", false, listOf(1)), ScopeRow(3, "c", false, emptyList()))
        val names = scopeNames(rows, null)!!
        assertEquals(setOf("a", "b"), names)
        assertEquals(setOf("c"), scopeNames(rows, 3))
        assertNull(scopeNames(listOf(ScopeRow(1, "a", false, emptyList())), null))
        assertNull(scopeNames(rows, 42))
        assertTrue(outsideScope(names, "c"))
        assertTrue(!outsideScope(names, "a"))
        assertTrue(!outsideScope(names, null), "no project is never outside")
        assertTrue(!outsideScope(null, "c"), "no scope, nothing outside")
    }

    @Test
    fun theSparklineIsNamedWithThePagesWording() {
        assertEquals("calls over the last 5 min", sparkLabel("calls", 300_000))
        assertEquals("tokens saved over the last 15 min", sparkLabel("tokens saved", 900_000))
    }

    @Test
    fun foldedTargetsCountOnTheNearestDrawnAncestorElseOnTheGroup() {
        fun target(
            call: String,
            t: String,
            project: String,
        ) = end(call, 10, 5).copy(target = t, project = project)
        val s = CallsStore()
        s.folded(
            listOf(
                target("a", "src/web/calls.rs", "rtok"),
                target("b", "src/web/x/deep.rs", "rtok"),
                target("c", "src/webby/other.rs", "rtok"),
                target("d", "drawn_symbol", "rtok"),
                target("e", "src/web/out.rs", "elsewhere"),
                event("run").copy(target = "src/web/mod.rs"),
            ),
            T,
        )
        val drawn = listOf(DrawnNode("src", "src"), DrawnNode("web", "src/web"), DrawnNode("drawn_symbol", "src/lib.rs"))
        val got = s.foldedCounters(drawn, 7, setOf("rtok"), T)
        assertEquals(listOf("web" to 3L, "src" to 1L), got.map { it.label to it.calls })
        val lone = CallsStore()
        lone.folded(listOf(target("z", "other/z.rs", "rtok")), T)
        assertEquals(listOf(FoldedCount("+7 more", 1)), lone.foldedCounters(drawn, 7, null, T))
        assertTrue(s.foldedCounters(drawn, 0, null, T).isEmpty())
        assertTrue(s.foldedCounters(drawn, 7, null, T + FOLD_MS + 1).isEmpty())
    }

    @Test
    fun aStartRunsAndItsEndMovesItToTheFeedWithTheMeasuredTokens() {
        val s = CallsStore()
        s.folded(listOf(event("a")), T)
        assertEquals(listOf("a"), s.running.map { it.call })
        s.folded(listOf(end("a", 100, 30)), T + 50)
        assertTrue(s.running.isEmpty())
        assertEquals(100L to 30L, s.feed[0].before to s.feed[0].after)
        assertEquals("tags", s.feed[0].backend)
        assertEquals(listOf(1L, 100L, 30L), listOf(s.all.calls, s.all.before, s.all.after))
        assertEquals(ToolTotals(1, 70), s.all.tools["callers"])
    }

    @Test
    fun metricsAreCountedFromTheEventsAndThePercentilesAreMeasuredLatencies() {
        fun sample(
            kind: String,
            refId: String?,
        ) = MeasurementSample(1, kind, 0, 0, 0, 0, refId)

        fun timed(
            call: String,
            ms: Double,
        ) = end(call, 0, 0).copy(ms = ms, samples = emptyList())
        val a =
            timed("a", 10.0).copy(
                symbols = 2u,
                total = 3u,
                symbolsReturned = 1u,
                filesTouched = 4u,
                projectsHit = 2u,
                samples = listOf(sample("lsp_fallback", null)),
            )
        val b =
            timed("b", 20.0).copy(
                symbols = 1u,
                total = 1u,
                symbolsReturned = 1u,
                filesTouched = 2u,
                projectsHit = 1u,
                samples = listOf(sample("cap", "ab"), sample("explore", null)),
            )
        val s = CallsStore()
        s.folded(listOf(a, b, timed("c", 30.0), timed("d", 40.0), timed("e", 100.0)), T)
        val t = s.windowTotals(0, T)
        assertEquals(listOf(1L, 1L, 3L, 1L), listOf(t.fallbacks, t.caps, t.symbols, t.crossed), "a row with no ref_id is not a cap")
        assertEquals(listOf(2L, 6L, 3L), listOf(t.symbolsReturned, t.filesTouched, t.projectsHit))
        assertEquals(30.0 to 100.0, t.latency())
        assertEquals(t.latency(), s.all.latency())
        assertNull(CallsStore().all.latency())
    }

    @Test
    fun theLatenciesKeptAreTheNewestOnes() {
        val s = CallsStore()
        for (i in 0 until LATENCY_KEEP + 5) s.folded(listOf(end("c$i", 0, 0).copy(ms = i.toDouble())), T + i)
        assertEquals(LATENCY_KEEP, s.all.ms.size)
        assertEquals(5.0, s.all.ms[0])
    }

    @Test
    fun aFailedEndCountsAndKeepsItsError() {
        val s = CallsStore()
        s.folded(listOf(end("a", 0, 0).copy(ok = false, error = "no backend")), T)
        assertEquals(1L, s.all.failed)
        assertEquals("no backend", s.feed[0].error)
    }

    @Test
    fun totalsCountTheEventsABurstBatchLeftOut() {
        val s = CallsStore()
        s.fold(batch(listOf(end("a", 100, 30)), 400), T)
        assertEquals(401L, s.all.calls)
        assertEquals(100L + 4000, s.all.before)
        assertEquals(30L + 1600, s.all.after)
    }

    @Test
    fun theFeedKeepsTheNewestRowsFirstUpToTheConfiguredCap() {
        val s = CallsStore(Graph(liveFeedRows = 5u, liveHeatWindowS = 90u))
        for (i in 0 until 8) s.folded(listOf(end("c$i", 2, 1)), T + i)
        assertEquals(5, s.feed.size)
        assertEquals("c7", s.feed[0].call)
        assertEquals(8L, s.all.calls)
        assertEquals(90L, s.heatWindowS)
        assertEquals(200, CallsStore().feedRows, "the shipped default")
    }

    @Test
    fun aRowCarriesTheNameTheBatchGivesItsSessionElseTheSession() {
        val s = CallsStore()
        val b = batch(listOf(event("run"), end("a", 2, 1)), 0).copy(callers = mapOf("session-abcdef" to "claude 3f9a1c2e"))
        s.fold(b, T)
        assertEquals("claude 3f9a1c2e", s.running[0].caller)
        assertEquals("claude 3f9a1c2e", s.feed[0].caller)
        s.folded(listOf(end("b", 2, 1).copy(session = "mcp-1234567890")), T + 1)
        assertEquals("mcp-1234", s.feed[0].caller, "no name: the session's start")
        assertEquals(1, filterFeed(s.feed, FeedFilter(caller = "claude 3f9a1c2e")).size)
    }

    @Test
    fun eachWindowIsSummedFromTheStoresBatches() {
        val s = CallsStore()
        s.folded(listOf(end("old", 10, 5)), T)
        s.folded(listOf(end("mid", 20, 5)), T + 6 * 60_000)
        s.folded(listOf(end("new", 40, 5)), T + 9 * 60_000)
        val now = T + 9 * 60_000 + 1000
        assertEquals(listOf(40L, 60L, 70L, 70L), (0..3).map { s.windowTotals(it, now).before })
    }

    @Test
    fun aBatchOlderThanTheLongestWindowLeavesTheBucketsButNotTheTotal() {
        val s = CallsStore()
        s.folded(listOf(end("old", 10, 5)), T)
        s.folded(listOf(end("new", 40, 5)), T + 16 * 60_000)
        assertEquals(1, s.bucketCount)
        assertEquals(2L, s.windowTotals(3, T + 16 * 60_000).calls)
    }

    @Test
    fun aCallWithNoEndAfterTheTimeoutIsMarkedAndStopsRunning() {
        val s = CallsStore()
        s.folded(listOf(event("a")), T)
        s.sweep(T + INTERRUPT_MS)
        assertEquals(1, s.running.size, "the timeout is exclusive")
        s.sweep(T + INTERRUPT_MS + 1)
        assertTrue(s.running.isEmpty())
        assertTrue(s.feed[0].interrupted && s.feed[0].call == "a")
        assertEquals(0L, s.all.calls)
        s.folded(listOf(end("a", 10, 5)), T + INTERRUPT_MS + 2)
        assertEquals(listOf("a" to false), s.feed.map { it.call to it.interrupted }, "a late end replaces the mark")
    }
}

/** The display cap (T573) and the feed filters. */
class CallsStoreFeedTest {
    private fun CallsStore.folded(
        events: List<GraphEvent>,
        now: Long,
    ) = fold(batch(events, 0), now)

    /** T573: a poll's events through the real [coalesce] with the display cap [cap]. */
    private fun CallsStore.polled(
        cap: Int,
        events: List<Pair<String, EventPhase>>,
        now: Long,
    ) {
        val evs =
            events.mapIndexed { i, (call, phase) ->
                (if (phase == EventPhase.End) end(call, 10, 4) else event(call)).copy(phase = phase, id = i + 1L)
            }
        fold(coalesce(evs, cap)!!, now)
    }

    @Test
    fun anEndTheDisplayCapCutStillClearsItsCallAndNeverBecomesInterrupted() {
        val burst =
            listOf(
                "a" to EventPhase.Start,
                "b" to EventPhase.Start,
                "a" to EventPhase.Progress,
                "a" to EventPhase.End,
                "b" to EventPhase.End,
            )
        for (cap in listOf(100, 1)) {
            val s = CallsStore()
            s.polled(cap, burst, T)
            assertTrue(s.running.isEmpty(), "cap $cap: both calls ended")
            assertEquals(2L, s.all.calls, "cap $cap")
            s.sweep(T + INTERRUPT_MS + 1)
            assertTrue(s.feed.none { it.interrupted }, "cap $cap: no false interruption")
        }
        val s = CallsStore()
        s.polled(1, listOf("a" to EventPhase.Start, "b" to EventPhase.Start), T)
        s.polled(1, listOf("a" to EventPhase.End, "b" to EventPhase.End, "z" to EventPhase.End), T + 1)
        assertTrue(s.running.isEmpty())
        assertEquals(3L, s.all.calls)
        s.sweep(T + INTERRUPT_MS + 2)
        assertTrue(s.feed.none { it.interrupted })
    }

    @Test
    fun aCallWithNoEndStaysVisibleWhenEndedRowsCrowdTheCap() {
        val s = CallsStore()
        s.polled(
            1,
            listOf("p" to EventPhase.Start, "a" to EventPhase.Start, "a" to EventPhase.End, "b" to EventPhase.Start, "b" to EventPhase.End),
            T,
        )
        assertEquals(listOf("p"), s.running.map { it.call }, "the unfinished call survives the cut")
        s.sweep(T + INTERRUPT_MS + 1)
        assertTrue(s.feed.any { it.call == "p" && it.interrupted })
    }

    @Test
    fun aCappedEndAfterARealTimeoutReplacesTheInterruptedMark() {
        val s = CallsStore()
        s.polled(1, listOf("a" to EventPhase.Start), T)
        s.sweep(T + INTERRUPT_MS + 1)
        assertTrue(s.feed[0].interrupted)
        s.polled(1, listOf("a" to EventPhase.End, "b" to EventPhase.End), T + INTERRUPT_MS + 2)
        assertTrue(s.feed.none { it.call == "a" && it.interrupted })
    }

    @Test
    fun callerToolAndProjectNarrowTheRowsAndBlankMeansAll() {
        fun row(
            call: String,
            session: String,
            tool: String,
            project: String,
        ) = end(call, 2, 1).copy(session = session, tool = tool, project = project)
        val s = CallsStore()
        s.folded(listOf(row("a", "x", "callers", "p"), row("b", "y", "impact", "p"), row("c", "y", "callers", "q")), T)

        fun calls(f: FeedFilter) = filterFeed(s.feed, f).map { it.call }
        assertEquals(3, calls(FeedFilter()).size)
        assertEquals(listOf("c", "b"), calls(FeedFilter(caller = "y")))
        assertEquals(listOf("c"), calls(FeedFilter(caller = "y", tool = "callers")))
        assertEquals(listOf("p", "q"), distinct(s.feed) { it.project })
    }
}
