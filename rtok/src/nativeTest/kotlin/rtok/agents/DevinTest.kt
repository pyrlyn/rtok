// Port of agents/devin/mod.rs tests (install_writes_hooks_and_mcp_and_is_idempotent,
// remove_takes_ours_and_foreign_hooks_survive_both, installed_never_reports_plugin), plus own tests for the Windows
// path rule and the plugin doc shape. plugin_manifest_matches_the_installer reads plugins/devin, which stays in the
// Rust tree.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
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

class DevinTest {
    private val devin = Devin { "/src/$it" }

    private fun cfg(name: String): Config {
        val dir = join(tempDir(), "rtok-devin-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val c = Config()
        return c.copy(setup = c.setup.copy(yes = true, backup = false, devin = c.setup.devin.copy(configPath = join(dir, "config.json"))))
    }

    private fun json(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

    private fun cleanup(c: Config) = removeDirAll(rtok.sys.parent(Devin.configPath(c))!!)

    @Test fun installWritesHooksAndMcpAndIsIdempotent() {
        val c = cfg("install")
        val first = devin.apply(c, Kind.Cli, Mode.Install)
        assertTrue(first.any { "plugins/devin" in it && "devin plugins install --local" in it }, "$first")
        val want = Devin.hooksDocWith({ ClaudeHooks.commandFor("devin", "rtok", it) }, c.setup.hookTimeoutS.toLong())
        assertEquals(want, json(Devin.configPath(c))["hooks"])
        val rtok = json(Devin.mcpPath(c))["mcpServers"]!!.jsonObject["rtok"]!!.jsonObject
        assertEquals(rtokMcpCommand(), rtok["command"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "--host", "devin"), rtok["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val second = devin.apply(c, Kind.Cli, Mode.Install)
        assertTrue(second.all { it == NO_CHANGES }, "$second")
        cleanup(c)
    }

    @Test fun removeTakesOursAndForeignHooksSurviveBoth() {
        val c = cfg("foreign")
        writeString(
            Devin.configPath(c),
            """{"version":1,"hooks":{"PreToolUse":[{"matcher":"exec","hooks":[{"type":"command","command":"echo foreign","timeout":30}]}],""" +
                """"Stop":[{"hooks":[{"type":"command","command":"echo stop","timeout":9}]}]}}""",
        )
        writeString(Devin.mcpPath(c), """{"mcpServers":{"other":{"command":"other","args":[]}}}""")
        devin.apply(c, Kind.Cli, Mode.Install)
        devin.apply(c, Kind.Cli, Mode.Remove)
        val text = readToString(Devin.configPath(c))
        assertTrue("echo foreign" in text && "echo stop" in text, text)
        assertFalse("rtok" in text, text)
        assertEquals("1", json(Devin.configPath(c))["version"]!!.jsonPrimitive.content)
        val servers = json(Devin.mcpPath(c))["mcpServers"]!!.jsonObject
        assertNull(servers["rtok"], "$servers")
        assertTrue("other" in servers, "$servers")
        cleanup(c)
    }

    @Test fun installedNeverReportsPlugin() {
        val c = cfg("plugin")
        devin.apply(c, Kind.Cli, Mode.Install)
        assertEquals(listOf("hooks", "mcp"), devin.installed(c, Kind.Cli))
        cleanup(c)
    }

    @Test fun onlyTheXdgDefaultIsRedirectedOnWindows() {
        assertTrue(Devin.isXdgDefault("C:\\Users\\a\\.config\\devin\\config.json"))
        assertFalse(Devin.isXdgDefault("/tmp/x/config.json"))
        val c = cfg("path")
        assertEquals(c.setup.devin.configPath, Devin.configPath(c))
        cleanup(c)
    }

    @Test fun pluginDocUsesTheResolverAndOmitsEmptyMatchers() {
        val doc: JsonObject = Devin.hooksDoc()
        assertEquals(Devin.entries().map { it.first }.distinct(), doc.keys.toList())
        for ((event, groups) in doc) {
            for (g in groups.jsonArray) {
                val hook =
                    g.jsonObject["hooks"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                assertTrue("exec rtok hook $event --host devin;" in hook["command"]!!.jsonPrimitive.content, "$hook")
                assertEquals("5", hook["timeout"]!!.jsonPrimitive.content)
                g.jsonObject["matcher"]?.let { assertTrue(it.jsonPrimitive.content.isNotEmpty()) }
            }
        }
    }
}
