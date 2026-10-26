// `rtok mcp ping` process side: host names, selection, the server-only and headless checks over fakes, the ping reply
// out of a roundtrip, and the text / JSON output. The live spawn is covered by McpPingE2eSpec.
package rtok.mcp

import kotlinx.serialization.json.Json
import rtok.agents.Agents
import rtok.agents.Kind
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val T = 5.seconds

private fun target(
    host: String,
    kind: Kind,
    display: String,
) = PingTarget(Agents.agent(host)!!, kind, display)

class PingRunTest {
    @Test fun namedHostsSplitsAndRefusesUnknownOrEmpty() {
        assertNull(PingRun.namedHosts(null))
        assertEquals(listOf("claude", "cursor"), PingRun.namedHosts(" claude, cursor ,"))
        assertEquals("unknown host: nope", assertFailsWith<IllegalArgumentException> { PingRun.namedHosts("claude,nope") }.message)
        assertEquals("no host given", assertFailsWith<IllegalArgumentException> { PingRun.namedHosts(" , ") }.message)
    }

    @Test fun selectTakesNamedVariantsAndOnlyInstalledPresentOnesOtherwise() {
        val cfg = Config()
        val named = PingRun.select(cfg, listOf("claude"), cli = true, desktop = false) { _, _ -> false }
        assertEquals(listOf(Kind.Cli), named.map { it.kind })
        assertEquals(2, PingRun.select(cfg, listOf("claude"), cli = false, desktop = false) { _, _ -> false }.size)
        assertTrue(PingRun.select(cfg, null, cli = false, desktop = false) { _, _ -> false }.isEmpty())
    }

    @Test fun serverOnlyReadsTheEntryAndComparesTheReply() {
        val dir = join(tempDir(), "rtok-ping-run-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val path = join(dir, "mcp_config.json")
        val c = Config()
        val cfg = c.copy(setup = c.setup.copy(windsurf = c.setup.windsurf.copy(configPath = path)))
        val t = target("windsurf", Kind.Desktop, "Windsurf")
        val none = PingRun(probe = { _, _, _ -> error("not spawned") }).check(cfg, t, T)
        assertEquals("no rtok MCP entry in the host config; run `rtok agents install windsurf`", none.reason)
        writeString(path, """{"mcpServers":{"rtok":{"command":"/x/rtok","args":["mcp","--host","windsurf"]}}}""")
        var seen: Launch? = null
        val ok =
            PingRun(probe = { l, a, _ ->
                seen = l
                Result.success("MCP $a жив\n")
            }).check(cfg, t, T)
        assertTrue(ok.ok && ok.mode == PingRun.SERVER_ONLY && ok.reply == "MCP Windsurf жив", "$ok")
        assertEquals(Launch("/x/rtok", listOf("mcp", "--host", "windsurf"), emptyList()), seen)
        val wrong = PingRun(probe = { _, _, _ -> Result.success("hi") }).check(cfg, t, T)
        assertEquals("reply was \"hi\", expected \"MCP Windsurf жив\"", wrong.reason)
        removeDirAll(dir)
    }

    @Test fun headlessAsksTheCliAndFallsBackFromCursorAgentToAgent() {
        val asked = mutableListOf<String>()
        val run =
            PingRun(capture = { bin, args, _ ->
                asked += bin
                if (bin == "cursor-agent") throw IllegalStateException("not found") else "MCP Cursor жив\n".also { assertTrue(args[1].contains("agent=\"Cursor\"")) }
            })
        val r = run.check(Config(), target("cursor", Kind.Cli, "Cursor"), T)
        assertTrue(r.ok && r.mode == "chat", "$r")
        assertEquals(listOf("cursor-agent", "agent"), asked)
        val late = PingRun(capture = { _, _, _ -> null }).check(Config(), target("claude", Kind.Cli, "Claude Code"), T)
        assertEquals("timed out after 5s", late.reason)
    }

    @Test fun pingReplyNeedsTheListedToolAndTheCallAnswer() {
        fun v(s: String) = Json.parseToJsonElement(s)
        val list = v("""{"id":2,"result":{"tools":[{"name":"ping"}]}}""")
        val reply = v("""{"id":"3","result":{"content":[{"type":"text","text":" MCP X жив "}]}}""")
        assertEquals("MCP X жив", PingRun.pingReply(listOf(list, reply), T))
        assertEquals("the ping tool is missing", assertFailsWith<PingFailure> { PingRun.pingReply(listOf(reply), T) }.message)
        assertEquals("timed out after 5s", assertFailsWith<PingFailure> { PingRun.pingReply(listOf(list), T) }.message)
        val err = v("""{"id":3,"result":{"isError":true,"content":[{"type":"text","text":"boom"}]}}""")
        assertEquals("boom", assertFailsWith<PingFailure> { PingRun.pingReply(listOf(list, err), T) }.message)
    }

    @Test fun textAndJsonRendering() {
        val chat = PingReport("claude", "cli", "Claude Code", "chat", "MCP Claude Code жив", true, null)
        val server = PingReport("windsurf", "desktop", "Windsurf", PingRun.SERVER_ONLY, "MCP Windsurf жив", true, null)
        val bad = PingReport("zed", "desktop", "Zed", PingRun.SERVER_ONLY, "", false, "boom")
        assertEquals("MCP Claude Code жив\n", PingRun.renderText(listOf(chat)))
        val many = PingRun.renderText(listOf(chat, server, bad))
        assertTrue(many.startsWith("Claude Code: MCP Claude Code жив\nPaste into Windsurf:\n"), many)
        assertTrue("Checked the configured server, not whether Windsurf loaded it: MCP Windsurf жив\n" in many, many)
        assertTrue(many.endsWith("Paste into Zed:\n${Ping.promptFor("Zed")}\n\nZed: boom\n"), many)
        assertEquals(
            """{"agent":"zed","variant":"desktop","mode":"server-only","reply":"","ok":false,"reason":"boom"}""",
            PingRun.rowJson(bad).toString(),
        )
        assertEquals("null", PingRun.rowJson(chat)["reason"].toString())
    }
}
