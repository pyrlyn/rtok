// Port of agents/grok/mod.rs tests: detection (marker, table, Claude import), MCP kept beside a plugin and removed
// only on remove, the import said not duplicated, an unparseable config refused byte for byte, the legacy entry upgraded.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GrokTest {
    private val rtokTable = "[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n"
    private val grok = Grok { "/src/$it" }

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = true,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-grok-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val c = Config()
        val setup =
            c.setup.copy(
                dryRun = dry,
                yes = yes,
                backup = false,
                grok = c.setup.grok.copy(configPath = "$dir/config.toml"),
                claude = c.setup.claude.copy(settingsPath = "$dir/claude-settings.json"),
            )
        return c.copy(setup = setup, doctor = c.doctor.copy(claudeJson = "$dir/claude.json")) to dir
    }

    @Test fun detectionReadsMarkerTableAndTheClaudeImport() {
        val (c, dir) = scratch("detect")
        assertEquals(emptyList(), grok.installed(c, Kind.Cli))
        createDirAll(Grok.pluginMarker(c))
        writeString(c.setup.grok.configPath, rtokTable)
        assertEquals(listOf("plugin", "mcp"), grok.installed(c, Kind.Cli))
        writeString(c.setup.claude.settingsPath, """{"hooks":{"PostToolUse":[{"hooks":[{"command":"rtok hook PostToolUse"}]}]}}""")
        writeString(c.setup.grok.configPath, "[compat.claude]\nhooks = true\nmcps = false\n\n$rtokTable")
        assertEquals(listOf("plugin", "hooks", "mcp"), grok.installed(c, Kind.Cli))
        assertTrue(Agents.agent("grok") is Grok)
        removeDirAll(dir)
    }

    @Test fun aPluginKeepsTheMcpTableAndRemoveTakesItOut() {
        val (c, dir) = scratch("single")
        createDirAll(Grok.pluginMarker(c))
        writeString(c.setup.grok.configPath, "$rtokTable\n[mcp_servers.other]\ncommand = \"x\"\n")
        grok.apply(c, Kind.Cli, Mode.Install)
        assertTrue("[mcp_servers.rtok]" in readToString(c.setup.grok.configPath))
        val lines = grok.apply(c, Kind.Cli, Mode.Remove)
        assertTrue("- mcp_servers.rtok" in lines, "$lines")
        assertTrue(lines[0].startsWith("keep ~/.grok/plugins/rtok"), lines[0])
        val text = readToString(c.setup.grok.configPath)
        assertTrue("[mcp_servers.rtok]" !in text && "[mcp_servers.other]" in text, text)
        removeDirAll(dir)
    }

    @Test fun theClaudeImportIsSaidNotDuplicated() {
        val (c, dir) = scratch("import")
        writeString(c.setup.claude.settingsPath, """{"hooks":{"PostToolUse":[{"hooks":[{"command":"rtok hook PostToolUse"}]}]}}""")
        writeString(c.doctor.claudeJson, """{"mcpServers":{"rtok":{"command":"rtok","args":["mcp"]}}}""")
        val lines = grok.apply(c, Kind.Cli, Mode.Install)
        assertTrue(lines[0].startsWith("offer plugins/grok → grok plugin install /src/plugins/grok --trust ketch install"), lines[0])
        assertTrue("hooks: covered by rtok's Claude hooks" in lines[1] && "mcp: covered by rtok's Claude MCP" in lines[2], "$lines")
        assertEquals(lines, grok.apply(c, Kind.Cli, Mode.Install))
        removeDirAll(dir)
    }

    @Test fun anUnparseableConfigIsRefusedAndLeftByteForByte() {
        val (c, dir) = scratch("broken", yes = false)
        val path = c.setup.grok.configPath
        val broken = "[providers.mine]\nname = \"x\"\nmodels = [\"a\",\n"
        writeString(path, broken)
        assertFails { Grok.registerMcp(c) }
        assertFails { Grok.unregisterMcp(c) }
        assertEquals(broken, readToString(path))
        removeFile(path)
        val first = Grok.registerMcp(c)
        assertTrue(first.startsWith("mcp_servers.rtok: ") && first.endsWith("rtok mcp --host grok"), first)
        writeString(path, "[providers.mine]\nname = \"x\"\n")
        Grok.registerMcp(c)
        val text = readToString(path)
        assertTrue(text.startsWith("[providers.mine]\nname = \"x\"\n") && "[mcp_servers.rtok]" in text, text)
        assertEquals(NO_CHANGES, Grok.registerMcp(c))
        removeDirAll(dir)
    }

    @Test fun anEntryWithoutHostIsUpgradedAndAUserEditIsLeft() {
        val (c, dir) = scratch("legacy", yes = false)
        val path = c.setup.grok.configPath
        writeString(path, "# keep me\n[mcp_servers.other]\ncommand = \"x\"\n\n$rtokTable")
        Grok.registerMcp(c)
        val up = readToString(path)
        assertTrue("\"--host\"" in up && "# keep me" in up && "[mcp_servers.other]\ncommand = \"x\"\n" in up, up)
        val edited = "[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\", \"--extra\"]\n"
        writeString(path, edited)
        assertEquals(NO_CHANGES, Grok.registerMcp(c))
        assertEquals(edited, readToString(path))
        removeDirAll(dir)
    }
}
