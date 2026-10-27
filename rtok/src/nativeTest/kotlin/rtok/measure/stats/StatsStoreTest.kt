// Port of the store-backed tests of src/measure/stats.rs: `--price` USD, Batch/Flex rows, lanes, checkpoint notes,
// unmarked `cmd` bytes and the `api` table.
@file:Suppress("MagicNumber")

package rtok.measure.stats

import rtok.config.Config
import rtok.config.ModelPrice
import rtok.measure.rowCost
import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.callIdsOfKind
import rtok.store.insertCall
import rtok.store.insertMeasurement
import rtok.store.insertNote
import rtok.store.insertProxyTurn
import rtok.store.insertUsage
import rtok.store.setCallServiceTier
import rtok.store.upsertSession
import rtok.support.installDb
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsStoreTest {
    private fun store(): Store {
        installDb()
        return Store.openInMemory().also { it.upsertSession("s1", null, null, null, "proxy") }
    }

    private val prices = Config().stats.prices

    @Test fun priceArithmeticCostsLegsAndCacheSaving() {
        val p = ModelPrice(2.0, 2.5, 0.2, 10.0)
        assertEquals(11.6 to 14.4, rowCost(2_000_000, 400_000, 8_000_000, 500_000, p))
        assertEquals(0.0 to 0.0, rowCost(0, 0, 0, 0, p))
        assertEquals(2.0 to 0.0, rowCost(0, 0, 1_000_000, 0, ModelPrice(1.0, 1.0, 2.0, 1.0)))
    }

    @Test fun attachCostsPricesKnownAndDashesUnknown() {
        val s = store()
        val id = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
        s.insertUsage("s1", "claude-sonnet-5", "anthropic", 2_000_000, 400_000, 8_000_000, 500_000, id)
        s.insertUsage("s1", "mystery-1", "anthropic", 30, 0, 0, 1, id)
        s.insertUsage("s1", null, "anthropic", 7, 0, 0, 0, id)
        val report = Report()
        report.attachCosts(s, prices)
        val cost = report.cost!!
        assertEquals(11.6, cost.totalCost)
        assertEquals(14.4, cost.totalSaved)
        assertEquals(listOf("mystery-1", "unknown"), cost.unknown)
        val m = cost.models["mystery-1"]!!
        assertEquals(30, m.input)
        assertEquals(null to null, m.cost to m.saved)
        assertEquals(11.6, cost.models["claude-sonnet-5"]!!.cost)
        val table = cost.toTable()
        assertTrue("11.60" in table, table)
        assertTrue("mystery-1" in table, table)
    }

    /** T385.12.1: Batch-lane usage is costed at the model's `@batch` row; a model without one stays unpriced. */
    @Test fun batchLaneUsageIsPricedAtItsBatchRow() {
        val s = store()
        for ((kind, model) in listOf("api_request" to "gpt-5", "api_request:batch" to "gpt-5", "api_request:batch" to "mystery-1")) {
            val call = s.insertCall("s1", "proxy", kind, null, null, null, null, "/x")
            s.insertUsage("s1", model, "openai_chat", 1_000_000, 0, 0, 1_000_000, call)
        }
        val report = Report()
        report.attachCosts(s, prices)
        val cost = report.cost!!
        assertEquals(11.25, cost.models["gpt-5"]!!.cost)
        assertEquals(5.625, cost.models["gpt-5@batch"]!!.cost)
        assertEquals(listOf("mystery-1@batch"), cost.unknown)
        assertEquals(16.875, cost.totalCost)
    }

    /** T385.12.2: Flex usage is costed at `@flex` and listed by tier; the ordinary tiers alone add no table. */
    @Test fun flexUsageIsPricedAtItsFlexRowAndListedByTier() {
        val s = store()
        for ((kind, tier) in listOf("api_request" to "default", "api_request:bulk" to "flex", "api_request:bulk" to "default", "api_request:batch" to null)) {
            val call = s.insertCall("s1", "proxy", kind, null, null, null, null, "/x")
            tier?.let { s.setCallServiceTier(call, it) }
            s.insertUsage("s1", "gpt-5", "openai_chat", 1_000_000, 0, 0, 1_000_000, call)
        }
        val report = Report()
        report.attachCosts(s, prices)
        report.attachLanes(s)
        val cost = report.cost!!
        assertEquals(22.5, cost.models["gpt-5"]!!.cost)
        assertEquals(5.625, cost.models["gpt-5@flex"]!!.cost)
        assertEquals(5.625, cost.models["gpt-5@batch"]!!.cost)
        assertEquals(listOf("agent/default", "batch/-", "bulk/default", "bulk/flex"), report.laneTiers.sortedEntries().map { it.key })
        assertEquals(2_000_000, report.lanes["bulk"]!!.input)

        installDb()
        val agentOnly = Store.openInMemory()
        agentOnly.insertProxyTurn("s", 1, 0, 0, 1)
        agentOnly.setCallServiceTier(agentOnly.callIdsOfKind("api_request")[0], "standard")
        val plain = Report()
        plain.attachLanes(agentOnly)
        assertTrue(plain.lanes.isEmpty() && plain.laneTiers.isEmpty())
    }

    @Test fun checkpointNotesSplitSessionsWithAndWithout() {
        val dir = statsDir()
        writeString("$dir/has.jsonl", """{"type":"user","message":{"content":"a"}}""")
        writeString("$dir/miss.jsonl", """{"type":"user","message":{"content":"b"}}""")
        val r = collect(dir, cutoff(), "", defaultReplay())
        assertEquals(Triple(2L, 0L, 2L), Triple(r.sessions, r.checkpoint, r.noCheckpoint))
        val s = store()
        s.insertNote("rtok", "checkpoint:has", "compact", "checkpoint\n")
        r.attachCheckpointNotes(s)
        assertEquals(1L to 1L, r.checkpoint to r.noCheckpoint)
        assertTrue(r.toTable().startsWith("sessions 2  compact 0  checkpoint 1  no_checkpoint 1"), r.toTable())
    }

    /** T177: unmarked `cmd` bytes split into below-gate (`raw`) vs no-rule (`unmatched`); a real shrink counts as neither. */
    @Test fun bashUnmarkedSplitsGateFromUnmatched() {
        val s = store()

        fun meas(
            kind: String,
            before: ULong,
            after: ULong = before,
        ) = Measurement("cmd", kind, before, after, 10u, 10u)
        s.insertMeasurement("s1", meas("raw", 50u))
        s.insertMeasurement("s1", meas("raw", 30u))
        s.insertMeasurement("s1", meas("unmatched", 900u))
        s.insertMeasurement("s1", meas("rule", 1000u, 100u))
        val report = Report()
        report.attachBashCmd(s)
        val d = report.bashUnmarked
        assertEquals(listOf(2L, 80L, 1L, 900L), listOf(d.gateCalls, d.gateBytes, d.unmatchedCalls, d.unmatchedBytes))
        assertTrue("bash unmarked  below_gate calls 2 bytes 80" in report.toTable(), report.toTable())
    }

    @Test fun twoApisPrintAsTwoTableRows() {
        val s = store()
        val id1 = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
        val id2 = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/chat/completions")
        s.insertUsage("s1", "m", "anthropic", 10, 1, 2, 3, id1)
        s.insertUsage("s1", "m", "openai_chat", 20, 0, 5, 4, id2)
        val report = Report()
        report.attachApi(s)
        val table = report.toTable()
        val names = table.lines().filter { it.startsWith("anthropic") || it.startsWith("openai_chat") }
        assertEquals(2, names.size, table)
    }
}
