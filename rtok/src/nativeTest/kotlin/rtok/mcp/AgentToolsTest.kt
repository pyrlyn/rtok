package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.plugin.Runtime
import rtok.store.callIoRequest
import rtok.store.endAgent
import rtok.store.projectByRoot
import rtok.store.recentCalls
import rtok.store.registerAgent
import rtok.store.setAgentAncestors
import rtok.support.diskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GIT_CAP = 65_536L

/** Above any real pid. */
private const val FOREIGN_PID = 10_000_000

@Suppress("TooManyFunctions")
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

    /** A session whose hooks recorded a process tree the test process is not part of, as for one app's shared server. */
    private fun hooked(
        s: Server,
        session: String,
        cwd: String,
    ): String {
        val id = s.cx.store.registerAgent(s.cx.hostId!!, session, null, cwd, null)
        s.cx.store.setAgentAncestors(id, listOf(FOREIGN_PID))
        return id
    }

    private fun rows(json: String) = Json.parseToJsonElement(json).jsonArray.map { it.toString() }

    private fun field(
        json: String,
        key: String,
    ) = Json
        .parseToJsonElement(json)
        .jsonObject[key]!!
        .jsonPrimitive.content

    private fun gitRepo(tag: String): String {
        val dir = rtok.support.tmpDir(tag)
        val (g, _) =
            rtok.sys
                .ProgressRunner("", "git", listOf("-C", dir, "init", "-q"))
                .quiet()
                .runCapped(GIT_CAP)
        assertEquals(0, g.exitCode)
        return dir
    }

    @Test fun twoSessionsInDifferentFoldersSharingOneServerGetNoAnswerForEachOther() =
        server("shared").use { s ->
            hooked(s, "sess-theirs", rtok.sys.cwd())
            val repo = gitRepo("mcp-shared-mine")
            val mine = hooked(s, "sess-mine", repo)

            val (lost, why) = call(s, "whoami")
            assertTrue(lost && "cannot tell which one is calling" in why, why)
            for (tool in listOf("worktree_list", "task_list", "task_ready")) {
                val (err, text) = call(s, tool)
                assertTrue(err && "pass `repo`" in text, "$tool: $text")
            }
            assertTrue(call(s, "worktree_add", """{"task":"T1"}""").first)
            assertTrue(call(s, "agent_inbox").first)

            val (tree, rooted) = call(s, "tree", """{"path":"."}""")
            assertFalse(tree, rooted)
            assertTrue("this server is shared" in rooted, rooted)

            val (named, me) = call(s, "whoami", """{"agent":"$mine"}""")
            assertFalse(named, me)
            assertEquals(mine to "arg", field(me, "id") to field(me, "rule"))

            val (listErr, listed) = call(s, "worktree_list", """{"repo":"$repo"}""")
            assertFalse(listErr, listed)
            assertEquals(listOf(rtok.sys.canonicalize(repo)), rows(listed).map { rtok.sys.canonicalize(field(it, "path")) })
            assertEquals(false to "[]", call(s, "task_list", """{"repo":"$repo"}"""))
            val (made, task) = call(s, "task_create", """{"title":"Mine","repo":"$repo"}""")
            assertFalse(made, task)
            assertEquals(listOf(field(task, "id")), rows(call(s, "task_list", """{"repo":"$repo"}""").second).map { field(it, "id") })

            // Naming the agent acts in its repository; git then has nothing to branch from.
            val (_, added) = call(s, "worktree_add", """{"task":"T1","agent":"$mine"}""")
            assertFalse("not yours" in added || "cannot tell" in added, added)
        }

    private fun stamped(
        caller: String,
        args: String = "",
    ) = """{${if (args.isEmpty()) "" else "$args,"}"${AgentTools.CALLER}":"$caller"}"""

    @Test fun concurrentSessionsOnOneServerGetTheirOwnAgentAndRepository() =
        server("hook-shared").use { s ->
            val here = rtok.sys.cwd()
            hooked(s, "sess-server-cwd", here)
            val repos = listOf(gitRepo("mcp-hook-a"), gitRepo("mcp-hook-b"))
            val ids = repos.mapIndexed { i, repo -> hooked(s, "sess-$i", repo) }
            repos.forEachIndexed { i, repo -> rtok.sys.writeString(rtok.sys.join(repo, "only.kt"), "// session $i\n") }
            try {
                rtok.plugins.read.Roots
                    .setClientRoots(repos)
                for ((i, id) in ids.withIndex()) {
                    val (bad, read) = call(s, "read", stamped(id, """"path":"only.kt""""))
                    assertFalse(bad, read)
                    assertTrue("session $i" in read && "this server is shared" !in read, read)
                }
                repeat(2) {
                    for ((i, id) in ids.withIndex()) {
                        val (err, me) = call(s, "whoami", stamped(id))
                        assertFalse(err, me)
                        assertEquals(id to "hook", field(me, "id") to field(me, "rule"))

                        val (noTree, tree) = call(s, "tree", stamped(id, """"path":".""""))
                        assertFalse(noTree, tree)
                        assertTrue("only.kt" in tree, tree)

                        val (noList, listed) = call(s, "worktree_list", stamped(id))
                        assertFalse(noList, listed)
                        assertEquals(listOf(rtok.sys.canonicalize(repos[i])), rows(listed).map { rtok.sys.canonicalize(field(it, "path")) })

                        assertEquals(false to "[]", call(s, "task_list", stamped(id)))
                    }
                }
                val (made, task) = call(s, "task_create", stamped(ids[0], """"title":"Theirs""""))
                assertFalse(made, task)
                assertEquals(1, rows(call(s, "task_list", stamped(ids[0])).second).size)
                assertEquals(false to "[]", call(s, "task_list", stamped(ids[1])))
            } finally {
                rtok.plugins.read.Roots
                    .reset()
            }
        }

    @Test fun aHookNamedCallScopesTheGraphToItsAgent() =
        server("hook-graph").use { s ->
            val repo = gitRepo("mcp-hook-graph")
            val id = hooked(s, "sess-a", repo)
            hooked(s, "sess-b", rtok.sys.cwd())
            rtok.plugins.read.Roots
                .setClientRoots(listOf(repo))
            try {
                val (err, text) = call(s, "symbol", stamped(id, """"name":"nothing""""))
                assertFalse("no project" in text, "$err: $text")
                assertNotNull(s.cx.store.projectByRoot(rtok.sys.canonicalize(repo)!!), "the call indexes its agent's repository")
                assertNull(s.cx.store.projectByRoot(rtok.sys.cwd()), "not the server's")
            } finally {
                rtok.plugins.read.Roots
                    .reset()
            }
        }

    @Test fun aStableLinkIsNotOverriddenByACallerArgument() {
        val (base, _) = diskConfig("hook-stable")
        val cfg = base.copy(hook = base.hook.copy(host = "claude"))
        val cx = Runtime.open(cfg, "mcp-hook-stable")
        val agents = AgentSession(cx, env = { if (it == "CLAUDE_CODE_SESSION_ID") "sess-mine" else null })
        Server(cx, Server.listedFor(cfg), agents).use { s ->
            val mine = hooked(s, "sess-mine", rtok.sys.cwd())
            val theirs = hooked(s, "sess-theirs", "/elsewhere")
            val (err, me) = call(s, "whoami", stamped(theirs))
            assertFalse(err, me)
            assertEquals(mine to "env", field(me, "id") to field(me, "rule"))
            assertFalse(AgentTools.CALLER in toolListing(s), "a server that knows its session spends no tokens on the argument")
        }
    }

    @Test fun aCallerThatIsNoLiveSessionLeavesTheCallUnnamed() =
        server("hook-stale").use { s ->
            hooked(s, "sess-a", rtok.sys.cwd())
            val ended = hooked(s, "sess-ended", "/elsewhere")
            s.cx.store.endAgent(ended, rtok.log.now())
            val child = s.cx.store.registerAgent(s.cx.hostId!!, "sess-a", "sub-1", "/elsewhere", null)
            for (id in listOf("no-such-agent", ended, child, "")) {
                val (lost, why) = call(s, "whoami", stamped(id))
                assertTrue(lost && "cannot tell which one is calling" in why, "$id: $why")
            }
        }

    @Test fun onlyAServerThatCannotTellItsSessionListsTheCallerArgument() =
        server("hook-list").use { s ->
            hooked(s, "sess-a", "/elsewhere")
            assertEquals(s.listed.size, toolListing(s).split(AgentTools.CALLER).size - 1, "every tool can be the first a session calls")
            val stateless = s.handleLine("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", stateless = true)!!
            assertFalse(AgentTools.CALLER in stateless, stateless)
        }

    @Test fun theCallerArgumentReachesNoTool() =
        server("hook-strip").use { s ->
            val id = hooked(s, "sess-a", rtok.sys.cwd())
            call(s, "ping", stamped(id, """"sentinel":"x""""))
            val logged =
                s.cx.store
                    .callIoRequest(
                        s.cx.store
                            .recentCalls(1)
                            .single()
                            .id,
                    )!!
                    .decodeToString()
            assertTrue("sentinel" in logged && AgentTools.CALLER !in logged, logged)
        }

    private fun toolListing(s: Server) = s.handleLine("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")!!

    @Test fun aOneShotCallActsInItsOwnDirectoryBesideLiveSessions() {
        val (base, _) = diskConfig("one-shot")
        val cfg = base.copy(hook = base.hook.copy(host = "claude"))
        val cx = Runtime.open(cfg, "mcp-one-shot")
        Server(cx, Server.listedFor(cfg), AgentSession(cx, env = { null }, oneShot = true)).use { s ->
            hooked(s, "sess-a", "/elsewhere")
            assertEquals(rtok.sys.cwd(), s.agents.projectDir(JsonObject(emptyMap())))
            assertFalse(s.agents.unsure())
        }
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
        val want =
            listOf("expand", "ping", "whoami", "worktree_add", "worktree_list", "worktree_remove", "worktree_finish", "worktree_adopt") +
                listOf("agent_send", "agent_inbox", "agents_list", "agent_show", "agent_status_set")
        assertEquals(want, names.take(want.size))
    }

    /** mcp.rs `worktree_add_refuses_a_repository_that_is_not_the_callers` (T924). */
    @Test fun worktreeAddRefusesARepositoryThatIsNotTheCallers() =
        server("worktree-wrong-repo").use { s ->
            // The caller works in `theirs`; the server runs in this test's own checkout.
            val theirs = gitRepo("mcp-wt-theirs")
            val id = s.cx.store.registerAgent(s.cx.hostId!!, "sess-a", null, theirs, null)
            val (err, text) = call(s, "worktree_add", """{"task":"T1","agent":"$id"}""")
            assertTrue(err && "not yours" in text && "pass `repo`" in text, text)
            assertTrue(call(s, "worktree_add", """{"task":"T1","agent":"$id","repo":"/"}""").first)
            // The caller's own repository passes the check; git then has nothing to branch from.
            val (_, own) = call(s, "worktree_add", """{"task":"T1","agent":"$id","repo":"$theirs"}""")
            assertFalse("not yours" in own, own)
        }
}
