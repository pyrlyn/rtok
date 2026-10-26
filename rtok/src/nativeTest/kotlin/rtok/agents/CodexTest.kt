// Port of agents/codex/mod.rs tests (MCP and hooks): dry-run, an unreadable config refused, comments and other servers
// kept, the approval key (T528) added once and a user's value kept, the pre-`--host` entry upgraded, and the `codex`
// hook rows written with the old compaction spelling rewritten in place.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.config.Toml
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeBytes
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexTest {
    private val hostArgs = "args = [\"mcp\", \"--host\", \"codex\"]"

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-codex-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, codex = s.codex.copy(configPath = join(dir, "config.toml")))
        return Config().copy(setup = setup) to dir
    }

    private fun rtokTable(path: String) = (Toml.parse(readToString(path)) as JsonObject)["mcp_servers"]!!.jsonObject["rtok"]!!.jsonObject

    @Test fun dryRunShowsOneBlockAndTouchesNothing() {
        val (c, dir) = scratch("dry", dry = true)
        val path = c.setup.codex.configPath
        writeString(path, "model = \"o3\" # keep me\n")
        val out = Codex.runMcp(c, false)
        assertTrue(out.startsWith("+ [mcp_servers.rtok]") && hostArgs in out, out)
        assertEquals(1, out.split("[mcp_servers.rtok]").size - 1)
        assertEquals("model = \"o3\" # keep me\n", readToString(path))
        removeDirAll(dir)
    }

    @Test fun anInvalidConfigIsRefusedNotOverwritten() {
        val (c, dir) = scratch("invalid")
        val path = c.setup.codex.configPath
        val original = "model = \"o3\"\nbroken = [\n".encodeToByteArray()
        writeBytes(path, original)
        assertFails { Codex.runMcp(c, false) }
        assertContentEquals(original, rtok.sys.readBytes(path))
        removeDirAll(dir)
    }

    @Test fun applyKeepsCommentsAndOtherServersAndIsIdempotent() {
        val (c, dir) = scratch("apply")
        val path = c.setup.codex.configPath
        writeString(path, "# codex config\nmodel = \"o3\"\n\n[mcp_servers.other]\ncommand = \"x\"\n")
        assertTrue(Codex.runMcp(c, false).startsWith("+ [mcp_servers.rtok]"))
        assertEquals(NO_CHANGES, Codex.runMcp(c, false))
        val raw = readToString(path)
        assertTrue(raw.startsWith("# codex config\nmodel = \"o3\"\n") && "[mcp_servers.other]\ncommand = \"x\"\n" in raw, raw)
        assertTrue(hostArgs in raw && "\n[mcp_servers]\n" !in raw, raw)
        val t = rtokTable(path)
        assertEquals("writes", t[Codex.APPROVAL_KEY]!!.toString().trim('"'))
        assertTrue(isRtokBin(t["command"]!!.toString().trim('"')), raw)
        assertEquals(listOf("mcp"), Codex().installed(c, Kind.Cli))
        assertEquals("- [mcp_servers.rtok]", Codex.runMcp(c, true))
        assertEquals(NO_CHANGES, Codex.runMcp(c, true))
        assertFalse("rtok" in readToString(path))
        removeDirAll(dir)
    }

    @Test fun anEntryWithoutApprovalGainsItAndStaysOurs() {
        val (c, dir) = scratch("approval")
        val path = c.setup.codex.configPath
        val old = "[mcp_servers.rtok]\ncommand = \"rtok\"\n$hostArgs\n"
        writeString(path, "$old\n[mcp_servers.rtok.tools.read]\napproval_mode = \"approve\"\n")
        val out = Codex.runMcp(c, false)
        assertTrue("+ [mcp_servers.rtok] default_tools_approval_mode = \"writes\"" in out, out)
        assertEquals(NO_CHANGES, Codex.runMcp(c, false))
        val t = rtokTable(path)
        assertEquals("\"writes\"", t[Codex.APPROVAL_KEY].toString())
        assertEquals("\"approve\"", t["tools"]!!.jsonObject["read"]!!.jsonObject["approval_mode"].toString())
        writeString(path, old)
        assertEquals("- [mcp_servers.rtok]", Codex.runMcp(c, true))
        removeDirAll(dir)
    }

    @Test fun aUserApprovalModeIsKept() {
        val (c, dir) = scratch("user")
        val path = c.setup.codex.configPath
        val mine = "[mcp_servers.rtok]\ncommand = \"/opt/rtok\"\n$hostArgs\ndefault_tools_approval_mode = \"prompt\" # mine\n"
        writeString(path, mine)
        assertEquals(NO_CHANGES, Codex.runMcp(c, false))
        assertEquals(mine, readToString(path))
        removeDirAll(dir)
    }

    @Test fun anEntryWithoutHostIsUpgradedAndStillRemovable() {
        val (c, dir) = scratch("legacy")
        val path = c.setup.codex.configPath
        writeString(path, "# keep me\n[mcp_servers.other]\ncommand = \"x\"\n\n[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n")
        Codex.runMcp(c, false)
        val up = readToString(path)
        assertTrue(up.startsWith("# keep me\n[mcp_servers.other]\ncommand = \"x\"\n") && hostArgs in up, up)
        assertEquals(1, up.split("[mcp_servers.rtok]").size - 1, up)
        assertEquals("- [mcp_servers.rtok]", Codex.runMcp(c, true))
        removeDirAll(dir)
    }

    @Test fun hooksJsonCarriesTheCodexRowsAndUpgradesTheOldSpelling() {
        val (c, dir) = scratch("rows")
        val hooks = Codex.hooksPath(c)
        writeString(
            hooks,
            """{"hooks":{"PreCompact":[{"hooks":[{"type":"command","command":"rtok hook PreCompact","timeout":10},""" +
                """{"type":"command","command":"notify-send hi","timeout":5}]}],""" +
                """"PostCompact":[{"hooks":[{"type":"command","command":"rtok hook PostCompact","timeout":10}]}]}}""",
        )
        val report = Codex.runHooks(c, false)
        assertTrue("+ PreToolUse Bash" in report && "+ PostToolUse *" in report && "~ PreCompact" in report, report)
        val v = Json.parseToJsonElement(readToString(hooks)).jsonObject["hooks"]!!.jsonObject
        for ((event, matcher) in HookEvents.entries("codex")) {
            val ours = v[event]!!.jsonArray.flatMap { it.jsonObject["hooks"]!!.jsonArray }.filter { "rtok" in it.toString() }
            assertEquals(1, ours.size, "$event: $v")
            assertTrue("hook $event --host codex" in ours[0].toString(), event)
            assertEquals(matcher, str(v[event]!!.jsonArray[0], "matcher").orEmpty(), event)
        }
        assertEquals(NO_CHANGES, Codex.runHooks(c, false))
        assertTrue("hooks" in Codex().installed(c, Kind.Cli))
        Codex.runHooks(c.copy(setup = c.setup.copy(yes = true)), true)
        val after = Json.parseToJsonElement(readToString(hooks)).jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"hooks":{"PreCompact":[{"hooks":[{"type":"command","command":"notify-send hi","timeout":5}]}]}}"""),
            after,
        )
        assertTrue(Agents.agent("codex") is Codex && after["hooks"] !is JsonArray)
        removeDirAll(dir)
    }
}
