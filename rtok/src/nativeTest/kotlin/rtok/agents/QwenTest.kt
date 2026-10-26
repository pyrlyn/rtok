// Port of agents/qwen/mod.rs tests (dry_run_names_hooks_and_mcp_and_writes_nothing,
// apply_is_idempotent_and_remove_keeps_foreign, linked_extension_strips_settings_hooks_and_keeps_mcp), plus an own test
// for the `--yes` extension link through the host CLI. extension_hooks_follow_the_event_table
// reads plugins/qwen from the Rust tree and stays there.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class QwenTest {
    private val calls = mutableListOf<List<String>>()
    private val qwen =
        Qwen({ "/src/$it" }) { _, args ->
            calls += args
            null
        }

    private fun cfg(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Config {
        val dir = join(tempDir(), "rtok-qwen-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val c = Config()
        return c.copy(setup = c.setup.copy(dryRun = dry, yes = yes, backup = false, qwen = c.setup.qwen.copy(dir = dir)))
    }

    private fun root(c: Config) = Json.parseToJsonElement(readToString(Qwen.settingsPath(c))).jsonObject

    private val additions = "${HookEvents.entries("qwen").size} additions"

    @Test fun dryRunNamesHooksAndMcpAndWritesNothing() {
        val c = cfg("dry", dry = true)
        val out = qwen.apply(c, Kind.Cli, Mode.Install).joinToString("\n")
        assertTrue("+ PreToolUse Bash" in out, out)
        assertTrue(additions in out, out)
        assertTrue("mcpServers.rtok: " in out && "mcp --host qwen" in out, out)
        assertFalse(exists(Qwen.settingsPath(c)))
        assertTrue(qwen.installed(c, Kind.Cli).isEmpty())
        removeDirAll(c.setup.qwen.dir)
    }

    @Test fun applyIsIdempotentAndRemoveKeepsForeign() {
        val c = cfg("apply")
        writeString(
            Qwen.settingsPath(c),
            """{"hooks":{"Notification":[{"hooks":[{"type":"command","command":"echo hi"}]}]},""" +
                """"mcpServers":{"other":{"command":"npx","args":["x"]}},"env":{"KEEP":"1"}}""",
        )
        val lines = qwen.apply(c, Kind.Cli, Mode.Install)
        assertTrue(lines.any { additions in it }, "$lines")
        assertTrue(lines.any { it.startsWith("mcpServers.rtok: ") }, "$lines")
        assertTrue(qwen.apply(c, Kind.Cli, Mode.Install).all { it == NO_CHANGES })
        val r = root(c)
        val before = r["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray[0].jsonObject
        assertEquals("Bash", before["matcher"]!!.jsonPrimitive.content)
        val hook = before["hooks"]!!.jsonArray[0].jsonObject
        val cmd = hook["command"]!!.jsonPrimitive.content
        assertTrue("hook PreToolUse" in cmd && "--host" !in cmd, cmd)
        assertEquals(c.setup.hookTimeoutS.toString(), hook["timeout"]!!.jsonPrimitive.content)
        assertTrue(r["hooks"]!!.jsonObject["Notification"] is JsonArray)
        assertEquals(
            "qwen",
            r["mcpServers"]!!
                .jsonObject["rtok"]!!
                .jsonObject["args"]!!
                .jsonArray[2]
                .jsonPrimitive.content,
        )
        assertEquals(listOf("hooks", "mcp"), qwen.installed(c, Kind.Cli))

        qwen.apply(c, Kind.Cli, Mode.Remove)
        val raw = readToString(Qwen.settingsPath(c))
        assertFalse("rtok hook" in raw, raw)
        val after = root(c)
        assertTrue(after["hooks"]!!.jsonObject["Notification"] is JsonArray)
        assertNull(after["mcpServers"]!!.jsonObject["rtok"])
        assertEquals(
            "npx",
            after["mcpServers"]!!
                .jsonObject["other"]!!
                .jsonObject["command"]!!
                .jsonPrimitive.content,
        )
        assertEquals("1", after["env"]!!.jsonObject["KEEP"]!!.jsonPrimitive.content)
        assertTrue(qwen.installed(c, Kind.Cli).isEmpty())
        assertTrue(qwen.apply(c, Kind.Cli, Mode.Remove).all { it == NO_CHANGES })
        assertTrue(calls.isEmpty())
        removeDirAll(c.setup.qwen.dir)
    }

    @Test fun linkedExtensionStripsSettingsHooksAndKeepsMcp() {
        val c = cfg("linked")
        qwen.apply(c, Kind.Cli, Mode.Install)
        val ext = join(Qwen.extensionsDir(c), "rtok").also(::createDirAll)
        writeString(join(ext, "qwen-extension.json"), """{"name":"rtok"}""")
        assertTrue(qwen.pluginInstalled(c))
        qwen.apply(c, Kind.Cli, Mode.Install)
        val r = root(c)
        assertNull(r["hooks"]!!.jsonObject["PreToolUse"], "$r")
        assertEquals(
            "qwen",
            r["mcpServers"]!!
                .jsonObject["rtok"]!!
                .jsonObject["args"]!!
                .jsonArray[2]
                .jsonPrimitive.content,
        )
        assertEquals(listOf("mcp", "plugin"), qwen.installed(c, Kind.Cli))
        removeDirAll(c.setup.qwen.dir)
    }

    @Test fun yesLinksTheExtensionThroughTheCli() {
        val c = cfg("yes", yes = true)
        val lines = qwen.apply(c, Kind.Cli, Mode.Install)
        assertEquals("+ plugin plugins/qwen → rtok", lines.first())
        assertEquals(listOf(listOf("extensions", "link", "/src/plugins/qwen")), calls)
        removeDirAll(c.setup.qwen.dir)
    }
}
