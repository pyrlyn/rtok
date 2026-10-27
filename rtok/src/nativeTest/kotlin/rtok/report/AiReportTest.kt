// Port of the tests in src/report/ai.rs (T22.4, T580, T664): the task-list close, whole-section drops named in
// `dropped:`, stable ids, toon tables, one-line doctor agents, no non-finite leak, and the exact budget.
@file:Suppress("MagicNumber")

package rtok.report

import rtok.agents.ModuleRow
import rtok.agents.ModuleState
import rtok.config.Config
import rtok.doctor.AgentModules
import rtok.doctor.Report
import rtok.model.ReportBust
import rtok.model.ReportCache
import rtok.model.ReportCalls
import rtok.model.ReportCallsSection
import rtok.model.ReportExpand
import rtok.model.ReportHook
import rtok.model.ReportLedgers
import rtok.model.ReportSavings
import rtok.model.ReportSavingsSection
import rtok.model.ReportSinksSection
import rtok.model.ReportWindow
import rtok.plugin.sdk.Class
import rtok.report.AiFixtures.agent
import rtok.report.AiFixtures.budget
import rtok.report.AiFixtures.doc
import rtok.report.AiFixtures.tokensOf
import rtok.report.AiFixtures.withDb
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiReportTest {
    @Test fun doctorListsOnlyHostsWithRtokInstalled() {
        val d = doc().let { it.copy(doctor = it.doctor.copy(agents = listOf(agent("claude", ModuleState.Installed), agent("zed", ModuleState.NotSupported), agent("kimi", ModuleState.NotInstalled)))) }
        val out = Ai.doctor(d)
        assertContains(out, "  claude (cli)")
        assertFalse("zed (cli)" in out || "kimi (cli)" in out, out)
        assertContains(out, "agents without rtok: 2")
        assertFalse("agents without rtok" in Ai.doctor(d.copy(doctor = d.doctor.copy(agents = d.doctor.agents.take(1)))))
    }

    @Test fun recommendationsCloseAsAnExplicitTaskList() {
        val out = Ai.render(doc(), budget(8000u))
        val tail = out.substring(out.indexOf("#recommendations"))
        assertContains(tail, "1. [expand-rate] pointers frozen (3 rows)")
        assertContains(tail, "2. [cache-bust] tools rewritten (1 turn)")
        assertTrue(out.endsWith("2. [cache-bust] tools rewritten (1 turn)\n"), "nothing follows the task list")
        assertContains(Ai.render(doc().copy(recommendations = emptyList()), budget(8000u)), "no recommendations.")
    }

    @Test fun tightBudgetDropsWholeSectionsAndNamesThem() {
        assertContains(Ai.render(doc(), budget(8000u)), "dropped: none")
        val out = Ai.render(doc(), budget(120u))
        assertFalse("dropped: none" in out, out)
        val dropped =
            out
                .lines()
                .first { it.startsWith("dropped: ") }
                .substring(9)
                .split(',')
        assertTrue(dropped.isNotEmpty())
        for (id in dropped) {
            assertTrue(id in Ai.IDS, "named drops are section ids: $id in $out")
            assertFalse("#$id\n" in out, "a dropped section leaves no heading: $id")
        }
        for (line in out.lines().filter { it.startsWith('[') && "]{" in it }) assertTrue(line.substring(1, line.indexOf(']')).toInt() > 0, line)
    }

    @Test fun headingIdsAreStableAcrossRuns() {
        val a = Ai.render(doc(), budget(8000u))
        assertEquals(a, Ai.render(doc(), budget(8000u)))
        assertEquals(Ai.IDS.map { "#$it" }, a.lines().filter { it.startsWith('#') }, "every section carries its stable id, in order")
    }

    @Test fun tablesAreToonAndNumbersKeepUnits() {
        val out = Ai.render(doc(), budget(8000u))
        assertFalse("| ---" in out, "no Markdown pipe tables")
        assertContains(out, "[1]{plugin,rows,est_before,est_after,saved}:")
        assertContains(out, "total_saved=15tok rows=1")
        assertContains(out, "units:")
    }

    @Test fun doctorAgentsAreOneLinePerHostVariant() {
        val modules =
            listOf(ModuleRow("hooks", ModuleState.NotSupported, ": no hook events"), ModuleRow("mcp", ModuleState.Installed, ""), ModuleRow("proxy", ModuleState.NotInstalled, " (--proxy)"))
        val d = doc().let { it.copy(doctor = it.doctor.copy(agents = listOf(AgentModules("claude", "desktop", modules)))) }
        val out = Ai.render(d, budget(8000u))
        assertContains(out, "\n  claude (desktop) hooks=n/a mcp=yes proxy=no\n")
        assertFalse("no hook events" in out, "reasons stay in rtok doctor")
        assertContains(d.doctor.toText(), "no hook events", message = "full text unchanged")
    }

    @Test fun nonFiniteRateNeverLeaksIntoTheDocument() {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val d = doc().let { it.copy(ledgers = it.ledgers.copy(expand = it.ledgers.expand.copy(rate = rate))) }
            val out = Ai.render(d, budget(8000u))
            assertFalse("NaN" in out, out)
            assertFalse("inf" in out.lowercase(), out)
        }
    }

    @Test fun wholeOutputStaysWithinTheBudget() {
        val coarse = budget(0u).let { it.copy(estimator = it.estimator.copy(prose = 1.0f)) }
        for (d in listOf(doc(), withDb("/very/long/store/path/".repeat(30)), withDb("/хранилище/数据库/".repeat(10)))) {
            for (rates in listOf(Config(), coarse)) {
                for (b in 0 until 1500 step 7) {
                    val cfg = budget(b.toUInt(), rates)
                    val out = Ai.render(d, cfg)
                    assertTrue(tokensOf(out, cfg) <= b.toUInt(), "$b: $out")
                    if (out.isEmpty()) continue
                    val line = out.lines().firstNotNullOf { it.removePrefix("dropped: ").takeIf { r -> r != it } }
                    val named = if (line == "none") emptyList() else line.split(',')
                    for (id in Ai.IDS) assertEquals(id !in named, "#$id\n" in out, "$id at $b: $out")
                }
            }
        }
    }

    @Test fun budgetBoundaryIsExact() {
        val cfg = budget(8000u)
        val full = Ai.render(doc(), cfg)
        val size = tokensOf(full, cfg)
        assertEquals(full, Ai.render(doc(), budget(size)))
        assertFalse("dropped: none" in Ai.render(doc(), budget(size - 1u)))
    }

    @Test fun aBudgetBelowTheHeaderDegradesExplicitly() {
        val d = withDb("/p".repeat(400))
        val minimal = "rtok-report ai\ndropped: window,savings,calls,cache,expand,config,doctor,recommendations\n"
        assertEquals(minimal, Ai.render(d, budget(tokensOf(minimal, Config()))))
        assertEquals("", Ai.render(d, budget(0u)))
    }

    @Test fun aFullArchiveIdPrintsEightHexChars() {
        val id = "ab".repeat(32)
        val d = ReportFixtures.oneRow()
        val out = Ai.render(d.copy(ledgers = d.ledgers.copy(expand = d.ledgers.expand.copy(expandedIds = listOf(id)))), Config())
        assertContains(out, "expanded_ids=${rtok.shortId(id)}")
        assertFalse(id in out)
    }
}
