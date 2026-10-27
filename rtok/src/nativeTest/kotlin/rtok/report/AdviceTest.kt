// Port of the tests in src/report/advice.rs: top sinks rank by estimated tokens, cache busts group per session and
// cause, and the `SessionEnd` hook setup installs is not idle.
@file:Suppress("MagicNumber")

package rtok.report

import rtok.config.Config
import rtok.model.ReportBust
import rtok.model.ReportCache
import rtok.model.ReportSink
import rtok.model.ReportSinksSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdviceTest {
    private fun withSinks(
        measurements: ULong,
        vararg sinks: ReportSink,
    ) = ReportFixtures.zero().ledgers.let { l -> l.copy(window = l.window.copy(measurements = measurements), sinks = ReportSinksSection(sinks.toList())) }

    @Test fun topSinksRanksLargestSinkFirst() {
        val l = withSinks(2u, ReportSink("cmd", "grep", 200, 2u, "[grep] rule"), ReportSink("read", "src/a.rs", 50, 1u, "[plugins.read] default_mode = full"))
        val found = mutableListOf<Triple<Long, String, String>>()
        Advice.topSinks(l, Config()) { t, rule, finding, _ -> found += Triple(t, rule, finding) }
        assertEquals(2, found.size)
        assertEquals("top-sinks", found[0].second)
        assertTrue("grep" in found[0].third, found[0].third)
        assertTrue(found[0].first >= found[1].first)
    }

    /** The sort key is an estimated token count, not the raw byte count (T302). */
    @Test fun topSinksRanksByEstimatedTokensNotRawBytes() {
        val found = mutableListOf<Long>()
        Advice.topSinks(withSinks(1u, ReportSink("cmd", "grep", 700, 1u, "[grep] rule")), Config()) { t, _, _, _ -> found += t }
        assertEquals(listOf(200L), found)
    }

    @Test fun cacheBustsGroupPerSessionAndCause() {
        fun bust(
            session: String,
            turn: Long,
            cause: String,
        ) = ReportBust(session, turn.toULong(), cause, 100, 0)
        val detail = (1L..8L).map { bust("a", it, "tools") } + listOf(bust("a", 9, "system"), bust("b", 1, "tools"))
        val found = mutableListOf<Pair<Long, String>>()
        Advice.cacheBusts(ReportCache(2u, 10u, 10u, emptyList(), detail)) { t, _, finding, _ -> found += t to finding }
        assertEquals(3, found.size, found.toString())
        assertEquals(800, found[0].first)
        assertTrue("8 times" in found[0].second && "1, 2, 3, 4, 5 and 3 more" in found[0].second, found[0].second)
        assertTrue("session a turn 9" in found[1].second, found[1].second)
    }

    @Test fun theSessionEndRtokInstallsIsNotIdle() {
        assertFalse(Advice.idleByDesign("SessionEnd"))
        assertTrue(Advice.idleByDesign("Stop"))
    }
}
