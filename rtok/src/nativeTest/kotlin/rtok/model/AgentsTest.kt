package rtok.model

import rtok.agents.AgentState
import rtok.agents.AgentView
import rtok.store.AgentDetail
import rtok.store.SessionTotals
import rtok.store.Store
import rtok.store.agentDetail
import rtok.store.agentsOfSessions
import rtok.store.claimWorktree
import rtok.store.endAgent
import rtok.store.hostId
import rtok.store.inbox
import rtok.store.registerAgent
import rtok.store.sendMessage
import rtok.store.upsertSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsTest {
    private fun store(): Store {
        rtok.support.installDb()
        return Store.openInMemory()
    }

    private fun row(
        id: String,
        parent: String?,
        session: String,
        seen: Long,
        ended: Long?,
    ) = AgentDetail(id, id.take(8), "claude", session, parent, null, 0, seen, ended, null, null)

    private fun session(
        id: String,
        ended: Long?,
    ) = SessionTotals(id, "claude", null, null, null, "claude-x", 0, 0, 0, 0, 0, 0, ended)

    private fun find(
        views: List<AgentView>,
        id: String,
    ): AgentView? = views.firstNotNullOfOrNull { if (it.detail.id == id) it else find(it.subAgents, id) }

    @Test fun stateFollowsTheIdleRule() {
        assertEquals(AgentState.Live, AgentState.of(null, 990, 1000, 60))
        assertEquals(AgentState.Idle, AgentState.of(null, 900, 1000, 60))
        assertEquals(AgentState.Ended, AgentState.of(1, 1000, 1000, 60))
    }

    @Test fun subAgentsNestUnderTheirSessionsAgent() {
        val agents =
            listOf(
                row("aaaa0001", null, "s1", 1000, null),
                row("bbbb0002", "aaaa0001", "s1", 900, null),
                row("cccc0003", "aaaa0001", "s1", 1000, 5),
                row("dddd0004", null, "s2", 1000, null),
            )
        val sessions = listOf(session("s1", null), session("s2", 7), session("s3", null))
        val live = Agents.sessionViews(sessions, agents, 1000, 60, false)
        assertEquals(2, live.size, "s2 ended: hidden; s3 has no agent row but shows")
        val a = live[0].agent!!
        assertEquals("aaaa0001" to AgentState.Live, a.detail.id to a.state)
        assertEquals("claude-x", a.model)
        assertEquals(listOf("bbbb0002" to AgentState.Idle), a.subAgents.map { it.detail.id to it.state })
        assertNull(live[1].agent)
        val all = Agents.sessionViews(sessions, agents, 1000, 60, true)
        assertEquals(3, all.size)
        assertEquals(2, all[0].agent!!.subAgents.size)
        assertEquals(AgentState.Ended, all[1].state)
    }

    @Test fun aSessionSeenFirstThroughASubAgentKeepsItsMainAgent() =
        store().use { store ->
            val claude = store.hostId("claude")!!
            val sub = store.registerAgent(claude, "s1", "sub", "/repo", "Bash: ls")
            val main = store.registerAgent(claude, "s1", null, "/repo", null)
            val live = Agents.sessionViews(listOf(session("s1", null)), store.agentsOfSessions(listOf("s1")), Long.MAX_VALUE - 1, Long.MAX_VALUE / 2, true)
            assertEquals(main, live[0].agent!!.detail.id)
            assertEquals(listOf(sub), live[0].agent!!.subAgents.map { it.detail.id })
            store.endAgent(sub, 5)
            val hidden = Agents.sessionViews(listOf(session("s1", null)), store.agentsOfSessions(listOf("s1")), 100, 600, false)
            assertEquals(main, hidden[0].agent!!.detail.id, "an ended orphan cannot hide the session")
            assertTrue(hidden[0].agent!!.subAgents.isEmpty())
        }

    @Test fun showFindsByPrefixFillsClaimsAndUnreadAndSetsStatus() =
        store().use { store ->
            val claude = store.hostId("claude")!!
            store.upsertSession("s1", claude, null, null, null)
            val main = store.registerAgent(claude, "s1", null, "/nowhere/x", "Bash: ls")
            val sub = store.registerAgent(claude, "s1", "k", null, null)
            val other = store.registerAgent(claude, "s2", null, "/nowhere/y", null)
            store.claimWorktree("/wt/rtok-t9", main, "t9")
            store.sendMessage(other, main, "hi")
            store.sendMessage(other, sub, "you too")
            store.inbox(main, true, true)
            store.sendMessage(null, main, "new")
            val now = rtok.log.now()
            val v = Agents.agentShow(store, "30m", main.take(8), now)
            assertEquals("Bash: ls", v.detail.activity)
            assertEquals(sub, v.subAgents[0].detail.id)
            assertEquals(Triple(1L, "rtok-t9", listOf("/wt/rtok-t9")), Triple(v.unread, v.worktree, v.worktrees))
            assertEquals(1L, find(listOf(v), sub)!!.unread, "a sub-agent is filled too")
            assertEquals(main, Agents.agentShow(store, "30m", sub, now).detail.parentId)
            assertTrue(assertFailsWith<Exception> { Agents.agentShow(store, "30m", "ffffffff", now) }.message!!.contains("no agent id"))
            assertTrue(assertFailsWith<Exception> { Agents.agentShow(store, "30m", main.take(3), now) }.message!!.contains("at least 4"))
            assertEquals("busy on T284", Agents.setStatus(store, main, " busy\u0007 on T284 "))
            assertEquals("busy on T284", store.agentDetail(main)!!.statusText)
            assertFailsWith<IllegalArgumentException> { Agents.setStatus(store, null, "x") }
            assertEquals(120, Agents.cleanStatus("y".repeat(120))!!.length)
            assertTrue(assertFailsWith<IllegalArgumentException> { Agents.cleanStatus("y".repeat(121)) }.message!!.contains("at most 120"))
            assertNull(Agents.cleanStatus(" \t "), "empty clears")
        }

    @Test fun jsonFlattensTheDetailAheadOfTheViewFields() {
        val v = Agents.nest(listOf(row("aaaa0001", null, "s1", 1000, null)), 1000, 60, true)[0]
        val keys = v.toJson().keys.toList()
        assertEquals(listOf("id", "short", "host"), keys.take(3))
        assertEquals(listOf("model", "worktree", "worktrees", "unread", "state", "sub_agents"), keys.takeLast(6))
    }
}
