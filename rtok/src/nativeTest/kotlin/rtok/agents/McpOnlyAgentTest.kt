// Port of the MCP-only host tests (augment, crush, droid, junie, kiro, openhands, roo, warp, windsurf mod.rs) and the shared
// `assert_stdio_mcp_roundtrip` / `assert_mcp_only_apply_idempotent` bodies in agents/mod.rs.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOnlyAgentTest {
    /** A config whose every MCP-only host file lives in one scratch dir. */
    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Pair<Config, String> {
        val dir = "${tempDir()}/rtok-mcp-only-$name-${kotlin.random.Random.nextLong().toULong()}".also(::createDirAll)
        val c = Config()
        val s = c.setup
        return c.copy(
            setup =
                s.copy(
                    dryRun = dry,
                    backup = false,
                    augment = s.augment.copy(settingsPath = "$dir/augment.json"),
                    crush = s.crush.copy(configPath = "$dir/crush.json"),
                    droid = s.droid.copy(mcpPath = "$dir/droid.json"),
                    junie = s.junie.copy(mcpPath = "$dir/junie.json"),
                    kiro = s.kiro.copy(mcpPath = "$dir/kiro.json"),
                    openhands = s.openhands.copy(mcpPath = "$dir/openhands.json"),
                    roo = s.roo.copy(mcpPath = "$dir/roo.json"),
                    warp = s.warp.copy(mcpPath = "$dir/warp.json"),
                    windsurf = s.windsurf.copy(configPath = "$dir/windsurf.json"),
                ),
        ) to dir
    }

    private fun key(a: McpOnlyAgent) = if (a.id == "crush") CRUSH_MCP_KEY else "mcpServers"

    private fun root(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

    @Test fun everyMcpOnlyHostIsInTheRegistryAndMcpOnly() {
        for (a in Agents.MCP_ONLY) {
            val host = Hosts.host(a.id)!!
            for (v in host.variants) {
                assertEquals(Support.Yes, host.support(v.kind, "mcp"), a.id)
                assertTrue(host.support(v.kind, "hooks") is Support.No, a.id)
            }
            assertEquals(a, Agents.agent(a.id))
        }
        assertNull(Agents.agent("cursor"))
    }

    @Test fun theDefaultPathsAreTheHostsUserFiles() {
        val c = Config()
        val want =
            mapOf(
                "augment" to ".augment/settings.json",
                "crush" to ".config/crush/crush.json",
                "droid" to ".factory/mcp.json",
                "junie" to ".junie/mcp/mcp.json",
                "kiro" to ".kiro/settings/mcp.json",
                "openhands" to ".openhands/mcp.json",
                "roo" to "rooveterinaryinc.roo-cline/settings/mcp_settings.json",
                "warp" to ".warp/.mcp.json",
                "windsurf" to ".codeium/windsurf/mcp_config.json",
            )
        for (a in Agents.MCP_ONLY) assertTrue(a.mcpPath(c).endsWith(want.getValue(a.id)), "${a.id}: ${a.mcpPath(c)}")
    }

    @Test fun dryRunNamesTheEntryAndCreatesNothing() {
        val (c, _) = cfg("dry", dry = true)
        for (a in Agents.MCP_ONLY) {
            val out = a.apply(c, Kind.Cli, Mode.Install).single()
            assertTrue(out.startsWith("${key(a)}.rtok: "), out)
            assertTrue(out.contains("mcp --host ${a.id}"), out)
            assertFalse(exists(a.mcpPath(c)), a.id)
            assertTrue(a.installed(c, Kind.Cli).isEmpty(), a.id)
        }
    }

    /** Register is idempotent, the entry is the typeless stdio shape, remove keeps the foreign server. */
    @Test fun mcpEntryIsStdioIdempotentAndRemoveKeepsForeign() {
        val (c, _) = cfg("roundtrip")
        for (a in Agents.MCP_ONLY) {
            val path = a.mcpPath(c)
            val k = key(a)
            writeString(path, """{"$k":{"foreign":{"command":"x"}}}""")
            val first = a.apply(c, Kind.Cli, Mode.Install).single()
            assertTrue(first.startsWith("$k.rtok: ") && first.contains(a.id), first)
            assertEquals(listOf(NO_CHANGES), a.apply(c, Kind.Cli, Mode.Install))
            val entry =
                root(path)
                    .getValue(k)
                    .jsonObject
                    .getValue("rtok")
                    .jsonObject
            assertNull(entry["type"], "stdio shape has no type: $entry")
            assertEquals(JsonPrimitive("mcp"), entry.getValue("args").jsonArray[0])
            assertEquals(JsonPrimitive(a.id), entry.getValue("args").jsonArray[2])
            assertEquals(listOf("mcp"), a.installed(c, Kind.Cli))
            assertEquals(listOf("- $k.rtok"), a.apply(c, Kind.Cli, Mode.Remove))
            assertEquals(listOf(NO_CHANGES), a.apply(c, Kind.Cli, Mode.Remove))
            val after = root(path).getValue(k).jsonObject
            assertNull(after["rtok"])
            assertEquals(JsonPrimitive("x"), after.getValue("foreign").jsonObject["command"])
        }
    }

    /** `assert_mcp_only_apply_idempotent`: from no file, install, install, remove, remove; the empty map goes too. */
    @Test fun applyIsIdempotentViaTheAgentContract() {
        val (c, _) = cfg("apply")
        for (a in Agents.MCP_ONLY) {
            val k = key(a)
            assertTrue(a.apply(c, Kind.Cli, Mode.Install).single().startsWith("$k.rtok: "), a.id)
            assertEquals(listOf(NO_CHANGES), a.apply(c, Kind.Cli, Mode.Install))
            assertEquals(listOf("- $k.rtok"), a.apply(c, Kind.Cli, Mode.Remove))
            assertEquals(listOf(NO_CHANGES), a.apply(c, Kind.Cli, Mode.Remove))
            assertEquals(JsonObject(emptyMap()), root(a.mcpPath(c)), a.id)
        }
    }

    /** An entry the user pointed elsewhere is not rtok's: remove leaves it with a `leave` line. */
    @Test fun removeLeavesAForeignRtokEntry() {
        val (c, _) = cfg("foreign")
        val a = Agents.agent("droid") as McpOnlyAgent
        writeString(a.mcpPath(c), """{"mcpServers":{"rtok":{"command":"other-tool"}}}""")
        val out = a.apply(c, Kind.Cli, Mode.Remove).single()
        assertTrue(out.startsWith("leave mcpServers.rtok in "), out)
        assertTrue("rtok" in root(a.mcpPath(c)).getValue("mcpServers").jsonObject)
    }

    @Test fun setupMcpOffWritesNothing() {
        val (c0, _) = cfg("off")
        val c = c0.copy(setup = c0.setup.copy(mcp = false))
        val a = Agents.MCP_ONLY.first()
        assertEquals(listOf(NO_CHANGES), a.apply(c, Kind.Cli, Mode.Install))
        assertFalse(exists(a.mcpPath(c)))
    }
}
