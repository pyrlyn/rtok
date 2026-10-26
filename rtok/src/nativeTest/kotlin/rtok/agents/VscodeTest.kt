// Port of agents/vscode/mod.rs tests: `servers.rtok` per profile, the `chat.pluginLocations` member kept in step with
// the linked `plugins/claude`, comments and foreign locations kept, and nothing but plain MCP without `--yes`.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Jsonc
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VscodeTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val vscode: Vscode,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-vscode-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/claude/.claude-plugin"))
        writeString(join(src, "plugins/claude/.claude-plugin/plugin.json"), "{\"name\":\"rtok\"}\n")
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, vscode = s.vscode.copy(codeUserDir = join(dir, "Code/User"), insidersUserDir = join(dir, "Insiders/User")))
        return Scratch(Config().copy(setup = setup), dir, Vscode { rel -> PluginSrc.joinRel(src, rel) })
    }

    private fun locations(path: String) = (Jsonc.parse(readToString(path)) as JsonObject)["chat.pluginLocations"]?.jsonObject

    @Test fun defaultUserDirNamesCodeAndInsiders() {
        assertTrue(Vscode.mcpPath(Config(), false).endsWith("Code/User/mcp.json"))
        assertTrue(Vscode.mcpPath(Config(), true).endsWith("Code - Insiders/User/mcp.json"))
        assertTrue(Agents.agent("vscode") is Vscode)
    }

    @Test fun mcpEntryIsStdioPerProfileAndIdempotent() {
        val s = scratch("mcp")
        val lines = s.vscode.apply(s.cfg, Kind.Desktop, Mode.Install)
        assertEquals(2, lines.count { it.startsWith("servers.rtok: ") }, "$lines")
        assertTrue(lines.filterNot { it.startsWith("servers.rtok: ") }.all { "accept with --yes" in it }, "$lines")
        for (insiders in listOf(false, true)) {
            val e = (Jsonc.parse(readToString(Vscode.mcpPath(s.cfg, insiders))) as JsonObject)["servers"]!!.jsonObject["rtok"]!!.jsonObject
            assertEquals(JsonPrimitive("stdio"), e["type"])
        }
        assertEquals(listOf("mcp"), s.vscode.installed(s.cfg, Kind.Desktop))
        assertTrue(s.vscode.apply(s.cfg, Kind.Desktop, Mode.Install).none { it.startsWith("servers.rtok") })
        removeDirAll(s.dir)
    }

    @Test fun yesLinksThePluginRegistersItsLocationAndRemoveTakesBothBack() {
        val s = scratch("yes", yes = true)
        val settings = Vscode.settingsPath(s.cfg, false)
        createDirAll(join(s.dir, "Code/User"))
        writeString(settings, "{\n  // mine\n  \"chat.pluginLocations\": {\"/other\": true},\n}\n")
        val lines = s.vscode.apply(s.cfg, Kind.Desktop, Mode.Install).joinToString("\n")
        val dest = Vscode.pluginDest(s.cfg, false)
        assertTrue("+ plugin" in lines && "+ chat.pluginLocations: $dest" in lines, lines)
        assertEquals(JsonPrimitive(true), locations(settings)!![dest])
        assertTrue("// mine" in readToString(settings) && "/other" in readToString(settings))
        assertEquals(listOf("mcp", "plugin"), s.vscode.installed(s.cfg, Kind.Desktop))
        assertEquals(listOf(NO_CHANGES), s.vscode.apply(s.cfg, Kind.Desktop, Mode.Install))
        val gone = s.vscode.apply(s.cfg, Kind.Desktop, Mode.Remove).joinToString("\n")
        assertTrue("- chat.pluginLocations: $dest" in gone, gone)
        assertNull(locations(settings)!![dest])
        assertTrue("// mine" in readToString(settings) && "/other" in readToString(settings))
        assertEquals(emptyList(), s.vscode.installed(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }

    @Test fun dryRunAndUndeclaredOffersTouchNothingButPlainMcp() {
        val dry = scratch("dry", dry = true, yes = true)
        dry.vscode.apply(dry.cfg, Kind.Desktop, Mode.Install)
        assertFalse(exists(Vscode.settingsPath(dry.cfg, false)) || exists(Vscode.mcpPath(dry.cfg, false)))
        removeDirAll(dry.dir)
        val plain = scratch("plain")
        plain.vscode.apply(plain.cfg, Kind.Desktop, Mode.Install)
        assertFalse(exists(Vscode.settingsPath(plain.cfg, false)) || exists(Vscode.pluginDest(plain.cfg, false)))
        assertTrue(exists(Vscode.mcpPath(plain.cfg, false)))
        removeDirAll(plain.dir)
    }
}
