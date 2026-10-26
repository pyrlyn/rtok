package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.plugin.Runtime
import rtok.store.registerAgent
import rtok.support.diskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolsTest {
    private fun server(name: String): Server {
        val (base, _) = diskConfig(name)
        val cfg = base.copy(hook = base.hook.copy(host = "claude"))
        val cx = Runtime.open(cfg, "mcp-$name")
        return Server(cx, Server.listedFor(cfg), AgentSession(cx, env = { null }))
    }

    private fun call(
        s: Server,
        name: String,
        args: String = "{}",
    ): Pair<Boolean, String> {
        s.handleLine("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
        val r = Json.parseToJsonElement(s.handleLine("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")!!).jsonObject["result"]!!.jsonObject
        return r["isError"]!!.jsonPrimitive.boolean to
            r["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content
    }

    private fun register(
        s: Server,
        session: String,
    ) = s.cx.store.registerAgent(s.cx.hostId!!, session, null, rtok.sys.cwd(), null)

    @Test fun whoamiNamesTheAgentAHookRegisteredForThisCwd() =
        server("whoami-cwd").use { s ->
            val id = register(s, "sess-a")
            val (err, text) = call(s, "whoami")
            assertFalse(err, text)
            val v = Json.parseToJsonElement(text).jsonObject
            assertEquals(listOf(id, id.take(8), "claude", "cwd"), listOf("id", "short", "host", "rule").map { v[it]!!.jsonPrimitive.content })
            assertTrue(v["worktrees"]!!.jsonArray.isEmpty())
        }

    @Test fun whoamiRetriesUntilTheHooksHaveRegistered() =
        server("whoami-late").use { s ->
            val (err, text) = call(s, "whoami")
            assertTrue(err && text.startsWith("not linked to an agent session: no live claude agent") && text.contains("pass `agent`"), text)
            register(s, "sess-a")
            assertFalse(call(s, "whoami").first)
        }

    @Test fun anAmbiguousCwdIsResolvedByTheAgentArgument() =
        server("whoami-arg").use { s ->
            val ids = listOf(register(s, "sess-a"), register(s, "sess-b"))
            val (err, text) = call(s, "whoami")
            assertTrue(err && text.startsWith("ambiguous: agents ") && text.contains("pass `agent`"), text)
            val (ok, mine) = call(s, "whoami", """{"agent":"${ids[1].take(8)}"}""")
            assertFalse(ok, mine)
            val v = Json.parseToJsonElement(mine).jsonObject
            assertEquals(ids[1] to "arg", v["id"]!!.jsonPrimitive.content to v["rule"]!!.jsonPrimitive.content)
            val (bad, why) = call(s, "whoami", """{"agent":"zzzz"}""")
            assertTrue(bad && why.startsWith("agent zzzz:"), why)
        }

    @Test fun sendInboxStatusAndListRoundTrip() =
        server("agent-msgs").use { s ->
            val me = register(s, "sess-a")
            val other = s.cx.store.registerAgent(s.cx.hostId!!, "sess-b", null, "/elsewhere", null)
            val (err, sent) = call(s, "agent_send", """{"to":"${other.take(8)}","text":"hello","task":"A12"}""")
            assertFalse(err, sent)
            assertEquals(
                other.take(8),
                Json
                    .parseToJsonElement(sent)
                    .jsonObject["to"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(false to "no messages", call(s, "agent_inbox"))
            assertEquals(false to "status: on it", call(s, "agent_status_set", """{"text":"on it"}"""))
            assertEquals(false to "status cleared", call(s, "agent_status_set", """{"text":" "}"""))
            val (_, shown) = call(s, "agent_show", """{"id":"${other.take(8)}"}""")
            assertEquals(
                1,
                Json
                    .parseToJsonElement(shown)
                    .jsonObject["unread"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertFalse(call(s, "agents_list", """{"all":true}""").first)
            assertTrue(me.isNotEmpty())
        }

    @Test fun aTaskThatIsNotAStringIsRefused() {
        fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
        assertNull(AgentTools.taskArg(obj("{}")))
        assertEquals("A12", AgentTools.taskArg(obj("""{"task":"A12"}""")))
        assertFailsWith<IllegalStateException> { AgentTools.taskArg(obj("""{"task":12}""")) }
        assertFailsWith<IllegalStateException> { AgentTools.taskArg(obj("""{"task":true}""")) }
    }

    @Test fun agentToolsAreListedInRustOrder() {
        val names = Server.listedFor(rtok.config.Config()).map { it.def.name }
        assertEquals(listOf("expand", "ping", "whoami", "agent_send", "agent_inbox", "agents_list", "agent_show", "agent_status_set"), names.take(8))
    }
}
