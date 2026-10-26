// Port of agents/claude/mod.rs tests (install side, chunk 3d-1): the ten settings-file hooks, foreign hooks and user
// chains kept, MCP on both surfaces, the proxy env and the marketplace step list.
package rtok.agents

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

class ClaudeTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
    ) {
        val settings get() = cfg.setup.claude.settingsPath

        fun plugins(
            file: String,
            text: String,
        ) {
            createDirAll(join(dir, ".claude/plugins"))
            writeString(join(dir, ".claude/plugins/$file"), text)
        }
    }

    private fun scratch(
        name: String,
        dry: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-claude-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        createDirAll(join(dir, ".claude"))
        val c = Config()
        val setup = c.setup.copy(dryRun = dry, backup = false, claude = c.setup.claude.copy(settingsPath = join(dir, ".claude/settings.json")))
        return Scratch(c.copy(setup = setup, doctor = c.doctor.copy(claudeJson = join(dir, ".claude.json"))), dir)
    }

    private val calls = mutableListOf<List<String>>()
    private val noCli = Claude({ _, a, _ -> calls.add(a).let { null } }, { false })
    private val cli = Claude({ _, a, _ -> calls.add(a).let { null } }, { true })

    @Test fun dryRunOnAnEmptyFileIsTenAdditionsWithoutWorktreeRows() {
        val s = scratch("dry", dry = true)
        val report = Claude.run(s.cfg, false)
        assertTrue("10 additions" in report, report)
        assertTrue("+ SubagentStart " in report && "+ SessionEnd " in report, report)
        assertFalse("Worktree" in report, report)
        assertFalse(exists(s.settings))
        assertTrue(Agents.agent("claude") is Claude)
        removeDirAll(s.dir)
    }

    @Test fun applyTwiceThenRemoveKeepsForeignHooks() {
        val s = scratch("apply")
        writeString(s.settings, """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo other"}]}]}}""")
        assertTrue("10 additions" in Claude.run(s.cfg, false))
        assertEquals(NO_CHANGES, Claude.run(s.cfg, false))
        assertTrue("removed" in Claude.run(s.cfg, true))
        val raw = readToString(s.settings)
        assertTrue("echo other" in raw && "rtok hook" !in raw, raw)
        removeDirAll(s.dir)
    }

    @Test fun removeKeepsAUserCommandThatChainsRtok() {
        val s = scratch("chain")
        val chain = "notify-send hi && rtok hook PreToolUse"
        writeString(s.settings, """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"$chain"}]}]}}""")
        Claude.run(s.cfg, false)
        Claude.run(s.cfg, true)
        assertTrue(chain in readToString(s.settings))
        removeDirAll(s.dir)
    }

    @Test fun installWritesHooksMcpAndProxyAndRemoveTakesThemBack() {
        val s = scratch("install")
        val cfg = s.cfg.copy(setup = s.cfg.setup.copy(proxy = true))
        writeString(s.settings, """{"env":{"KEEP":"1"}}""")
        val lines = noCli.apply(cfg, Kind.Cli, Mode.Install)
        assertTrue(lines[0].startsWith("offer plugins/claude → claude plugin marketplace add pyrlyn/rtok"), lines[0])
        assertEquals(listOf("hooks", "mcp", "proxy"), noCli.installed(cfg, Kind.Cli))
        assertTrue("http://127.0.0.1:8790" in readToString(s.settings))
        assertTrue(noCli.apply(cfg, Kind.Cli, Mode.Install).drop(1).all { it == NO_CHANGES })
        noCli.apply(cfg, Kind.Cli, Mode.Remove)
        assertEquals(emptyList(), noCli.installed(cfg, Kind.Cli))
        assertTrue("KEEP" in readToString(s.settings))
        assertTrue(calls.isEmpty())
        removeDirAll(s.dir)
    }

    @Test fun removeLeavesAProxyUrlTheUserSet() {
        val s = scratch("proxy")
        writeString(s.settings, """{"env":{"ANTHROPIC_BASE_URL":"https://example.test"}}""")
        assertEquals(NO_CHANGES, Claude.proxy(s.cfg, true))
        val set = Claude.proxy(s.cfg, false)
        assertTrue("revert: set env.ANTHROPIC_BASE_URL to https://example.test" in set, set)
        assertEquals(NO_CHANGES, Claude.proxy(s.cfg, false))
        removeDirAll(s.dir)
    }

    @Test fun desktopSpecUsesItsOwnFileAndTheAbsoluteBinary() {
        val s = scratch("desktop")
        val spec = Claude.mcpSpec(s.cfg, Kind.Desktop)
        assertEquals(McpPaths.claudeDesktop(), spec.configPath)
        assertTrue(spec.configPath.endsWith("claude_desktop_config.json"))
        assertEquals(listOf(McpPaths.claudeDesktop()), noCli.files(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }

    @Test fun pluginStepsFollowTheMarketplaceState() {
        val s = scratch("market")
        val repo = Claude.MARKETPLACE_REPO
        assertEquals("claude plugin marketplace add pyrlyn/rtok && claude plugin install rtok@rtok", Claude.shownSteps(Claude.pluginSteps(s.cfg, false, repo, false)!!))
        assertNull(Claude.pluginSteps(s.cfg, true, repo, false))
        s.plugins("known_marketplaces.json", """{"rtok":{"source":{"source":"directory","path":"/x/.ketch/store/rtok/v0.6.3/plugins/claude"}}}""")
        assertEquals(MarketplaceState.Stale, Claude.marketplaceState(s.cfg, repo))
        val stale = Claude.shownSteps(Claude.pluginSteps(s.cfg, false, repo, false)!!)
        assertEquals("claude plugin marketplace remove rtok && claude plugin marketplace add pyrlyn/rtok && claude plugin install rtok@rtok", stale)
        s.plugins("known_marketplaces.json", """{"rtok":{"source":{"source":"github","repo":"listepo/rtok"}}}""")
        assertEquals(MarketplaceState.Stale, Claude.marketplaceState(s.cfg, repo))
        s.plugins("known_marketplaces.json", """{"rtok":{"source":{"source":"github","repo":"pyrlyn/rtok"}}}""")
        s.plugins("installed_plugins.json", """{"plugins":{"rtok@rtok":[]}}""")
        assertNull(Claude.pluginSteps(s.cfg, false, repo, false))
        assertEquals("claude plugin uninstall rtok@rtok && claude plugin install rtok@rtok", Claude.shownSteps(Claude.pluginSteps(s.cfg, false, repo, true)!!))
        removeDirAll(s.dir)
    }

    @Test fun anInstalledPluginServesTheHooksAndSettingsHooksAreStripped() {
        val s = scratch("plugin")
        Claude.run(s.cfg, false)
        s.plugins("known_marketplaces.json", """{"rtok":{"source":{"source":"github","repo":"pyrlyn/rtok"}}}""")
        s.plugins("installed_plugins.json", """{"plugins":{"rtok@rtok":[]}}""")
        val lines = cli.apply(s.cfg, Kind.Cli, Mode.Install)
        assertEquals(NO_CHANGES, lines[0])
        assertFalse("rtok hook" in readToString(s.settings))
        assertEquals(listOf("hooks", "mcp", "plugin"), cli.installed(s.cfg, Kind.Cli))
        assertEquals("- plugin rtok@rtok", cli.plugin(s.cfg, true))
        assertEquals(listOf(listOf("plugin", "uninstall", "rtok@rtok"), listOf("plugin", "marketplace", "remove", "rtok")), calls)
        removeDirAll(s.dir)
    }
}
