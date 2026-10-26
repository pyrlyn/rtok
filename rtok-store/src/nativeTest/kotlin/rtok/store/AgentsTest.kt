@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package rtok.store

import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Port of agents.rs tests. */
class AgentsTest {
    private val s = Store.openInMemory()
    private val claude = s.hostId("claude")!!
    private fun reg(session: String, key: String? = null, cwd: String? = null, activity: String? = null) = s.registerAgent(claude, session, key, cwd, activity)
    private fun row(id: String) = s.agentRow(id)!!
    private fun rawAgent(id: String, session: String, key: String) =
        s.conn { it.execute("INSERT INTO agents (id, host_id, host_session_id, parent_key) VALUES (?, ?, ?, ?)", id, claude, session, key) }
    private fun setParent(id: String, parent: String?) = s.conn { it.execute("UPDATE agents SET parent_id = ? WHERE id = ?", parent, id) }

    @Test fun registerIsIdempotentAndReturnsTheSameId() {
        val a = reg("sess-1", cwd = "/repo", activity = "Bash: ls"); val b = reg("sess-1", cwd = "/repo")
        assertEquals(a, b); val r = row(a)
        assertEquals("sess-1", r.hostSessionId); assertEquals("", r.parentKey); assertNull(r.parentId); assertEquals("Bash: ls", r.activity)
    }

    @Test fun sessionHasSubagentIsTrueOnlyOnceAChildRowExists() {
        reg("sess-3"); assertFalse(s.sessionHasSubagent("sess-3")); reg("sess-3", "child")
        assertTrue(s.sessionHasSubagent("sess-3")); assertFalse(s.sessionHasSubagent("sess-4"))
    }

    @Test fun aSubAgentGetsItsOwnRowWithTheParentsId() {
        val parent = reg("sess-2"); val child = reg("sess-2", "child-1"); assertNotEquals(parent, child)
        assertEquals("child-1", row(child).parentKey); assertEquals(parent, row(child).parentId)
        assertNotEquals(child, reg("sess-2", "child-2")); assertEquals(child, reg("sess-2", "child-1"))
    }

    @Test fun touchSetsActivityAndEndStampsEndedAt() {
        val id = reg("sess-3"); s.touchAgent(id, "Edit: src/a.rs")
        assertEquals("Edit: src/a.rs", row(id).activity); assertNull(row(id).endedAt)
        s.endAgent(id, 1_800_000_000); assertEquals(1_800_000_000L, row(id).endedAt)
    }

    @Test fun agentDetailJoinsTheHostSlugAndShortId() {
        val id = reg("sess-detail", cwd = "/repo", activity = "Bash: ls"); val d = s.agentDetail(id)!!
        assertEquals(id, d.id); assertEquals(id.take(8), d.short); assertEquals("claude", d.host); assertEquals("sess-detail", d.hostSessionId)
        assertEquals("/repo", d.cwd); assertEquals("Bash: ls", d.activity); assertNull(d.endedAt)
        assertNull(s.agentDetail("ffffffff-0000-7000-8000-000000000000"))
    }

    @Test fun resolvePrefixExactAmbiguousAndUnknown() {
        val a = reg("sess-a"); val shared = "${a.take(8)}-0000-4000-8000-000000000000"; rawAgent(shared, "sess-b", "")
        assertEquals(a, s.resolveAgent(a))
        val amb = assertFailsWith<StoreException> { s.resolveAgent(a.take(8)) }.message!!
        assertTrue(amb.startsWith("ambiguous: "), amb); assertTrue(a in amb && shared in amb)
        assertEquals("unknown", assertFailsWith<StoreException> { s.resolveAgent("ffffffff") }.message)
        assertTrue("at least 4" in assertFailsWith<StoreException> { s.resolveAgent(a.take(3)) }.message!!)
    }

