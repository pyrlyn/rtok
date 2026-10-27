package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures and cases from web/src/pages/textFixtures.ts, text.test.ts and pagesRest.test.tsx.
class StatsTest {
    private val statsText =
        listOf(
            "sessions 42  compact 3  checkpoint 5  no_checkpoint 37  lines 18204  malformed 0",
            "usage input=1204332 cache_create=388120 cache_read=9120554 output=96310  hit=85.0%  median_context=61240",
            "sub-agents 3 sessions / 7 agents  results 90210 B vs parents 410022 B (18.0% of the tree)",
            "cost (USD at [stats.prices] \$/MTok; `-` = no price row)",
            "model                           input cache_create   cache_read       output       cost      saved",
            "claude-sonnet-5                902114       301220      7203311        71022      12.84       3.10",
            "claude-haiku-4-5                88410        20110       611223         9120       0.41       0.07",
            "gpt-5                          213808        66790      1306020        16168       1.92       0.38",
            "claude-opus-4-1                   0            0            0            0          -          -",
            "cost total \$15.17 (cache reads saved \$3.55; 1 model(s) without a price)",
            "thinking blocks 12 bytes 40211 est. tokens 10052 0.0840% of session input",
            "",
            "cache health",
            "session                                   turns   cache_read cache_create  busts",
            "3f9a2c1e-7b41-4d0a-9c55-0e12ab34cd56          88      2210334       101220      1",
            "9c0d4e2a-11f3-4b7e-8a20-5d6e7f809a1b          41       903311        48020      0",
            "a17be5f0-2c9d-4e61-b3a4-7f8091a2b3c4          17       221080        30112      2",
            "  bust turn 41 cause=tools cache_create=48120 cache_read=0",
            "  bust turn 6 cause=tools cache_create=31000 cache_read=0",
            "",
        ).joinToString("\n")

    private val v = parseStats(statsText)

    @Test
    fun readsTotalsUsagePricedRowsAndTheUnpricedDash() {
        assertEquals("42", v.totals["sessions"])
        assertEquals("85.0%", v.usage["hit"])
        assertEquals(listOf("claude-sonnet-5", "claude-haiku-4-5", "gpt-5", "claude-opus-4-1"), v.priced.map { it.model })
        assertEquals(12.84, v.priced[0].cost)
        assertEquals(3.1, v.priced[0].saved)
        assertEquals(7_203_311.0, v.priced[0].cacheRead)
        assertNull(v.priced[3].cost)
        assertNull(v.priced[3].saved)
    }

    @Test
    fun takesTheCostTotalLine() {
        assertEquals(CostTotal(15.17, 3.55), v.costTotal)
    }

    @Test
    fun readsCacheHealthRowsAndBusts() {
        assertEquals(3, v.health.size)
        assertEquals(17.0, v.health[2].turns)
        assertEquals(2.0, v.health[2].busts)
        assertEquals(listOf(Bust("41", "tools", 48_120.0, 0.0), Bust("6", "tools", 31_000.0, 0.0)), v.busts)
    }

    @Test
    fun keepsTheSectionsItDoesNotChart() {
        assertEquals(listOf("sub-agents", "thinking"), v.other.map { it.split(" ")[0] })
    }

    @Test
    fun anEmptyReportKeepsTheHintLine() {
        val e =
            parseStats(
                "sessions 0  compact 0  checkpoint 0  no_checkpoint 0  lines 0  malformed 0\n\ncache health\n" +
                    "session  turns  cache_read  cache_create  busts\nno usage rows (run `rtok proxy` first)\n",
            )
        assertTrue(e.priced.isEmpty() && e.health.isEmpty() && e.busts.isEmpty())
        assertEquals(listOf("no usage rows (run `rtok proxy` first)"), e.other)
    }

    @Test
    fun costTotalsPreferTheReportTotalElseSum() {
        assertEquals(CostTotal(15.17, 3.55), costTotals(v))
        val noTotal = parseStats(statsText.lines().filterNot { it.startsWith("cost total") }.joinToString("\n"))
        assertEquals(15.17, costTotals(noTotal).cost, 1e-9)
    }

    private fun skill(
        name: String,
        source: String = "user",
        descChars: Long = 120,
        bodyBytes: Long = 2_000,
        invocations: Long = 4,
        never: Boolean = false,
    ) = SkillRow(name, source, descChars, bodyBytes, invocations, 1, "2026-09-27", never)

    @Test
    fun skillWarningsAndFilter() {
        val worktrees = skill("worktrees", descChars = 212, bodyBytes = 3_400, invocations = 9)
        val release = skill("release", source = "project", bodyBytes = 11_200, invocations = 0, never = true)
        assertEquals(listOf("long description"), skillWarnings(worktrees))
        assertEquals(listOf("body > 8 KB", "never invoked"), skillWarnings(release))
        assertTrue(matchesSkill(release, true, ""))
        assertFalse(matchesSkill(worktrees, true, ""))
        assertFalse(matchesSkill(worktrees, false, "PROJECT"))
        assertTrue(matchesSkill(release, false, "PROJECT"))
    }
}
