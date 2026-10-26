package rtok.store

import kotlin.test.*

/** Port of otel.rs tests. */
class OtelTest {
    @Test fun marksStartAtZeroAndUpsert() {
        val s = Store.openInMemory()
        assertEquals(0, s.otelMark("calls"))
        s.otelAdvance("calls", 5)
        s.otelAdvance("calls", 7)
        assertEquals(7, s.otelMark("calls"))
        assertEquals(0, s.otelMark("logs"))
    }

    @Test fun rowsAfterAMarkAscendAndHonourTheLimit() {
        val s = Store.openInMemory()
        s.upsertSession("s1", null, null, null, null)
        repeat(3) { s.insertCall("s1", "hook", "hook", null, null, null, null, "PostToolUse") }
        assertEquals(listOf(2, 3), s.callsAfter(1, 10).map { it.id })
        assertEquals(1, s.callsAfter(0, 1).size)
        assertTrue(s.callsAfter(3, 10).isEmpty())
        s.insertLog("info", "otel", "flush", "m", "s1", 1, null)
        assertEquals(1, s.logsAfter(0, 10).size)
        assertTrue(s.logsAfter(1, 10).isEmpty())
        assertEquals(3L to 1L, s.otelPending())
        assertEquals("flush", s.lastLog("otel")!!.name)
    }

    @Test fun endedSessionsIncludeTheTie() {
        val s = Store.openInMemory()
        s.upsertSession("s1", null, "p", "/w", "startup")
        assertTrue(s.sessionsEndedAfter(0).isEmpty())
        s.endSession("s1", 1_700_000_000)
        val rows = s.sessionsEndedAfter(1_700_000_000)
        assertEquals(1, rows.size)
        assertEquals(1_700_000_000L, rows[0].endedAt)
        assertTrue(s.sessionsEndedAfter(1_700_000_001).isEmpty())
    }

    private fun ids(rows: List<Pair<Long, Session>>) = rows.map { it.second.id }

    @Test fun sessionsPendingExportFollowsTheOrderSessionsEndedIn() {
        val s = Store.openInMemory()
        for (id in listOf("z-first", "a-late", "open")) s.upsertSession(id, null, null, null, null)
        s.endSession("z-first", 1_700_000_000)
        val first = s.sessionsPendingExport(0, 1000)
        assertEquals(listOf("z-first"), ids(first))
        s.otelAdvance("sessions_seq", first[0].first)
        s.endSession("a-late", 1_700_000_000)
        val rest = s.sessionsPendingExport(s.otelMark("sessions_seq"), 1000)
        assertEquals(listOf("a-late"), ids(rest))
        assertTrue(rest[0].first > first[0].first)
    }

    @Test fun sessionsPendingExportIsBatchedAndResumesMidSecond() {
        val s = Store.openInMemory()
        for (id in listOf("s3", "s1", "s2")) {
            s.upsertSession(id, null, null, null, null)
            s.endSession(id, 1_700_000_000)
        }
        val first = s.sessionsPendingExport(0, 2)
        assertEquals(listOf("s3", "s1"), ids(first))
        assertEquals(listOf("s2"), ids(s.sessionsPendingExport(first[1].first, 2)))
    }

    @Test fun endingASessionAtTheSameSecondAgainQueuesNothingNew() {
        val s = Store.openInMemory()
        s.upsertSession("s1", null, null, null, null)
        s.endSession("s1", 1_700_000_000)
        val seq = s.sessionsPendingExport(0, 10)[0].first
        s.endSession("s1", 1_700_000_000)
        assertEquals(0, s.sessionsPendingExport(seq, 10).size)
        s.endSession("s1", 1_700_000_005)
        assertEquals(1, s.sessionsPendingExport(seq, 10).size)
    }

    @Test fun callDetailJoinsIoUsageTokensMeasurementsAndSlugs() {
        val s = Store.openInMemory()
        s.upsertSession("s1", null, null, null, null)
        val (pid, mid) = s.upsertModel("anthropic", "claude-x")
        val id = s.insertCall("s1", "proxy", "api_request", null, pid, mid, null, "/v1/messages")
        s.insertCallIo(id, """{"a":1}""".encodeToByteArray(), """{"b":2}""".encodeToByteArray(), 1024, null)
        s.insertUsage("s1", "claude-x", "anthropic", 100, 10, 20, 30, id)
        s.insertTokens(id, "proxy", "before", "est", 3)
        s.insertMeasurement("s1", meas("proxy", "raw", 10u, 5u, 3u, 1u, callId = id))
        val d = s.callDetail(s.callsAfter(0, 1)[0])
        assertEquals("""{"a":1}""", d.io!!.requestJson)
        val u = d.usage!!
        assertEquals(listOf(100L, 10L, 20L, 30L), listOf(u.input, u.cacheCreate, u.cacheRead, u.output))
        assertEquals(3, d.tokens[0].nTokens)
        assertEquals(2, d.measurements[0].estBefore - d.measurements[0].estAfter)
        assertEquals("anthropic", d.provider)
        assertEquals("claude-x", d.model)
        assertNull(d.host)
    }

    @Test fun tokenTotalsCountTheUpstreamRequestNotTheCacheHitServedAfterIt() {
        val s = Store.openInMemory()
        s.upsertSession("s1", null, null, null, null)
        val live = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
        val hit = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
        for (id in listOf(live, hit)) s.insertUsage("s1", "claude-x", "anthropic", 100, 0, 0, 25, id)
        s.insertMeasurement("s1", meas("proxy", SEMANTIC_CACHE_HIT_KIND, 40u, 40u, 10u, 10u, callId = hit))
        val totals = s.otelTokenTotals()
        assertEquals(1, totals.size)
        assertEquals(100L to 25L, totals[0].input to totals[0].output)
        val usage = s.callDetail(s.callsAfter(live.toLong(), 1)[0]).usage!!
        assertEquals(100L to 25L, usage.input to usage.output)
    }
}
