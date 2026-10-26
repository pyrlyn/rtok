// Port of agents/gemini/mod.rs tests (dry_run_names_six_events_and_creates_nothing,
// apply_is_idempotent_and_remove_keeps_foreign, stale_rtok_hook_is_rewritten_in_place), plus own tests for the linked
// extension taking the settings hooks back and the `--yes` link with GEMINI_CLI_HOME. support_* lives with [Hosts];
// t275 and hooks_doc read helpers/plugins that stay in the Rust tree.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class GeminiTest {
    private val calls = mutableListOf<List<String>>()
    private val gemini =
        Gemini({ "/src/$it" }) { _, args ->
            calls += args
            null
        }

    private fun cfg(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Config {
        val dir = join(tempDir(), "rtok-gemini-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val c = Config()
        return c.copy(setup = c.setup.copy(dryRun = dry, yes = yes, backup = false, gemini = c.setup.gemini.copy(dir = dir)))
    }

    private fun root(c: Config) = Json.parseToJsonElement(readToString(Gemini.settingsPath(c))).jsonObject

    private fun hooks(c: Config) = root(c)["hooks"]!!.jsonObject

    @Test fun dryRunNamesSixEventsAndCreatesNothing() {
        val c = cfg("dry", dry = true)
        val out = gemini.run(c, false)
        assertTrue(out.startsWith("+ BeforeTool ") && "6 additions" in out, out)
        assertFalse(exists(Gemini.settingsPath(c)))
        assertTrue(gemini.installed(c, Kind.Cli).isEmpty())
        removeDirAll(c.setup.gemini.dir)
    }

    @Test fun applyIsIdempotentAndRemoveKeepsForeign() {
        val c = cfg("apply")
        writeString(
            Gemini.settingsPath(c),
            """{"hooks":{"Notification":[{"hooks":[{"type":"command","command":"echo hi"}]}]},""" +
                """"mcpServers":{"other":{"command":"npx","args":["x"]}}}""",
        )
        val lines = gemini.apply(c, Kind.Cli, Mode.Install)
        assertTrue(lines.any { "6 additions" in it }, "$lines")
        assertTrue(lines.any { it.startsWith("mcpServers.rtok: ") }, "$lines")
        assertTrue(gemini.apply(c, Kind.Cli, Mode.Install).all { it == NO_CHANGES })
        val before = hooks(c)["BeforeTool"]!!.jsonArray[0].jsonObject
        assertNull(before["matcher"], "$before")
        val hook = before["hooks"]!!.jsonArray[0].jsonObject
        assertTrue(hook["command"]!!.jsonPrimitive.content.endsWith("rtok hook PreToolUse --host gemini"), "$hook")
        assertEquals("5000", hook["timeout"]!!.jsonPrimitive.content)
        assertTrue(hooks(c)["Notification"] is JsonArray)
        assertEquals(
            "mcp",
            root(c)["mcpServers"]!!
                .jsonObject["rtok"]!!
                .jsonObject["args"]!!
                .jsonArray[0]
                .jsonPrimitive.content,
        )
        assertEquals(listOf("hooks", "mcp"), gemini.installed(c, Kind.Cli))

        gemini.apply(c, Kind.Cli, Mode.Remove)
        val raw = readToString(Gemini.settingsPath(c))
        assertFalse("rtok hook" in raw, raw)
        assertTrue(hooks(c)["Notification"] is JsonArray)
        assertNull(root(c)["mcpServers"]!!.jsonObject["rtok"])
        assertEquals(
            "npx",
            root(c)["mcpServers"]!!
                .jsonObject["other"]!!
                .jsonObject["command"]!!
                .jsonPrimitive.content,
        )
        assertTrue(gemini.installed(c, Kind.Cli).isEmpty())
        assertTrue(calls.isEmpty())
        removeDirAll(c.setup.gemini.dir)
    }

    @Test fun staleRtokHookIsRewrittenInPlace() {
        val (gevent, cevent) = HookEvents.installed("gemini").first()
        val stale = "/old/store/rtok/v0.1.0/rtok hook $cevent --host gemini"
        val doc =
            Json.parseToJsonElement(
                """{"$gevent":[{"matcher":"run_shell_command","hooks":[{"type":"command","command":"audit.sh"},""" +
                    """{"type":"command","command":"$stale","timeout":1000}]}]}""",
            )
        val spec = Gemini.hookSpec(TIMEOUT_S)
        val (out, report) = HookInsert.insert(Gemini.FORM, doc, spec, "rtok")
        val want = "rtok hook $cevent --host gemini"
        assertTrue(report.startsWith("~ $gevent $want\n") && "1 updates" in report, report)
        val entries = out[gevent]!!.jsonArray
        assertEquals(1, entries.size, "$out")
        val inner = entries[0].jsonObject["hooks"]!!.jsonArray
        assertEquals("audit.sh", inner[0].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(want, inner[1].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("5000", inner[1].jsonObject["timeout"]!!.jsonPrimitive.content)
        assertEquals(NO_CHANGES, HookInsert.insert(Gemini.FORM, out, spec, "rtok").second)
    }

    @Test fun linkedExtensionStripsSettingsHooksAndKeepsMcp() {
        val c = cfg("linked")
        gemini.apply(c, Kind.Cli, Mode.Install)
        val ext = join(Gemini.extensionsDir(c), "rtok").also(::createDirAll)
        writeString(join(ext, "gemini-extension.json"), """{"name":"rtok"}""")
        gemini.apply(c, Kind.Cli, Mode.Install)
        assertTrue((root(c)["hooks"] as? JsonObject)?.get("BeforeTool") == null, "${root(c)}")
        assertEquals(listOf("mcp", "plugin"), gemini.installed(c, Kind.Cli))
        removeDirAll(c.setup.gemini.dir)
    }

    @Test fun yesLinksTheExtensionThroughTheCli() {
        val c = cfg("yes", yes = true)
        val lines = gemini.apply(c, Kind.Cli, Mode.Install)
        assertEquals("+ plugin plugins/gemini → rtok", lines.first())
        assertEquals(listOf(listOf("extensions", "link", "/src/plugins/gemini")), calls)
        removeDirAll(c.setup.gemini.dir)
    }

    /** Rust `insert_ours` scans every entry of a listed event, so a user's `matcher` keeps ours in place (no prune). */
    @Test fun oursUnderAnyMatcherIsKeptInItsSlot() {
        val doc = Json.parseToJsonElement("""{"BeforeTool":[{"matcher":"x","hooks":[{"type":"command","command":"rtok hook PreToolUse --host gemini","timeout":5000}]}]}""")
        val (out, report) = HookInsert.insert(Gemini.FORM, doc, Gemini.hookSpec(TIMEOUT_S), "rtok")
        assertEquals(1, out["BeforeTool"]!!.jsonArray.size, "$out")
        assertEquals(
            "x",
            out["BeforeTool"]!!
                .jsonArray[0]
                .jsonObject["matcher"]!!
                .jsonPrimitive.content,
        )
        assertFalse(report.lines().any { it.startsWith("- ") || it.startsWith("+ BeforeTool") }, report)
        assertTrue(report.endsWith("5 additions"), report)
    }

    /** Rust never prunes: a hook on an event the table no longer lists is not ours for Gemini and survives install. */
    @Test fun installLeavesHooksOnUnlistedEvents() {
        val doc = Json.parseToJsonElement("""{"OldEvent":[{"hooks":[{"type":"command","command":"rtok hook PreToolUse --host gemini","timeout":5000}]}]}""")
        val (out, report) = HookInsert.insert(Gemini.FORM, doc, Gemini.hookSpec(TIMEOUT_S), "rtok")
        assertEquals(doc.jsonObject["OldEvent"], out["OldEvent"])
        assertFalse(report.lines().any { it.startsWith("- ") }, report)
    }

    /** Rust `strip_ours`: only an entry of just `hooks` with the exact hook is unchanged; extra keys mean user-edited. */
    @Test fun removeLeavesEntriesTheUserChanged() {
        val c = cfg("edited")
        val ours = """{"type":"command","command":"rtok hook PreToolUse --host gemini","timeout":5000}"""
        val other = """{"type":"command","command":"rtok hook SessionStart --host gemini","timeout":5000}"""
        writeString(
            Gemini.settingsPath(c),
            """{"hooks":{"BeforeTool":[{"matcher":"","hooks":[$ours]}],"SessionStart":[{"hooks":[$other]}]}}""",
        )
        val out = gemini.run(c, true)
        assertTrue("leave hooks.BeforeTool in " in out && "1 removed" in out, out)
        assertEquals(1, hooks(c)["BeforeTool"]!!.jsonArray.size)
        assertNull(hooks(c)["SessionStart"])
        removeDirAll(c.setup.gemini.dir)
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}
