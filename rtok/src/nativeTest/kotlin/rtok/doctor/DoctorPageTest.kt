@file:Suppress("MagicNumber")

// Port of the src/doctor.rs tests for `page`, the MCP server list, the proxy chain and the `--json` shape.

package rtok.doctor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.json.StrictJson
import rtok.support.diskConfig
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class DoctorPageTest {
    private fun withSettings(
        tag: String,
        settings: String,
        body: (Config, String) -> Unit,
    ) {
        val (base, dir) = diskConfig(tag)
        writeString("$dir/settings.json", settings)
        val cfg =
            base.copy(
                doctor = base.doctor.copy(settingsPath = "$dir/settings.json"),
                setup = base.setup.copy(claude = base.setup.claude.copy(settingsPath = "$dir/settings.json")),
            )
        try {
            body(cfg, dir)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test
    fun mcpServersKeepArgsAndEnv() {
        val c =
            StrictJson.parse(
                """{"mcpServers":{"serena":{"command":"/usr/bin/uvx","args":["--from","serena-agent","serena"],"env":{"A":"1"}},""" +
                    """"bare":{"command":"x"}}}""",
            )
        val s = Page.mcpServers(c, null)
        assertEquals(listOf("bare", "serena"), s.map { it.name })
        assertTrue(s[0].args.isEmpty())
        assertEquals(listOf("--from", "serena-agent", "serena"), s[1].args)
        assertEquals("1", s[1].env["A"])
    }

    @Test
    fun theFirstFileWinsAName() {
        val a = StrictJson.parse("""{"mcpServers":{"x":{"command":"a"}}}""")
        val b = StrictJson.parse("""{"mcpServers":{"x":{"command":"b"},"y":{"command":"c"}}}""")
        assertEquals(listOf("a", "c"), Page.mcpServers(a, b).map { it.cmd })
    }

    @Test
    fun hostportStripsLoopback() {
        assertEquals("8788", Page.hostport("http://127.0.0.1:8788"))
        assertEquals("8787", Page.hostport("http://127.0.0.1:8787/w/claude"))
    }

    @Test
    fun aPortThatContainsASeenPortIsNotALoop() {
        assertFalse(Page.isLoop(listOf("8788"), "http://127.0.0.1:18788"))
        assertTrue(Page.isLoop(listOf("8788"), "http://127.0.0.1:8788/v1"))
    }

    @Test
    fun theProxyChainFollowsUpstreamsAndStopsAtALoopOrFiveHops() {
        val next = mapOf("http://127.0.0.1:8790" to "http://127.0.0.1:8788/v1", "http://127.0.0.1:8788/v1" to "https://api.anthropic.com")
        assertEquals("8790→8788→api.anthropic.com", Page.proxyChain("http://127.0.0.1:8790", 300) { u, _ -> next[u] })
        assertEquals("8790", Page.proxyChain("http://127.0.0.1:8790", 300) { _, _ -> "http://127.0.0.1:8790/x" })
        var n = 0
        assertEquals(5, Page.proxyChain("http://h0", 300) { _, _ -> "http://h${++n}" }.split('→').size)
        assertEquals("", Page.proxyChain("", 300) { _, _ -> null })
    }

    @Test
    fun pageCountsPluginCarriedHooksOnAPluginOnlyHome() {
        val (base, dir) = diskConfig("t173-plugin")
        val install = "$dir/cache/rtok/1.0"
        createDirAll("$install/hooks")
        createDirAll("$dir/plugins")
        writeString("$dir/settings.json", "{}")
        val installed = buildJsonObject { put("version", 2) }.let { """{"version":2,"plugins":{"rtok@rtok":[{"installPath":"$install"}]}}""" }
        writeString("$dir/plugins/installed_plugins.json", installed)
        writeString("$install/hooks/hooks.json", """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command"}]}]}}""")
        val cfg =
            base.copy(
                doctor = base.doctor.copy(settingsPath = "$dir/settings.json"),
                setup = base.setup.copy(claude = base.setup.claude.copy(settingsPath = "$dir/settings.json")),
            )
        val report = Page.page(cfg)
        assertEquals(1, report.hooksTotal, "${report.hooksByEvent}")
        assertEquals(1, report.hooksByEvent["PreToolUse"])
        assertTrue(report.toText().startsWith("hooks 1\n"))
        removeDirAll(dir)
    }

    @Test
    fun pageWarnsNoToolSearchOnTheDefaultEndpoint() =
        withSettings("t173-base", """{"env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"}}""") { cfg, _ ->
            val report = Page.page(cfg)
            assertFalse(report.mcpToolSearchDisabled)
            assertFalse("mcp_tool_search" in report.toText())
        }

    @Test
    fun pageReportsTheOverrideOverACustomBaseUrl() =
        withSettings(
            "t388-override",
            """{"env":{"ANTHROPIC_BASE_URL":"http://127.0.0.1:1","ENABLE_TOOL_SEARCH":"true"}}""",
        ) { cfg, _ ->
            val report = Page.page(cfg)
            assertEquals(ToolSearchState.Enabled, report.mcpToolSearch.state)
            assertFalse(report.mcpToolSearchDisabled)
            val text = report.toText()
            assertTrue("mcp_tool_search enabled (ENABLE_TOOL_SEARCH=true in settings.json env)" in text, text)
            assertTrue("proxy 127.0.0.1:1\n" in text || "proxy 1\n" in text, text)
        }

    @Test
    fun instructionsListFourInjectors() {
        val (base, dir) = diskConfig("t72-instructions")
        writeString("$dir/CLAUDE.md", "user claude md padding for a long enough line xx\n")
        val servers = listOf("lean-ctx", "engram", "ponytail", "claude-mem").joinToString(",") { "\"$it\":{\"command\":\"\"}" }
        writeString("$dir/claude.json", """{"mcpServers":{$servers}}""")
        writeString("$dir/settings.json", "{}")
        val cfg =
            base.copy(doctor = base.doctor.copy(settingsPath = "$dir/settings.json", claudeJson = "$dir/claude.json", instructions = true))
        val s = Page.page(cfg).toText()
        assertTrue("instructions" in s, s)
        assertTrue(s.lines().count { it.startsWith("  ") && "tokens" in it } >= 4, s)
        removeDirAll(dir)
    }

    @Test
    fun readShareWithoutTranscriptsIsNoData() = withSettings("t504-empty", "{}") { cfg, _ -> assertTrue("read-share no data" in Page.page(cfg).toText()) }

    @Test
    fun listsAnthropicAndOpenaiProxyChains() =
        withSettings("t115-doctor", """{"env":{"ANTHROPIC_BASE_URL":"http://127.0.0.1:1"}}""") { cfg0, dir ->
            writeString("$dir/opencode.json", """{"env":{"OPENAI_BASE_URL":"http://127.0.0.1:1/v1"}}""")
            val cfg = cfg0.copy(setup = cfg0.setup.copy(opencode = cfg0.setup.opencode.copy(configPath = "$dir/opencode.json")))
            val s = Page.page(cfg).toText()
            assertTrue(s.lines().any { it.startsWith("proxy ") && !it.startsWith("proxy openai") && it.endsWith("1") }, s)
            assertTrue(s.lines().any { it.startsWith("proxy openai ") && it.endsWith("1") }, s)
        }

    @Test
    fun theJsonShapeSkipsWhatSerdeSkips() {
        val r = Report(problems = listOf(Problem("broken-hook", "claude", "/s.json", "hooks.PreToolUse[0]", detail = "missing")))
        val j = DoctorJson.of(r)
        assertEquals(
            listOf(
                "hooks_total",
                "hooks_by_event",
                "mcp",
                "proxy",
                "proxy_openai",
                "mcp_tool_search_disabled",
                "mcp_tool_search",
                "bash_max_output_length",
                "auto_compact_window",
                "instructions",
                "agents",
                "problems",
            ),
            j.keys.toList(),
        )
        assertEquals("""{"state":"enabled"}""", j["mcp_tool_search"].toString())
        val p = j["problems"].toString()
        assertEquals("""[{"kind":"broken-hook","agent":"claude","source":"/s.json","path":"hooks.PreToolUse[0]","detail":"missing","fixable":false}]""", p)
        assertTrue((DoctorJson.of(Report(skills = SkillsAudit()))["skills"] as JsonObject).keys == setOf("rows", "desc_bytes"))
    }
}
