// Port of agents/copilot/mod.rs tests, except pre_compact_writes_a_checkpoint_note (hook dispatcher). The T275 entry
// lifecycle (mcp.rs `assert_json_entry_lifecycle`) is spelled out here: that shared helper is not ported yet.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
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
import kotlin.test.assertTrue

class CopilotTest {
    private val copilot = Copilot({ "/src/$it" }) { _, _ -> null }

    @Test fun dryRunNamesTheHookFileAndCreatesNothing() {
        val c = cfg("dry", dry = true)
        val out = Copilot.run(c, false)
        assertTrue(out.startsWith("+ ") && out.endsWith("(7 events)"), out)
        assertFalse(exists(Copilot.hooksPath(c)))
        assertTrue(copilot.installed(c, Kind.Cli).isEmpty())
        assertTrue(Agents.agent("copilot") is Copilot)
        cleanup(c)
    }

    @Test fun applyWritesSevenEventsIsIdempotentAndRemoveDeletes() {
        val c = cfg("apply")
        assertTrue(Copilot.run(c, false).startsWith("+ "))
        assertEquals(NO_CHANGES, Copilot.run(c, false))
        val doc = json(Copilot.hooksPath(c))
        assertEquals("1", doc["version"]!!.jsonPrimitive.content)
        val hooks = doc["hooks"]!!.jsonObject
        assertEquals(HookEvents.installed("copilot").size, hooks.size)
        assertTrue("preCompact" in hooks)
        val pre = hooks["preToolUse"]!!.jsonArray[0].jsonObject
        assertEquals("command", pre["type"]!!.jsonPrimitive.content)
        assertEquals("5", pre["timeoutSec"]!!.jsonPrimitive.content)
        val bash = pre["bash"]!!.jsonPrimitive.content
        assertTrue("exec rtok hook PreToolUse --host copilot" in bash && bash.endsWith("; exit 0"), bash)
        val ps = pre["powershell"]!!.jsonPrimitive.content
        assertTrue("hook PreToolUse --host copilot" in ps && ps.endsWith("; exit 0"), ps)
        val prompt =
            hooks["userPromptSubmitted"]!!
                .jsonArray[0]
                .jsonObject["bash"]!!
                .jsonPrimitive.content
        assertTrue(" hook UserPromptSubmit " in prompt, prompt)
        assertEquals(listOf("hooks"), copilot.installed(c, Kind.Cli))
        assertEquals("- ${Copilot.hooksPath(c)}", Copilot.run(c, true))
        assertFalse(exists(Copilot.hooksPath(c)))
        assertEquals(NO_CHANGES, Copilot.run(c, true))
        cleanup(c)
    }

    /** T116: the offer names the local install and runs nothing without `--yes`; the marker alone answers (T75). */
    @Test fun pluginOffersTheLocalInstallBehindYes() {
        var c = cfg("offer", dry = true)
        assertEquals(NO_CHANGES, copilot.plugin(c, false), "no --yes, no line")
        c = c.copy(setup = c.setup.copy(yes = true))
        val s = copilot.plugin(c, false)
        assertTrue("plugins/copilot" in s && "copilot plugin install" in s && "ketch install pyrlyn/rtok" in s, s)
        assertFalse(Copilot.pluginInstalled(c))
        val marker = fakePlugin(c)
        assertTrue(Copilot.pluginInstalled(c))
        assertEquals(NO_CHANGES, copilot.plugin(c, false))
        assertEquals("- plugin rtok (copilot plugin uninstall rtok)", copilot.plugin(c, true))
        writeString(marker, """{"name":"other"}""")
        assertFalse(Copilot.pluginInstalled(c), "a foreign manifest is not ours")
        cleanup(c)
    }

    /** D21: while the plugin serves, `hooks/rtok.json` is taken back; MCP stays independent (T275/D33). */
    @Test fun thePluginIsTheHooksSingletonButMcpStaysIndependent() {
        val c = cfg("single", yes = true)
        assertTrue(Copilot.run(c, false).startsWith("+ "))
        assertTrue(McpRun.install(c, Copilot.mcpSpec(c)).startsWith("mcpServers.rtok: "))
        fakePlugin(c)
        val lines = copilot.apply(c, Kind.Cli, Mode.Install)
        assertTrue(lines.any { it.startsWith("- ") && "hooks" in it }, "$lines")
        assertFalse(lines.any { "mcpServers.rtok" in it }, "no mcp line while nothing changed: $lines")
        assertFalse(exists(Copilot.hooksPath(c)))
        assertTrue("rtok" in readToString(Copilot.mcpPath(c)), "D33: mcp stays while the plugin serves hooks")
        assertEquals(listOf("mcp", "plugin"), copilot.installed(c, Kind.Cli))
        cleanup(c)
    }

    /** T275/D33: install and update always write the entry, plugin or not; remove takes it; a user edit is left. */
    @Test fun t275McpEntryAlwaysWrittenExceptOnRemove() {
        val c = cfg("t275-mcp")
        fakePlugin(c)
        for (mode in listOf(Mode.Install, Mode.Update)) {
            copilot.apply(c, Kind.Cli, mode)
            assertTrue("mcp" in copilot.installed(c, Kind.Cli), "$mode")
        }
        copilot.apply(c, Kind.Cli, Mode.Remove)
        assertFalse("mcp" in copilot.installed(c, Kind.Cli))
        McpRun.install(c, Copilot.mcpSpec(c))
        writeString(Copilot.mcpPath(c), readToString(Copilot.mcpPath(c)).replace("\"copilot\"", "\"copilot\", \"--extra\""))
        assertTrue(McpRun.remove(c, Copilot.mcpSpec(c)).startsWith("leave "))
        assertTrue("--extra" in readToString(Copilot.mcpPath(c)))
        cleanup(c)
    }

    @Test fun mcpEntryIsLocalWithAllToolsAndTheAppReportsNoHooks() {
        val c = cfg("mcp")
        assertTrue(McpRun.install(c, Copilot.mcpSpec(c)).startsWith("mcpServers.rtok: "))
        assertEquals(NO_CHANGES, McpRun.install(c, Copilot.mcpSpec(c)))
        val rtok = json(Copilot.mcpPath(c))["mcpServers"]!!.jsonObject["rtok"]!!.jsonObject
        assertEquals("local", rtok["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "--host", "copilot"), rtok["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("*"), rtok["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("mcp"), copilot.installed(c, Kind.Desktop))
        assertEquals("- mcpServers.rtok", McpRun.remove(c, Copilot.mcpSpec(c)))
        assertFalse("rtok" in readToString(Copilot.mcpPath(c)))
        cleanup(c)
    }
}

private fun cfg(
    name: String,
    dry: Boolean = false,
    yes: Boolean = false,
): Config {
    val dir = join(tempDir(), "rtok-copilot-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
    val s = Config().setup
    return Config().copy(setup = s.copy(dryRun = dry, yes = yes, backup = false, copilot = s.copilot.copy(dir = dir)))
}

private fun cleanup(c: Config) = removeDirAll(c.setup.copilot.dir)

private fun json(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

/** Copilot's record of an installed rtok plugin: the manifest under `installed-plugins`. */
private fun fakePlugin(c: Config): String {
    val dir = join(c.setup.copilot.dir, "installed-plugins/_direct/x").also(::createDirAll)
    return join(dir, "plugin.json").also { writeString(it, """{"name":"rtok"}""") }
}
