@file:Suppress("MagicNumber")

// Port of the tests of src/doctor/mcp_dupes.rs: an in-memory machine through Probes.

package rtok.doctor

import rtok.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class McpMock(
    val files: MutableMap<String, String> = linkedMapOf(),
    val links: MutableMap<String, String> = linkedMapOf(),
    val path: MutableMap<String, String> = linkedMapOf(),
) : ProbeFs,
    ProbeEnv,
    Which {
    override fun canonical(path: String) = links[path] ?: path

    override fun read(path: String) = files[path] ?: throw ReadError(true, "not found")

    override fun kind(path: String): PathKind = PathKind.Missing

    override fun variable(name: String): String? = null

    override fun home() = "/h"

    override fun cwd() = "/proj"

    override fun find(program: String) = path[program]

    fun user(servers: String) {
        files["/h/.claude.json"] = """{"mcpServers": {$servers}}"""
    }

    fun plugin(
        id: String,
        mcp: String,
    ): List<Pair<String, String>> {
        val dir = "/h/plug/" + id.substringBefore('@')
        files["$dir/.mcp.json"] = mcp
        return listOf(id to dir)
    }
}

private fun cfg(): Config {
    val c = Config()
    return c.copy(
        doctor = c.doctor.copy(claudeJson = "/h/.claude.json"),
        setup =
            c.setup.copy(
                codex = c.setup.codex.copy(configPath = "/h/.codex/config.toml"),
                gemini = c.setup.gemini.copy(dir = "/h/.gemini"),
                cursor = c.setup.cursor.copy(hooksPath = "/h/.cursor/hooks.json"),
            ),
    )
}

private fun run(
    m: McpMock,
    plugins: List<Pair<String, String>> = emptyList(),
) = McpDupes.check(cfg(), Probes(m, m, m), plugins)

private data class Shape(
    val kind: String,
    val name: String,
    val keep: Boolean,
)

private fun shape(found: List<Problem>) = found.map { Shape(it.kind, it.path.substringAfterLast('.'), it.keep) }

private data class Own(
    val kind: String,
    val name: String,
    val keep: Boolean,
    val fixable: Boolean,
)

private fun own(found: List<Problem>) = found.map { Own(it.kind, it.path.substringAfterLast('.'), it.keep, it.fixable) }

private const val OWN = """{"command": "rtok", "args": ["mcp", "--host", "claude"]}"""
private const val HAND = """{"type": "stdio", "command": "/opt/bin/rtok", "args": ["mcp"]}"""

@Suppress("TooManyFunctions")
class McpDupesTest {
    @Test
    fun twoNamesWithOneLaunchAreAGroupAndEnvValuesNeverAppear() {
        val m = McpMock()
        m.user(
            """"a": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "s3cret"}},
               "b": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "s3cret"}},
               "c": {"command": "/bin/tool", "args": ["--y"], "env": {"TOKEN": "s3cret"}},
               "d": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "other"}}""",
        )
        val found = run(m)
        assertEquals(listOf(Shape("duplicate-mcp", "a", true), Shape("duplicate-mcp", "b", false)), shape(found))
        assertEquals("runs 2 times; keep this copy (user file)", found[0].detail)
        assertEquals("/bin/tool --x", found[0].command)
        val all = "$found${Dupes.renderMcp(found)}"
        assertTrue(!all.contains("s3cret") && !all.contains("TOKEN"), all)
    }

    @Test
    fun pathAndSymlinkResolutionMakeABareCommandEqualItsBinary() {
        val m = McpMock()
        m.path["tool"] = "/usr/local/bin/tool"
        m.links["/usr/local/bin/tool"] = "/opt/tool/bin/tool"
        m.user(""""a": {"command": "tool"}, "b": {"command": "/opt/tool/bin/tool"}, "c": {"command": "other"}""")
        assertEquals(2, run(m).size)
    }

