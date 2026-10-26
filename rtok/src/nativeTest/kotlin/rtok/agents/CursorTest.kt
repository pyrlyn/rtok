// Port of agents/cursor/mod.rs tests: the installer writes exactly the table's events (no `beforeSubmitPrompt`), a stale
// rtok line is rewritten in its slot, the retired row is taken back, remove leaves foreign hooks, the linked plugin is
// the only hook path (D21) and `mcp.json` stays independent of it (D33).
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

class CursorTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val cursor: Cursor,
    ) {
        val hooks get() = cfg.setup.cursor.hooksPath
    }

    private fun scratch(
        name: String,
        dry: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-cursor-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/cursor"))
        writeString(join(src, "plugins/cursor/plugin.json"), "{\"name\":\"rtok\"}\n")
        val s = Config().setup
        val setup = s.copy(dryRun = dry, backup = false, cursor = s.cursor.copy(hooksPath = join(dir, "hooks.json")))
        return Scratch(Config().copy(setup = setup), dir, Cursor { rel -> PluginSrc.joinRel(src, rel) })
    }

    private fun root(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

    private fun cmd(e: String) = JsonObject(mapOf("command" to JsonPrimitive(e)))

    @Test fun installerWritesTheTableEventsAndRemoveStripsThem() {
        val s = scratch("table")
        Cursor.run(s.cfg, false)
        val hooks = root(s.hooks)["hooks"]!!.jsonObject
        assertEquals(HookEvents.installed("cursor").map { it.first }.sorted(), hooks.keys.sorted())
        assertNull(hooks["beforeSubmitPrompt"])
        assertEquals(JsonPrimitive(1), root(s.hooks)["version"])
        assertEquals(JsonArray(listOf(cmd(Cursor.hookCmd(HookLine.rtokHookBin(), "SessionEnd")))), hooks["sessionEnd"])
        assertEquals(NO_CHANGES, Cursor.run(s.cfg, false))
        val report = Cursor.run(s.cfg, true)
        assertTrue("- sessionEnd" in report && "- preCompact" in report, report)
        assertFalse("rtok hook" in readToString(s.hooks))
        assertEquals(NO_CHANGES, Cursor.run(s.cfg, true))
        removeDirAll(s.dir)
    }

    @Test fun aStaleRtokHookIsRewrittenInPlace() {
        val stale = "/old/store/rtok/v0.1.0/rtok hook PreToolUse --host cursor"
        val root = JsonObject(mapOf("hooks" to JsonObject(mapOf("beforeShellExecution" to JsonArray(listOf(cmd("audit.sh"), cmd(stale)))))))
        val (out, report) = Cursor.insert(root)
        val want = Cursor.hookCmd(HookLine.rtokHookBin(), "PreToolUse")
        assertTrue("~ beforeShellExecution $want" in report, report)
        assertEquals(JsonArray(listOf(cmd("audit.sh"), cmd(want))), out["hooks"]!!.jsonObject["beforeShellExecution"])
        assertEquals(NO_CHANGES, Cursor.insert(out).second)
    }

    @Test fun theRetiredPromptRowIsTakenBackAndForeignHooksStay() {
        val ours = cmd(Cursor.hookCmd("rtok", "UserPromptSubmit"))
        val both = JsonObject(mapOf("hooks" to JsonObject(mapOf("beforeSubmitPrompt" to JsonArray(listOf(cmd("audit.sh"), ours))))))
        val (out, report) = Cursor.insert(both)
        assertTrue("- beforeSubmitPrompt" in report, report)
        assertEquals(JsonArray(listOf(cmd("audit.sh"))), out["hooks"]!!.jsonObject["beforeSubmitPrompt"])
        val alone = JsonObject(mapOf("hooks" to JsonObject(mapOf("beforeSubmitPrompt" to JsonArray(listOf(ours))))))
        assertNull(Cursor.insert(alone).first["hooks"]!!.jsonObject["beforeSubmitPrompt"])
        val s = scratch("retired")
        writeString(s.hooks, both.toString())
        assertTrue("- beforeSubmitPrompt" in Cursor.run(s.cfg, true))
        assertTrue("audit.sh" in readToString(s.hooks) && "rtok hook" !in readToString(s.hooks))
        removeDirAll(s.dir)
    }

    @Test fun theResolverAndAbsoluteLinesAreOurs() {
        assertTrue(Cursor.isOurs(cmd(Cursor.hookCmd("rtok", "PreCompact"))))
        assertTrue(Cursor.isOurs(cmd("/usr/local/bin/rtok hook PreToolUse --host cursor")))
        assertFalse(Cursor.isOurs(cmd("other hook PreToolUse --host cursor")))
        assertEquals("/opt/rtok hook PreToolUse --host cursor", Cursor.hookCmd("/opt/rtok", "PreToolUse"))
        assertTrue(Cursor.hookCmd("rtok", "PreToolUse").let { it.startsWith("{ command -v rtok") && it.endsWith("; }") })
    }

    @Test fun theLinkedPluginIsTheHookPathAndMcpStaysIndependent() {
        val s = scratch("plugin")
        val mcp = Cursor.mcpPath(s.cfg)
        writeString(mcp, """{"mcpServers":{"other":{"command":"x"}}}""")
        Cursor.run(s.cfg, false)
        val lines = s.cursor.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue(lines.any { it.startsWith("+ plugin") }, "$lines")
        assertFalse("rtok hook" in readToString(s.hooks), readToString(s.hooks))
        assertTrue("\"rtok\"" in readToString(mcp) && "other" in readToString(mcp))
        assertEquals(listOf("hooks", "mcp", "plugin"), s.cursor.installed(s.cfg, Kind.Cli))
        s.cursor.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertFalse(exists(Cursor.PLUGIN.path(s.cfg)))
        assertTrue("\"rtok\"" !in readToString(mcp) && "other" in readToString(mcp))
        assertEquals(emptyList(), s.cursor.installed(s.cfg, Kind.Cli))
        assertTrue(Agents.agent("cursor") is Cursor)
        removeDirAll(s.dir)
    }

    @Test fun dryRunNamesThePluginAndWritesNothing() {
        val s = scratch("dry", dry = true)
        val lines = s.cursor.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue(lines.any { "plugins/cursor" in it && "~/.cursor/plugins/local" in it }, "$lines")
        assertFalse(exists(s.hooks) || exists(Cursor.PLUGIN.path(s.cfg)))
        removeDirAll(s.dir)
    }
}
