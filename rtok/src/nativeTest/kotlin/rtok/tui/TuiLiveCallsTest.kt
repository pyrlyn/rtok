// Port of the tests of src/tui/live_calls.rs (T480): the Graph tab's live calls pane shows what the web's CallsStore
// computes for the same batches; a freeze holds the picture; the window chips and filters narrow it.
@file:Suppress("MagicNumber")

package rtok.tui

import rtok.web.CallBatch
import rtok.web.T
import rtok.web.batch
import rtok.web.end
import rtok.web.event
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiLiveCallsTest {
    private fun app() = TuiApp("graph").also { assertEquals("graph", it.page) }

    private fun screen(
        a: TuiApp,
        cols: Int = 130,
        rows: Int = 40,
    ) = Buffer(cols, rows).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    private fun press(
        a: TuiApp,
        c: String,
    ) = assertTrue(a.key(Key.Char(c)) != Effect.QUIT)

    private fun firstBatch(): CallBatch = batch(listOf(end("a", 100, 30), end("b", 0, 0).copy(ok = false, error = "no backend", tool = "impact"), event("c")), 0)

    @Test fun barsAndMillisAreTheWebPagesFormats() {
        assertEquals("▁▂▅█", blocks(listOf(0, 1, 4, 8)))
        assertEquals("30 ms", millis(29.6))
        assertEquals("1.5 s", millis(1500.0))
        assertEquals("1.2k", liveCompact(1234))
        assertEquals("34k", liveCompact(34_000))
        assertEquals("5.6M", liveCompact(5_600_000))
    }

    @Test fun thePaneShowsTheTotalsTheWebStoreComputes() {
        val a = app()
        a.live.feedBatch(firstBatch(), T)
        val s = screen(a)
        listOf(
            "running 1 (callers)",
            "calls 2 (1 failed)",
            "sent 30",
            "without rtok 100",
            "saved 70 (70%)",
            "backend tags 100%",
            "graph calls (2 of 2)",
            "no backend",
            "tags · 70 saved · 12 ms",
        ).forEach { assertTrue(it in s, "missing `$it` in\n$s") }
        val t = a.live.latest.windowTotals(3, T)
        assertEquals(listOf(2L, 1L, 100L, 30L), listOf(t.calls, t.failed, t.before, t.after))
    }

    @Test fun theMetricsLineLeavesTheFeedItsRowsAt24Rows() {
        val a = app()
        a.live.feedBatch(firstBatch(), T)
        val s = screen(a, 130, 24)
        listOf("latency p50 12 ms (p95 12 ms)", "no backend", "tags · 70 saved · 12 ms").forEach { assertTrue(it in s, "missing `$it` in\n$s") }
    }

    @Test fun aFreezeHoldsThePictureAndTheUnfreezeShowsEveryHeldCall() {
        val a = app()
        a.live.feedBatch(firstBatch(), T)
        press(a, "f")
        a.live.feedBatch(batch(listOf(end("d", 40, 10), end("e", 40, 10)), 0), T + 1000)
        val held = screen(a)
        assertTrue("frozen · 2 held" in held && "calls 2 (1 failed)" in held && "sent 50" !in held, held)
        press(a, "f")
        val live = screen(a)
        assertTrue("calls 4 (1 failed)" in live && "sent 50" in live && "graph calls (4 of 4)" in live, live)
        assertFalse("frozen" in live, live)
    }

    @Test fun theWindowChipsAndFiltersNarrowThePane() {
        val a = app()
        a.live.feedBatch(firstBatch(), T)
        press(a, "t")
        val s = screen(a)
        assertTrue("graph calls (1 of 2)" in s && "tool [t] callers" in s, s)
        press(a, "t")
        press(a, "t")
        assertTrue("graph calls (2 of 2)" in screen(a), "t wraps to all")
        // The default window is 5 minutes; a batch older than that falls out of it.
        a.live.feedBatch(batch(listOf(end("z", 8, 4)), 0), T + 6 * 60_000)
        assertTrue("calls 1 (0 failed)" in screen(a), screen(a))
        press(a, "w")
        press(a, "w")
        assertTrue("calls 3 (1 failed)" in screen(a), "since start sums all")
    }
}