    @Test
    fun npxVersionsConflictAndEqualOnesDuplicate() {
        val m = McpMock()
        m.user(
            """"a": {"command": "npx", "args": ["-y", "pkg@1.0.0"]},
               "b": {"command": "npx", "args": ["--yes", "pkg@2.0.0"]},
               "s1": {"command": "npx", "args": ["-y", "@sc/x@3"]},
               "s2": {"command": "npx", "args": ["@sc/x@3"]}""",
        )
        val found = run(m)
        assertEquals(
            listOf(
                Shape("duplicate-mcp", "s1", true),
                Shape("duplicate-mcp", "s2", false),
                Shape("conflicting-mcp", "a", false),
                Shape("conflicting-mcp", "b", false),
            ),
            shape(found),
        )
        assertTrue(found[2].detail.contains("2 versions") && found[2].detail.endsWith("1.0.0"))
    }

    @Test
    fun urlsCompareAfterLowercasingHostAndDroppingATrailingSlash() {
        val m = McpMock()
        m.user(
            """"a": {"type": "http", "url": "HTTPS://Example.COM/mcp/"},
               "b": {"type": "http", "url": "https://example.com/mcp"},
               "c": {"type": "sse", "url": "https://example.com/mcp"},
               "d": {"type": "http", "url": "https://example.com/Mcp"}""",
        )
        assertEquals(2, shape(run(m)).size)
    }

    @Test
    fun disabledServersAreNotComparedAndALoneRtokEntryIsSilent() {
        val m = McpMock()
        m.user(
            """"a": {"command": "/bin/t", "disabled": true}, "b": {"command": "/bin/t"},
               "c": {"command": "/bin/u", "enabled": false}, "d": {"command": "/bin/u"},
               "rtok": {"command": "rtok", "args": ["mcp"]},
               "rtok-mcp": {"command": "rtok", "args": ["mcp"], "disabled": true}""",
        )
        assertTrue(run(m).isEmpty())
    }

    @Test
    fun rtokMcpUnderAnotherNameIsARemovableDoubleLaunchNextToTheKeptRtok() {
        val m = McpMock()
        m.user(""""rtok": $OWN, "rtok-mcp": $HAND""")
        val found = run(m)
        assertEquals(listOf(Own("duplicate-mcp", "rtok", true, false), Own("duplicate-mcp", "rtok-mcp", false, true)), own(found))
        assertTrue(found[0].detail.startsWith("runs 2 times; keep this copy"))
        assertEquals("/opt/bin/rtok mcp", found[1].command)
    }

    @Test
    fun theRtokConfigEntryIsKeptEvenWhenAWiderScopeNamesItToo() {
        val m = McpMock()
        m.user(""""rtok": $OWN""")
        m.files["/proj/.mcp.json"] = """{"mcpServers": {"rtok": $OWN, "rtok-mcp": $HAND}}"""
        val found = run(m)
        assertEquals(listOf("/h/.claude.json"), found.filter { it.keep }.map { it.source })
        val by = found.map { it.kind to it.fixable }
        assertTrue(by.contains("own-mcp" to false) && by.contains("duplicate-mcp" to true))
        assertEquals(3, found.size, "$found")
    }

    @Test
    fun theSameRtokNameInTwoScopesIsInformationOnlyAndNamesTheScopeTheHostUses() {
        val m = McpMock()
        m.files["/h/.claude.json"] = """{"mcpServers": {"rtok": $OWN}, "projects": {"/proj": {"mcpServers": {"rtok": $OWN}}}}"""
        m.files["/proj/.mcp.json"] = """{"mcpServers": {"rtok": $OWN}}"""
        val found = run(m)
        assertEquals(3, found.size)
        assertTrue(found.all { it.kind == "own-mcp" && !it.fixable })
        assertEquals(1, found.count { it.keep })
        assertTrue(
            found[1].detail.contains("information only") && found[1].detail.contains("the host uses the local scope entry"),
            found[1].detail,
        )
        val text = Dupes.renderMcp(found)
        assertTrue(text.contains("rtok is configured 3 times"), text)
        assertTrue(text.contains("info  /proj/.mcp.json"), text)
    }

