// Port of agents/commandcode/mod.rs tests (all but commandcode_shell_payload_reaches_pre_tool_use, which belongs to the
// hook dispatcher), plus an own registry check.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandCodeTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val agent: CommandCode,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
        mcp: Boolean = true,
    ): Scratch {
        val dir = join(tempDir(), "rtok-commandcode-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/commandcode"))
        writeString(join(src, "plugins/commandcode/README.md"), "rtok\n")
        val home = join(dir, "home").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, mcp = mcp, commandcode = s.commandcode.copy(dir = home))
        return Scratch(Config().copy(setup = setup), dir, CommandCode { rel -> PluginSrc.joinRel(src, rel) })
    }

    @Test fun dryRunNamesTheSettingsFileAndWritesNothing() {
        val s = scratch("dry", dry = true)
        val out = CommandCode.run(s.cfg, false)
        assertTrue("PreToolUse" in out && "--host commandcode" in out, out)
        assertFalse(exists(CommandCode.settingsPath(s.cfg)))
        assertTrue(s.agent.installed(s.cfg, Kind.Cli).isEmpty())
        assertTrue(Agents.agent("commandcode") is CommandCode)
        removeDirAll(s.dir)
    }

    @Test fun applyWritesFourEventsIsIdempotentAndRemoveStrips() {
        val s = scratch("apply")
        assertTrue("+ PreToolUse" in CommandCode.run(s.cfg, false))
        assertEquals(NO_CHANGES, CommandCode.run(s.cfg, false))
        val doc = json(CommandCode.settingsPath(s.cfg))
        for (event in listOf("PreToolUse", "PostToolUse", "SessionStart", "Stop")) {
            assertTrue(commands(doc, event).any { "--host commandcode" in it }, "$event: $doc")
        }
        val hooks = doc["hooks"]!!.jsonObject
        // Lifecycle events carry no matcher (a matcher there never fires).
        assertNull(hooks["SessionStart"]!!.jsonArray[0].jsonObject["matcher"])
        assertNull(hooks["Stop"]!!.jsonArray[0].jsonObject["matcher"])
        assertEquals(
            "SHELL|READ|WRITE|EDIT",
            hooks["PreToolUse"]!!
                .jsonArray[0]
                .jsonObject["matcher"]!!
                .jsonPrimitive.content,
        )
        assertTrue("hooks" in s.agent.installed(s.cfg, Kind.Cli))
        val gone = CommandCode.run(s.cfg, true)
        assertTrue("- PreToolUse" in gone, gone)
        assertFalse("rtok hook" in readToString(CommandCode.settingsPath(s.cfg)))
        assertEquals(NO_CHANGES, CommandCode.run(s.cfg, true))
        removeDirAll(s.dir)
    }

    @Test fun foreignHooksSurviveInstallAndRemove() {
        val s = scratch("foreign")
        writeString(
            CommandCode.settingsPath(s.cfg),
            """{"hooks":{"PreToolUse":[{"matcher":".*","hooks":[{"type":"command","command":"other-guard"}]}]}}""",
        )
        CommandCode.run(s.cfg, false)
        val pre = commands(json(CommandCode.settingsPath(s.cfg)), "PreToolUse")
        assertTrue("other-guard" in pre && pre.any { "--host commandcode" in it }, "$pre")
        CommandCode.run(s.cfg, true)
        assertEquals(listOf("other-guard"), commands(json(CommandCode.settingsPath(s.cfg)), "PreToolUse"))
        removeDirAll(s.dir)
    }

    @Test fun mcpEntryRegistersAndUnregistersKeepingForeign() {
        val s = scratch("mcp")
        val path = CommandCode.mcpPath(s.cfg)
        writeString(path, """{"mcpServers":{"other":{"command":"x"}}}""")
        val first = StdioMcp.register(s.cfg, path, CommandCode.HOST)
        assertTrue(first.startsWith("mcpServers.rtok: "), first)
        assertEquals(NO_CHANGES, StdioMcp.register(s.cfg, path, CommandCode.HOST))
        val servers = json(path)["mcpServers"]!!.jsonObject
        assertEquals(listOf("mcp", "--host", "commandcode"), servers["rtok"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("x", servers["other"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertTrue("mcp" in s.agent.installed(s.cfg, Kind.Cli))
        assertEquals("- mcpServers.rtok", StdioMcp.unregister(s.cfg, path, CommandCode.HOST))
        assertFalse("rtok" in readToString(path))
        removeDirAll(s.dir)
    }

    @Test fun dryRunOfferNamesPluginAndKetchAndWritesNothing() {
        val s = scratch("offer-dry", dry = true)
        val line = CommandCode.PLUGIN.offer(s.cfg, false, plugins = { rel -> join(join(s.dir, "repo"), rel) })
        assertTrue("plugins/commandcode" in line && "ketch install pyrlyn/rtok" in line, line)
        assertFalse(exists(CommandCode.pluginDest(s.cfg)))
        removeDirAll(s.dir)
    }

    @Test fun linkedPluginInstallAndUpdateKeepTheMcpEntry() {
        val s = scratch("plugin-mcp", yes = true)
        writeString(CommandCode.mcpPath(s.cfg), """{"mcpServers":{"other":{"command":"x"}}}""")
        s.agent.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue("plugin" in s.agent.installed(s.cfg, Kind.Cli))
        assertEquals(listOf("mcp", "--host", "commandcode"), mcpArgs(s.cfg))
        assertTrue("other" in json(CommandCode.mcpPath(s.cfg))["mcpServers"]!!.jsonObject)
        // The update path rewrites a stale entry while the plugin stays linked.
        writeString(CommandCode.mcpPath(s.cfg), """{"mcpServers":{"rtok":{"command":"rtok","args":["mcp"]}}}""")
        s.agent.apply(s.cfg, Kind.Cli, Mode.Update)
        assertTrue("plugin" in s.agent.installed(s.cfg, Kind.Cli))
        assertEquals(listOf("mcp", "--host", "commandcode"), mcpArgs(s.cfg))
        removeDirAll(s.dir)
    }

    @Test fun removeTakesTheEntryOutEvenWithThePluginLinked() {
        val s = scratch("plugin-mcp-remove", yes = true)
        s.agent.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue("mcp" in s.agent.installed(s.cfg, Kind.Cli))
        s.agent.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertTrue(s.agent.installed(s.cfg, Kind.Cli).isEmpty(), "${s.agent.installed(s.cfg, Kind.Cli)}")
        assertFalse("rtok" in readToString(CommandCode.mcpPath(s.cfg)))
        removeDirAll(s.dir)
    }

    @Test fun pluginWithoutAConfigEntryDoesNotReadAsMcp() {
        val s = scratch("plugin-only", yes = true, mcp = false)
        s.agent.apply(s.cfg, Kind.Cli, Mode.Install)
        val have = s.agent.installed(s.cfg, Kind.Cli)
        assertTrue("plugin" in have && "mcp" !in have, "$have")
        assertFalse(exists(CommandCode.mcpPath(s.cfg)))
        removeDirAll(s.dir)
    }

    @Test fun aUserEditedEntryIsLeftByRemove() {
        val s = scratch("edited")
        writeString(CommandCode.mcpPath(s.cfg), """{"mcpServers":{"rtok":{"command":"/opt/mine/server","args":["serve"]}}}""")
        val line = StdioMcp.unregister(s.cfg, CommandCode.mcpPath(s.cfg), CommandCode.HOST)
        assertTrue(line.startsWith("leave "), line)
        assertTrue("/opt/mine/server" in readToString(CommandCode.mcpPath(s.cfg)))
        removeDirAll(s.dir)
    }
}

private fun json(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

private fun commands(
    doc: JsonObject,
    event: String,
) = doc["hooks"]!!.jsonObject[event]!!.jsonArray.flatMap { d ->
    d.jsonObject["hooks"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { it.jsonObject["command"]?.jsonPrimitive?.content }
}

private fun mcpArgs(cfg: Config) =
    json(CommandCode.mcpPath(cfg))["mcpServers"]!!
        .jsonObject["rtok"]!!
        .jsonObject["args"]!!
        .jsonArray
        .map { it.jsonPrimitive.content }
