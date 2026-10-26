// Port of agents/mod.rs tests for module and plugin rows: plugins grouped by the modules that carry their surfaces,
// the empty not-installed group omitted, expected/missing following support and flags, Claude's modules read back,
// and a flag module naming its flag.
package rtok.agents

import rtok.config.Config
import rtok.plugin.sdk.Surface
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModulesTest {
    private fun scratch(name: String) = join(tempDir(), "rtok-mods-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

    private val nowhere = join(tempDir(), "rtok-no-such-dir")

    @Test fun pluginsGroupByTheModulesThatCarryTheirSurfaces() {
        val dir = scratch("plugrows")
        val c = Config()
        val cfg = c.copy(setup = c.setup.copy(codex = c.setup.codex.copy(configPath = join(dir, "config.toml"))))
        writeString(cfg.setup.codex.configPath, "[mcp_servers.rtok]\ncommand = \"rtok\"\n")
        val rows = PluginRows.rows(Codex(), Kind.Cli, cfg)
        removeDirAll(dir)
        val state = { id: String -> rows.first { it.id == id }.state }
        assertEquals(ModuleState.Installed, state("read"))
        assertEquals(ModuleState.Installed, state("graph"))
        for (id in listOf("proxy", "measure", "cmd", "guard", "inject")) assertEquals(ModuleState.NotInstalled, state(id), id)
        val text = PluginRows.lines(rows, "", true)
        assertTrue("✓ installed      " in text, text)
        assertFalse("compress (off)" in text, text)
        assertTrue("cmd" in text && "inject" in text && "guard" in text, text)
        assertTrue(PluginRows.reaches(Pi(), Kind.Cli, listOf(Surface.Cli)))
        assertTrue(PluginRows.reaches(Pi(), Kind.Cli, listOf(Surface.Mcp)))
        assertFalse(PluginRows.reaches(Pi(), Kind.Cli, listOf(Surface.Proxy)))
        assertTrue(PluginRows.reaches(Cursor(), Kind.Desktop, listOf(Surface.Mcp)))
    }

    @Test fun emptyNotInstalledPluginGroupIsOmitted() {
        val text = PluginRows.lines(listOf(PluginRow("read", true, ModuleState.Installed)), "", false)
        assertFalse("not installed" in text, text)
        assertTrue("installed      read" in text, text)
        assertTrue("not supported  -" in text, text)
        val waiting = PluginRows.lines(listOf(PluginRow("proxy", false, ModuleState.NotInstalled)), "", false)
        assertTrue("not installed  proxy (off)" in waiting, waiting)
    }

    @Test fun expectedModulesFollowSupportAndFlagsAndMissingReadsThemBack() {
        val c = Config()
        var cfg =
            c.copy(
                setup = c.setup.copy(codex = c.setup.codex.copy(configPath = join(nowhere, "config.toml")), claude = c.setup.claude.copy(settingsPath = join(nowhere, "s.json"))),
                doctor = c.doctor.copy(claudeJson = join(nowhere, "c.json")),
            )
        assertEquals(listOf("hooks", "mcp", "plugin"), Modules.expected(Codex(), Kind.Cli, cfg))
        assertEquals(listOf("plugin"), Modules.expected(Pi(), Kind.Cli, cfg))
        assertEquals(listOf("mcp"), Modules.expected(Claude(), Kind.Desktop, cfg))
        assertEquals(listOf("hooks", "mcp", "plugin"), Modules.expected(Claude(), Kind.Cli, cfg))
        cfg = cfg.copy(setup = cfg.setup.copy(proxy = true, yes = true, mcp = false))
        assertEquals(listOf("hooks", "proxy", "plugin"), Modules.expected(Codex(), Kind.Cli, cfg))
        assertEquals(listOf("hooks", "proxy", "plugin"), Modules.expected(Claude(), Kind.Cli, cfg))
        assertEquals(listOf("plugin"), Modules.expected(Pi(), Kind.Cli, cfg))
        assertEquals(listOf("hooks", "proxy", "plugin"), Modules.missing(Claude(), Kind.Cli, cfg))
        assertEquals(emptyList(), Modules.missing(Claude(), Kind.Desktop, cfg))
    }

    @Test fun claudeModulesReadBackHooksAndAProxyOnAnyPort() {
        val dir = scratch("claude")
        val c = Config()
        val cfg =
            c.copy(
                proxy = c.proxy.copy(port = 9123u),
                setup = c.setup.copy(claude = c.setup.claude.copy(settingsPath = join(dir, "settings.json"))),
                doctor = c.doctor.copy(claudeJson = join(dir, "claude.json")),
            )
        writeString(
            cfg.setup.claude.settingsPath,
            """{"hooks":{"SessionStart":[{"hooks":[{"command":"rtok hook SessionStart"}]}]},"env":{"ANTHROPIC_BASE_URL":"${ProxyEnv.anthropic(cfg)}"}}""",
        )
        val rows = Modules.rows(Claude(), Kind.Cli, cfg)
        removeDirAll(dir)
        assertEquals(
            listOf("hooks" to ModuleState.Installed, "mcp" to ModuleState.NotInstalled, "proxy" to ModuleState.Installed, "plugin" to ModuleState.NotInstalled),
            rows.map { it.name to it.state },
        )
        val console = Modules.lines(rows, "  ", true)
        assertTrue("✓ hooks   installed" in console, console)
        assertTrue("✗ mcp     not installed" in console, console)
        assertTrue("✗ plugin  not installed" in console, console)
        val plain = Modules.lines(rows, "  ", false)
        assertTrue("  proxy   installed" in plain && '✓' !in plain, plain)
        assertEquals("hooks=yes mcp=no proxy=yes plugin=no", Modules.summary(rows))
    }

    @Test fun aFlagModuleNamesItsFlagAndAnUnknownBinHasNoPathOrVersion() {
        val c = Config()
        val cfg = c.copy(setup = c.setup.copy(codex = c.setup.codex.copy(configPath = join(nowhere, "config.toml"))))
        val rows = Modules.rows(Codex(), Kind.Cli, cfg)
        val row = { name: String -> rows.first { it.name == name } }
        assertEquals(ModuleState.NotInstalled, row("proxy").state)
        assertEquals(" (--proxy)", row("proxy").note)
        assertEquals(ModuleState.NotInstalled, row("hooks").state)
        assertTrue("✗ proxy   not installed (--proxy)" in Modules.lines(rows, "", true))
        val ghost = Variant(Kind.Cli, "ghost", listOf("rtok-no-such-binary"), listOf("~/rtok-no-such-app", "\$RTOK_NO_SUCH_VAR/app"))
        assertEquals("-", AppVersion.of(cfg, "ghost", ghost))
        assertNull(Locate.locate(cfg, "ghost", ghost))
    }
}
