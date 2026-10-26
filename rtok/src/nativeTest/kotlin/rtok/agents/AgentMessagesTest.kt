// Port of agents/command.rs whoami/send/inbox behaviour and render.rs whoami/show text over an in-memory store.
package rtok.agents

import rtok.model.Agents
import rtok.store.Store
import rtok.store.hostId
import rtok.store.registerAgent
import rtok.store.shortAgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMessagesTest {
    private val cwd = "/tmp/rtok-agent-messages-project"

    private fun store(): Store {
        rtok.support.installDb()
        return Store.openInMemory()
    }

    private fun register(
        s: Store,
        session: String,
        at: String = cwd,
    ) = s.registerAgent(s.hostId("claude")!!, session, null, at, null)

    private fun messages(
        s: Store,
        me: String?,
    ) = AgentMessages(s, { if (it == "RTOK_AGENT_ID") me else null }, { cwd })

    @Test fun whoamiNamesTheCallerAndRefusesOutsideASession() =
        store().use { s ->
            val me = register(s, "host-session-xyz")
            val d = messages(s, me).whoami()
            val text = AgentText.whoami(d)
            assertTrue(text.startsWith("id: $me\nshort: ${shortAgentId(me)}\nhost: claude\ncwd: $cwd\n") && "activity: -\n" in text, text)
            assertFalse(d.hostSessionId in text.removePrefix("id: $me"), "the host session id stays in --json")
            assertEquals("not inside an agent session", assertFailsWith<AgentsFailure> { messages(s, null).whoami() }.message)
        }

    @Test fun sendThenInboxFramesTheMessageAndMarksItRead() =
        store().use { s ->
            val me = register(s, "a")
            val peer = register(s, "b")
            val sent = messages(s, me).send(shortAgentId(peer), "hello\nworld", false, "A12", "30m")
            assertEquals("sent #1 to ${shortAgentId(peer)}\n", sent)
            val peek = messages(s, null).inbox(shortAgentId(peer), true, null, false)
            assertTrue(peek.startsWith("[rtok message #1 from ${shortAgentId(me)} (claude) at ") && "Task: A12\n> hello\n> world\n[end of rtok message #1]\n" in peek, peek)
            assertTrue("\"framed\": \"[rtok message #1" in messages(s, null).inbox(shortAgentId(peer), true, null, true))
            assertTrue(messages(s, peer).inbox(null, true, null, false).startsWith("[rtok message #1"))
            assertEquals("no messages\n", messages(s, peer).inbox(null, true, null, false))
            assertEquals("not inside an agent session; pass an agent id", assertFailsWith<AgentsFailure> { messages(s, null).inbox(null, false, null, false) }.message)
        }

    @Test fun allLiveReachesTheOtherAgentsOfTheProjectOnly() =
        store().use { s ->
            val me = register(s, "a")
            val peer = register(s, "b")
            register(s, "c", "/tmp/rtok-agent-messages-elsewhere")
            assertEquals("sent #1 to ${shortAgentId(peer)}\n", messages(s, me).send(null, "hi", true, null, "30m"))
            val solo = register(s, "solo", "/tmp/rtok-agent-messages-solo")
            val err = assertFailsWith<AgentsFailure> { messages(s, solo).send(null, "x", true, null, "30m") }
            assertEquals("no other live agent in this project", err.message)
        }

    @Test fun sendArgsAndAnUnresolvableCaller() {
        assertEquals("p" to "t", AgentMessages.sendArgs(false, "p", "t"))
        assertEquals(null to "t", AgentMessages.sendArgs(true, "t", null))
        assertEquals(AgentMessages.SEND_USAGE, assertFailsWith<AgentsFailure> { AgentMessages.sendArgs(true, "a", "b") }.message)
        assertEquals(AgentMessages.SEND_USAGE, assertFailsWith<AgentsFailure> { AgentMessages.sendArgs(false, "a", null) }.message)
        store().use { s ->
            assertTrue(assertFailsWith<AgentsFailure> { messages(s, "ffffffff").caller() }.message!!.startsWith("RTOK_AGENT_ID ffffffff: "))
        }
    }

    @Test fun showListsSubAgentsClaimsAndStatus() =
        store().use { s ->
            val me = register(s, "a")
            Agents.setStatus(s, me, "porting")
            val a = Agents.agentShow(s, "30m", shortAgentId(me), rtok.log.now())
            val text = AgentText.show(a, rtok.log.now())
            assertTrue(text.startsWith("id: $me\nhost: claude\nmodel: -\nparent: -\nsub-agents: -\ncwd: $cwd\n"), text)
            assertTrue("claimed: -\nunread: 0\nstate: live\nactivity: -\nstatus: porting\n" in text && " ago)\n" in text, text)
        }
}