    @Test
    fun aGeminiExtensionCopyIsInformationWithTheUpdateHint() {
        val m = McpMock()
        val entry = """{"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}}"""
        m.files["/h/.gemini/settings.json"] = entry
        m.files["/h/.gemini/extensions/rtok/gemini-extension.json"] = """{"name": "rtok", "version": "1", ${entry.substring(1)}"""
        val found = run(m)
        assertEquals(listOf(Own("own-mcp", "rtok", true, false), Own("own-mcp", "rtok", false, false)), own(found))
        assertTrue(found.all { it.agent == "gemini" })
        assertEquals("/h/.gemini/settings.json", found[0].source)
        assertTrue(found[1].source.endsWith("gemini-extension.json"))
        assertTrue(found[1].detail.contains("`rtok agents update gemini`"), found[1].detail)
        // Without the extension on disk there is one entry and nothing to say.
        m.files.remove("/h/.gemini/extensions/rtok/gemini-extension.json")
        assertTrue(run(m).isEmpty())
    }

    @Test
    fun anEnabledClaudePluginCopyOfRtokIsInformationAndATomlHostFollowsTheRules() {
        val m = McpMock()
        m.user(""""rtok": $OWN""")
        val plugins = m.plugin("rtok@mkt", """{"mcpServers": {"rtok": $OWN}}""")
        val found = run(m, plugins)
        assertEquals(listOf(Own("own-mcp", "rtok", true, false), Own("own-mcp", "plugin:rtok:rtok", false, false)), own(found))
        assertTrue(found[1].detail.contains("`rtok agents update claude`"))
        m.files["/h/.codex/config.toml"] =
            "[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n[mcp_servers.rtok-mcp]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n"
        val codex = run(m).filter { it.agent == "codex" }
        assertEquals(listOf(Own("duplicate-mcp", "rtok", true, false), Own("duplicate-mcp", "rtok-mcp", false, true)), own(codex))
    }

    @Test
    fun aHandWrittenDoubleLaunchAndAPluginCopyMakeTwoGroupsWithTheEntryListedOnce() {
        val m = McpMock()
        m.files["/h/.gemini/settings.json"] = """{"mcpServers": {"rtok": $OWN, "rtok-mcp": $HAND}}"""
        m.files["/h/.gemini/extensions/rtok/gemini-extension.json"] = """{"mcpServers": {"rtok": $OWN}}"""
        val found = run(m)
        assertEquals(
            listOf(Own("duplicate-mcp", "rtok", true, false), Own("duplicate-mcp", "rtok-mcp", false, true), Own("own-mcp", "rtok", false, false)),
            own(found),
        )
        assertNotEquals(found[0].group, found[2].group)
    }

    @Test
    fun theSameNameInTwoScopesIsShadowedByLocalThenProjectThenUser() {
        val m = McpMock()
        m.files["/h/.claude.json"] =
            """{"mcpServers": {"x": {"command": "/bin/user"}}, "projects": {"/proj": {"mcpServers": {"x": {"command": "/bin/local"}}}}}"""
        m.files["/proj/.mcp.json"] = """{"mcpServers": {"x": {"command": "/bin/project"}}}"""
        val found = run(m)
        assertEquals(
            listOf("/proj/.mcp.json" to false, "/h/.claude.json" to true, "/h/.claude.json" to false),
            found.map { it.source to it.keep },
        )
        assertTrue(found[1].path.startsWith("projects./proj.mcpServers"))
        assertTrue(found[0].detail.contains("unused"), found[0].detail)
        assertTrue(found.all { it.fixable })
    }