    @Test fun liveExcludesEndedAndStaleRows() {
        val live = reg("sess-live"); val ended = reg("sess-ended"); s.endAgent(ended, 0); val stale = reg("sess-stale")
        s.conn { it.execute("UPDATE agents SET last_seen = unixepoch() - 10000 WHERE id = ?", stale) }
        val ids = s.liveAgents("60s").map { it.id }
        assertTrue(live in ids); assertFalse(ended in ids, "ended rows are never live"); assertFalse(stale in ids, "a last_seen older than idle is never live")
    }

    @Test fun ancestorsAreStoredInOrderAndAnEmptyChainKeepsWhatIsThere() {
        val id = reg("sess-anc"); assertTrue(row(id).ancestors.isEmpty())
        s.setAgentAncestors(id, listOf(300, 200, 100)); s.setAgentAncestors(id, listOf(300, 200, 100)); s.setAgentAncestors(id, emptyList())
        assertEquals(listOf(300, 200, 100), row(id).ancestors); s.setAgentAncestors(id, listOf(400, 200)); assertEquals(listOf(400, 200), row(id).ancestors)
    }

    @Test fun parseIdleFromAcceptsTheAgentsIdleGrammar() {
        for ((t, secs) in listOf("30m" to 1800, "0s" to 0, "24h" to 86400, "7d" to 7 * 86400, "1m" to 60, "1s" to 1, "15m" to 900, "10m" to 600))
            assertEquals(secs.seconds, parseIdleFrom(t, "--idle"), t)
        assertEquals(Duration.ZERO, parseIdleFrom("0s", "--stale-lock"))
    }

    @Test fun parseIdleFromNamesThePassedFlag() {
        for (flag in listOf("--idle", "--stale-lock", "[agents] idle")) {
            val err = assertFailsWith<StoreException> { parseIdleFrom("5x", flag) }.message!!
            assertTrue(flag in err, err); assertFalse("--since" in err, err)
        }
    }

    @Test fun liveAgentsParsesTheConfiguredIdleWindow() {
        val id = reg("sess-idle"); assertTrue(id in s.liveAgents("30m").map { it.id })
        assertFailsWith<StoreException> { s.liveAgents("not-a-duration") }
    }

    @Test fun registeringAnEndedAgentAgainMakesItLive() {
        val id = reg("sess-resume"); s.endAgent(id, 1_800_000_000); assertTrue(s.liveAgents("30m").none { it.id == id })
        assertEquals(id, reg("sess-resume")); assertTrue(s.liveAgents("30m").any { it.id == id })
    }

    @Test fun aSubAgentRegisteredBeforeItsMainIsAttachedToIt() {
        val child = reg("late-main", "sub-1", activity = "Bash: ls"); val main = reg("late-main", cwd = "/r"); val again = reg("late-main", "sub-1")
        assertEquals(main, row(child).parentId, "the main row's arrival adopted it"); assertEquals(child, again)
        assertEquals(main, s.mainAgent(claude, "late-main")); assertEquals("Bash: ls", row(child).activity, "activity kept")
        assertEquals(listOf(child), s.agentChildren(main).map { it.id })
        val rows = s.agentsOfSessions(listOf("late-main")); assertEquals(2, rows.size); assertEquals(1, rows.count { it.parentId == null })
        assertNotEquals(main, row(reg("elsewhere", "sub-1")).parentId)
    }

    @Test fun existingOrphanSubAgentsAreRepairedInPlace() {
        fun orphan(session: String, key: String) = Uuid.random().toString().also { rawAgent(it, session, key) }
        val a = orphan("legacy", "sub-a"); val b = orphan("legacy", "sub-b"); val main = reg("legacy")
        for (id in listOf(a, b)) assertEquals(main, row(id).parentId, id)
        val c = orphan("legacy-2", "sub-c"); val main2 = reg("legacy-2"); setParent(c, null)
        assertEquals(c, reg("legacy-2", "sub-c")); assertEquals(main2, row(c).parentId)
        setParent(c, a); reg("legacy-2", "sub-c"); reg("legacy-2"); assertEquals(a, row(c).parentId)
    }
}
