// Port of src/render.rs sessions tests (T25.2, T25.3) and log.rs `watch_loop` behaviour: live rows, durations, the
// empty page, the watch tick, a pipe appending only fresh rows and a TTY repainting in place.
package rtok.agents

import rtok.log.WatchTick
import rtok.log.watchLoop
import rtok.model.Agents
import rtok.store.SessionTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val STARTED = 1_788_966_245L
private const val GONE_START = 1_788_900_000L
private const val GONE_RAN = 7_265L
private const val LIVE_RAN = 65L
private const val MINUTE = 60L
private const val IDLE = 1_800L
private const val TOKENS_IN = 30L
private const val TOKENS = 7L
private const val SEEN_AFTER = 100L
private const val PARENT = 7
private const val INIT = 1

class SessionsTableTest {
    private fun totals(
        id: String,
        host: String?,
        ended: Long?,
        started: Long,
        model: String = "claude-x",
    ) = SessionTotals(id, host, "rtok", "anthropic", "anthropic", model, TOKENS_IN, 1, TOKENS, TOKENS, started, started + SEEN_AFTER, ended)

    private fun views(
        rows: List<SessionTotals>,
        now: Long,
    ) = Agents.sessionViews(rows, emptyList(), now, IDLE, true)

    private fun hostIs(
        line: String,
        host: String,
    ) = line.split(Regex("\\s+")).getOrNull(1) == host

    @Test fun durationsUseTwoUnitsAtMost() {
        val cases = mapOf("0s" to 0L, "45s" to "45".toLong(), "1m05s" to "65".toLong(), "1h01m" to "3661".toLong(), "1d01h" to "90000".toLong())
        cases.forEach { (want, secs) -> assertEquals(want, SessionsTable.duration(secs)) }
        assertEquals("0s", SessionsTable.duration(-1), "clock skew is not a negative runtime")
    }

    @Test fun aSessionsPageRendersLiveRowsAndDurations() {
        val rows = listOf(totals("live", "claude", null, STARTED), totals("gone", "pi", GONE_START + GONE_RAN, GONE_START))
        val now = STARTED + LIVE_RAN
        val live = SessionsTable.render(views(rows, now), false, now)
        assertTrue(live.startsWith("agent"), live)
        assertEquals(listOf("agent", "live").size, live.lines().count { it.isNotEmpty() }, live)
        assertTrue("claude-x" in live && "2026-09-09 15:04:05" in live && "1m05s" in live, live)
        assertFalse(live.lines().any { hostIs(it, "pi") }, live)
        val all = SessionsTable.render(views(rows, now), true, now)
        assertEquals(rows.size + 1, all.lines().count { it.isNotEmpty() }, all)
        assertTrue(all.lines().any { hostIs(it, "pi") } && " 2h01m" in all, all)
    }

    @Test fun anEmptySessionsPageSaysNothingIsRunning() {
        val out = SessionsTable.render(emptyList(), false, 0)
        assertTrue(out.startsWith("agent") && out.endsWith("\nnothing is running\n"), out)
        assertEquals(out.lines().first() + "\nno sessions\n", SessionsTable.render(emptyList(), true, 0))
    }

    @Test fun aSessionsTickRepaintsStateAndStaysQuietOtherwise() {
        val now = STARTED + LIVE_RAN
        val rows = mutableListOf(totals("live", "claude", null, STARTED))
        val t = SessionsTable.Ticker()
        val first = t.tick(views(rows, now), false, now)
        assertTrue(first.fresh.isNotEmpty() && first.fresh == first.screen && first.screen.any { "claude" in it })
        assertFalse((first.fresh + first.screen).any { '\u001b' in it })
        val quiet = t.tick(views(rows, now), false, now)
        assertTrue(quiet.fresh.isEmpty())
        assertEquals(first.screen, quiet.screen)
        val later = t.tick(views(rows, now + MINUTE), false, now + MINUTE)
        assertTrue(later.fresh.isNotEmpty() && later.screen.any { "2m05s" in it })
        rows += totals("new", "pi", null, now, model = "watch-new")
        assertTrue(t.tick(views(rows, now + MINUTE), false, now + MINUTE).screen.any { "watch-new" in it })
        assertEquals(t.tick(views(rows, now + MINUTE), false, now + MINUTE).screen, t.keep().screen)
    }

    private fun loop(
        tty: Boolean,
        ticks: List<WatchTick>,
        parents: List<Int> = List(ticks.size + 1) { PARENT },
    ): String {
        val out = StringBuilder()
        val it = ticks.iterator()
        val p = parents.iterator()
        watchLoop(tty, parent = { p.next() }, sleep = {}, write = {
            out.append(it)
            true
        }) { if (it.hasNext()) it.next() else null }
        return out.toString()
    }

    @Test fun aPipeAppendsOnlyFreshRowsAndATtyRepaintsInPlace() {
        val ticks = listOf(WatchTick(listOf("a", "b"), listOf("a", "b")), WatchTick(emptyList(), listOf("a", "b")), WatchTick(listOf("c"), listOf("c")))
        assertEquals("a\nb\nc\n", loop(false, ticks))
        assertEquals("\u001b[Ja\nb\n\u001b[2F\u001b[Jc\n", loop(true, ticks))
    }

    @Test fun beingReparentedEndsTheWatch() {
        val ticks = listOf(WatchTick(listOf("a"), listOf("a")), WatchTick(listOf("b"), listOf("b")))
        assertEquals("a\n", loop(false, ticks, listOf(PARENT, PARENT, INIT)))
    }
}