    @Test
    fun aPluginServerAndAHandWrittenCopyAreAGroupThatKeepsThePlugin() {
        val m = McpMock()
        m.user(""""mine": {"command": "/h/plug/demo/bin/srv", "args": ["--x"]}, "other": {"command": "/bin/o"}""")
        val plugins =
            m.plugin(
                "demo@mkt",
                """{"mcpServers": {"srv": {"command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/srv", "args": ["--x"]},
                                   "other": {"command": "/bin/different"}}}""",
            )
        val found = run(m, plugins)
        assertEquals(listOf(Shape("duplicate-mcp", "mine", false), Shape("duplicate-mcp", "plugin:demo:srv", true)), shape(found))
        assertEquals("runs 2 times; keep this copy (enabled plugin)", found[1].detail)
        assertEquals("/h/plug/demo/.mcp.json", found[1].source)
        assertEquals("/h/plug/demo/bin/srv --x", found[1].command)
        // A plugin's file is never ours to rewrite; the hand-written copy is (T331.6).
        assertEquals(listOf(true, false), found.map { it.fixable })
    }

    @Test
    fun aPluginServerIsNotShadowedByASameNameEntryAndRtoksOwnIsSkipped() {
        val m = McpMock()
        m.user(""""srv": {"command": "/bin/a"}""")
        val plugins = m.plugin("demo@mkt", """{"mcpServers": {"srv": {"command": "/bin/b"}, "rtok": {"command": "rtok", "args": ["mcp"]}}}""")
        assertTrue(run(m, plugins).isEmpty())
        // Without the plugin in the enabled set its file is not read, whatever it holds.
        assertTrue(run(m).isEmpty())
        m.user(""""srv": {"command": "/bin/b"}""")
        assertEquals(2, run(m, plugins).size)
        assertTrue(run(m).isEmpty())
    }

    @Test
    fun twoPluginsProvidingOneLaunchKeepTheFirstAndAMissingFileIsSkipped() {
        val m = McpMock()
        val plugins = m.plugin("a@mkt", """{"mcpServers": {"s": {"command": "npx", "args": ["-y", "pkg@1"]}}}""").toMutableList()
        plugins += m.plugin("b@mkt", """{"mcpServers": {"s": {"command": "npx", "args": ["-y", "pkg@1"]}}}""")
        plugins += "c@mkt" to "/h/plug/c"
        assertEquals(listOf(Shape("duplicate-mcp", "plugin:a:s", true), Shape("duplicate-mcp", "plugin:b:s", false)), shape(run(m, plugins)))
    }

    /** T912: a host whose variants share one file (Cursor, now Codex) reported every duplicate once per variant. */
    @Test
    fun aSharedFileHostReportsADuplicateOnce() {
        val m = McpMock()
        m.files["/h/.cursor/mcp.json"] = """{"mcpServers": {"a": {"command": "/bin/t"}, "b": {"command": "/bin/t"}}}"""
        val found = run(m).filter { it.agent == "cursor" }
        assertEquals(2, found.size, "$found")
    }

    @Test
    fun aTomlHostAndAnUnparsableFileAreHandled() {
        val m = McpMock()
        m.files["/h/.codex/config.toml"] =
            "[mcp_servers.a]\ncommand = \"/bin/t\"\nargs = [\"1\"]\n[mcp_servers.b]\ncommand = \"/bin/t\"\nargs = [\"1\"]\n"
        m.files["/h/.claude.json"] = "{ nope"
        val found = run(m)
        assertEquals(2, found.size)
        assertEquals("codex", found[0].agent)
    }

    @Test
    fun theTextSaysNoneFoundOrListsEachGroupOnce() {
        assertEquals("duplicate mcp servers none found\n", Dupes.renderMcp(emptyList()))
    }

    @Test
    fun urlNormalFormMatchesTheUrlCrate() {
        assertEquals("https://example.com/mcp", McpDupes.normalUrl("HTTPS://Example.COM/mcp/"))
        assertEquals("https://example.com", McpDupes.normalUrl("https://Example.com"))
        assertEquals("not a url", McpDupes.normalUrl("Not A URL"))
    }
}
