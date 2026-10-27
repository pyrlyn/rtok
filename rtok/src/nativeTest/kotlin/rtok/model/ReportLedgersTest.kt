// Port of src/model/tests.rs `report_pct_is_nearest_rank`, plus the ledger read of src/model/report.rs over a seeded
// store: savings net an `expand` cost, sinks keep only actionable classes, calls split by surface and hook event.
@file:Suppress("MagicNumber")

package rtok.model

import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.insertCall
import rtok.store.insertMeasurement
import rtok.store.setCallMs
import rtok.store.upsertSession
import rtok.support.diskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportLedgersTest {
    @Test fun reportPctIsNearestRank() {
        assertNull(ReportSections.pct(emptyList(), 0.95))
        assertEquals(2.0, ReportSections.pct(listOf(2.0, 4.0), 0.5))
        assertEquals(4.0, ReportSections.pct(listOf(2.0, 4.0), 0.95))
        assertEquals(10.0, ReportSections.pct(listOf(10.0), 0.5))
        assertEquals(10.0, ReportSections.pct(listOf(10.0), 0.95))
    }

    @Test fun ledgersReadEverySectionFromOneStore() {
        val (cfg, _) = diskConfig("report-ledgers")
        Store.open(cfg.core.dbPath).use { s ->
            s.upsertSession("s1", null, null, null, null)
            s.insertMeasurement("s1", Measurement("read", "full", 4000u, 400u, 1000u, 100u, "src/a.rs"))
            s.insertMeasurement("s1", Measurement("cmd", "head_tail", 9000u, 900u, 2000u, 200u, "cargo:1"))
            s.insertMeasurement("s1", Measurement("archive", "tool_result", 8000u, 80u, 1500u, 20u, "abc"))
            s.insertMeasurement("s1", Measurement("archive", "expand", 0u, 80u, 0u, 30u, "abc"))
            val hook = s.insertCall("s1", "hook", "hook", null, null, null, null, "PreToolUse")
            s.setCallMs(hook, 4.0)
            s.insertCall("s1", "hook", "hook", null, null, null, null, null)
            val mcp = s.insertCall("s1", "mcp", "tool", null, null, null, null, "x")
            s.setCallMs(mcp, 10.0)
        }
        val l = ReportLedgers.read(cfg)
        assertEquals("30d", l.window.since)
        assertEquals(3uL, l.window.callsTotal)
        assertEquals(3uL, l.window.callsInWindow)
        assertEquals(4uL, l.window.measurements)
        assertEquals(listOf("archive", "cmd", "read"), l.savings.rows.map { it.plugin })
        assertEquals(1500L - 20 - 30, l.savings.rows[0].saved, "an expand row is a cost netted out")
        assertEquals(4uL, l.savings.totalRows)
        assertEquals(listOf("expand", "full", "head_tail", "tool_result"), l.savings.kinds)
        assertEquals(listOf("cmd" to "cargo", "read" to "src/a.rs"), l.sinks.rows.map { it.cls to it.sink }, "archive is already shortened")
        assertEquals("[plugins.read] default_mode = full", l.sinks.rows[1].switch)
        assertEquals(listOf(2uL, 1uL, 0uL), l.calls.rows.map { it.calls })
        assertEquals(listOf(4.0, 10.0, null), l.calls.rows.map { it.p50Ms })
        assertEquals(listOf(ReportHook("PreToolUse", 1u), ReportHook("unknown", 1u)), l.calls.hooks)
        assertEquals(1uL, l.expand.costRows)
        assertEquals(30, l.expand.cost)
        assertEquals(listOf("abc"), l.expand.expandedIds)
        assertEquals(0.0, l.expand.rate)
        assertEquals(emptyList(), l.laneCells(), "every request on the agent lane leaves the table out")
    }

    @Test fun sinkLabelsNameTheServerToolOrCommandStem() {
        assertEquals("mcp" to "github/search", ReportSections.sinkLabel("cmd", "wrap", "github/search:7"))
        assertEquals("mcp" to "mcp", ReportSections.sinkLabel("cmd", "wrap", null))
        assertEquals("cmd" to "git", ReportSections.sinkLabel("cmd", "rule", "git:status"))
        assertEquals("cmd" to "rule", ReportSections.sinkLabel("cmd", "rule", null))
        assertEquals("read" to "unknown", ReportSections.sinkLabel("read", "full", null))
        assertEquals("rtok mcp -- <server> (wrap)", ReportSections.sinkSwitch(rtok.config.Config(), "mcp", "x"))
    }
}
