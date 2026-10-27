// Port of the src/measure/cache.rs tests (T5.5): busts and their causes from the recorded request bodies.
@file:Suppress("MagicNumber")

package rtok.measure.stats

import rtok.store.Store
import rtok.store.UsageRow
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.insertUsage
import rtok.store.upsertSession
import rtok.support.installDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheHealthTest {
    private val a = """{"system":"s","tools":[{"name":"A"}],"messages":[]}"""
    private val aMoreTools = """{"system":"s","tools":[{"name":"A"},{"name":"B"}],"messages":[]}"""
    private val aNewSystem = """{"system":"s2","tools":[{"name":"A"}],"messages":[]}"""

    private fun row(
        callId: Long,
        create: Long,
        read: Long,
        api: String = "anthropic",
    ) = UsageRow("sess", "m", api, 100, create, read, 5, callId)

    /** T5.5 Check: an injected tools-array change → exactly one bust, cause `tools`. */
    @Test fun toolsChangeIsOneBustWithCauseTools() {
        installDb()
        val store = Store.openInMemory()
        for ((body, create, read) in listOf(Triple(a, 30_000L, 0L), Triple(a, 500L, 30_000L), Triple(aMoreTools, 31_000L, 200L), Triple(aMoreTools, 400L, 31_000L))) {
            store.upsertSession("sess", null, null, null, "proxy")
            val id = store.insertCall("sess", "proxy", "api_request", null, null, null, null, "/v1/messages")
            store.insertCallIo(id, body.encodeToByteArray(), null, 1 shl 20, null)
            store.insertUsage("sess", "m", "anthropic", 100, create, read, 5, id)
        }
        val r = CacheHealth.report(store)
        assertEquals(1, r.size)
        assertEquals(1 to 4, r[0].busts to r[0].turns.size)
        assertEquals(listOf(3 to "tools"), r[0].turns.mapIndexedNotNull { i, t -> t.bust?.let { i + 1 to it } })
        assertTrue("bust turn 3 cause=tools cache_create=31000" in CacheHealth.table(r))
    }

    @Test fun systemChangeUnknownAndNoDrop() {
        val rows = listOf(row(1, 0, 0), row(2, 500, 40_000), row(3, 25_000, 1_000))
        val bodies = listOf(a, a, aNewSystem)
        val turns = CacheHealth.analyse(rows) { bodies.getOrNull(it.toInt() - 1)?.encodeToByteArray() }
        assertEquals("system", turns[2].bust)
        assertTrue(turns[0].bust == null && turns[1].bust == null)
        assertEquals("unknown", CacheHealth.analyse(rows) { null }[2].bust)
        val growing = listOf(row(1, 0, 0), row(2, 500, 40_000), row(3, 25_000, 45_000))
        assertTrue(CacheHealth.analyse(growing) { null }.all { it.bust == null })
        assertTrue("no usage rows" in CacheHealth.table(emptyList()))
    }

    @Test fun openaiCacheReadDropIsABustWithoutCreate() {
        val turns = CacheHealth.analyse(listOf(row(1, 0, 100, "openai_chat"), row(1, 0, 10, "openai_chat"))) { null }
        assertNull(turns[0].bust)
        assertNotNull(turns[1].bust)
        assertTrue(CacheHealth.analyse(listOf(row(1, 0, 100), row(2, 0, 10))) { null }.all { it.bust == null })
    }

    /** Port of baseline.rs `save_then_compare_all_deltas_zero`. */
    @Test fun saveThenCompareAllDeltasZero() {
        val home = statsDir()
        rtok.sys.createDirAll("$home/tx")
        rtok.sys.writeString("$home/tx/s.jsonl", "{}\n")
        val report = collect("$home/tx", cutoff(86_400), "", defaultReplay())
        Baseline.save(home, "before-rtok", report)
        val text = Baseline.compare(home, "before-rtok", report)
        assertTrue("Δ0" in text && "Δ1" !in text, text)
        assertTrue(text.lines().none { 'Δ' in it && !it.endsWith("Δ0") }, text)
    }
}
