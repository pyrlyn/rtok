// Shared fixtures of AiReportTest: the ai.rs test document, an agent row, a budgeted config and the token count.
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object AiFixtures {
    fun doc() =
        Document(
            ReportLedgers(
                window = ReportWindow("30d", 1, 2, "2026-08-10", "2026-09-09", "/tmp/rtok.db", 7u, 7u, 3u, 3u),
                savings = ReportSavingsSection(listOf(ReportSavings("cmd", 1u, 25, 10, 15)), 1u, 15, listOf("filter")),
                sinks = ReportSinksSection(emptyList()),
                calls = ReportCallsSection(listOf(ReportCalls("hook", 2u, 2u, 2.0, 4.0)), 2u, 2u, listOf(ReportHook("PreToolUse", 2u))),
                cache = ReportCache(1u, 3u, 1u, listOf("tools" to 1uL), listOf(ReportBust("s1", 3u, "tools", 31_000, 200))),
                lanes = emptyList(),
                expand = ReportExpand(3, 1, 1.0 / 3.0, listOf("abc"), 5, 1u),
            ),
            listOf(ConfigEntry("report.since", "30d", "user")),
            Report(hooksTotal = 2, hooksByEvent = mapOf("PreToolUse" to 1, "PostToolUse" to 1), proxy = "8787"),
            listOf(Recommendation("expand-rate", "pointers frozen", "3 rows"), Recommendation("cache-bust", "tools rewritten", "1 turn")),
        )

    fun agent(
        host: String,
        state: ModuleState,
    ) = AgentModules(host, "cli", listOf(ModuleRow("hooks", state, "")))

    fun budget(
        n: UInt,
        base: Config = Config(),
    ) = base.copy(report = base.report.copy(budgetTokens = n))

    fun tokensOf(
        out: String,
        cfg: Config,
    ) = rtok.estimate(out, Class.Prose, cfg.estimator).toUInt()

    fun withDb(path: String) = doc().let { d -> d.copy(ledgers = d.ledgers.copy(window = d.ledgers.window.copy(dbPath = path))) }
}
