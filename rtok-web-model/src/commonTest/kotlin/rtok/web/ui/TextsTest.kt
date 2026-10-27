package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures and cases from web/src/pages/textFixtures.ts and text.test.ts.
class TextsTest {
    private val hostsText =
        listOf(
            "CLI: Claude Code",
            "  app     /opt/homebrew/bin/claude (2.1.4)",
            "  config  ~/.claude/settings.json, ~/.claude.json",
            "  ✓ hooks   installed",
            "  ✓ mcp     installed",
            "  ✓ proxy   installed",
            "  ✗ plugin  not installed --plugin",
            "CLI: Codex",
            "  app     /opt/homebrew/bin/codex (0.64.0)",
            "  config  ~/.codex/config.toml",
            "  − hooks   not supported",
            "  ✓ mcp     installed",
            "  ✗ proxy   not installed --proxy",
            "  ✓ plugin  installed 0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update codex",
            "Desktop: Cursor",
            "  app     /Applications/Cursor.app (2.3.1)",
            "  config  ~/.cursor/hooks.json, ~/.cursor/mcp.json",
            "  ✓ hooks   installed",
            "  ✓ plugin  installed 0.15.1 (local)",
            "CLI: Gemini CLI — not found",
            "  app     -",
            "  config  ~/.gemini/settings.json",
            "Desktop: Zed — not installed",
            "  skip    nothing of rtok here; run `rtok agents install zed`",
            "",
        ).joinToString("\n")

    private val configText =
        listOf(
            "core.enabled = true (default)",
            "core.db_path = \"~/.rtok/rtok.db\" (default)",
            "core.retain_calls_days = 14 (user)",
            "log.lines = 200 (default)",
            "log.level = \"debug\" (env)",
            "hook.max_ms = 10 (default)",
            "mcp.tools = [] (default)",
            "proxy.port = 8790 (default)",
            "proxy.mode = \"compress\" (project)",
            "web.port = 3333 (flag)",
            "demon.services = [\"proxy\", \"web\"] (user)",
            "plugins.measure.enabled = false (project)",
            "",
        ).joinToString("\n")

    @Test fun hostBlocks() {
        val v = parseHosts(hostsText)
        assertEquals(
            listOf(
                listOf("CLI", "Claude Code", ""),
                listOf("CLI", "Codex", ""),
                listOf("Desktop", "Cursor", ""),
                listOf("CLI", "Gemini CLI", "not found"),
                listOf("Desktop", "Zed", "not installed"),
            ),
            v.blocks.map { listOf(it.kind, it.name, it.note) },
        )
        assertEquals("/opt/homebrew/bin/claude (2.1.4)", v.blocks[0].app)
        assertEquals(listOf("~/.claude/settings.json", "~/.claude.json"), v.blocks[0].config)
        assertEquals("plugin" to "not installed --plugin", v.blocks[0].modules[3])
        assertNull(v.blocks[3].app)
        assertTrue(v.blocks[4].skip!!.contains("rtok agents install zed"))
    }

    @Test fun pluginRowKeepsVersion() {
        val v = parseHosts(hostsText)
        assertEquals("plugin" to "installed 0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update codex", v.blocks[1].modules.last())
        assertEquals("plugin" to "installed 0.15.1 (local)", v.blocks[2].modules.last())
        val legacy = parseHosts("CLI: Gemini CLI\n  ✓ plugin  installed (legacy, no version)\n")
        assertEquals(listOf("plugin" to "installed (legacy, no version)"), legacy.blocks[0].modules)
    }

    @Test fun probingAndStrayLines() {
        assertEquals(HostsView(true, emptyList(), emptyList()), parseHosts("probing hosts…\n"))
        assertEquals(listOf("stray line"), parseHosts("stray line\nCLI: X\n").other)
        assertEquals(
            1,
            parseHosts("CLI: X\n  ✓ mcp  installed\njunk\n  caches 3\n")
                .blocks
                .single()
                .modules.size,
        )
    }

    @Test fun moduleStates() {
        assertEquals(Tagged("warn", "outdated", "0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update codex"), moduleState("installed 0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update codex"))
        assertEquals("newer", moduleState("installed 0.16.0 (local), rtok is 0.15.1").label)
        assertEquals(Tagged("ok", "installed", "0.15.1 (local)"), moduleState("installed 0.15.1 (local)"))
        assertEquals(Tagged("warn", "not installed", "--proxy"), moduleState("not installed --proxy"))
        assertEquals(Tagged("muted", "not supported"), moduleState("not supported"))
        assertEquals(Tagged("muted", "odd"), moduleState("odd"))
        assertEquals("present", hostNote("").label)
    }

    @Test fun configRows() {
        val all = parseConfig(configText)
        assertEquals(12, all.size)
        assertEquals(ConfigEntry("core.db_path", "\"~/.rtok/rtok.db\"", "default"), all[1])
        assertEquals("flag", all.first { it.key == "web.port" }.source)
        assertEquals(listOf("core.retain_calls_days", "demon.services"), all.filter { matchesConfig(it, "user", "") }.map { it.key })
        assertEquals(listOf("proxy.mode"), all.filter { matchesConfig(it, "all", "COMPRESS") }.map { it.key })
        assertEquals(
            listOf("core" to 3, "log" to 2, "hook" to 1, "mcp" to 1, "proxy" to 2, "web" to 1, "demon" to 1, "plugins" to 1),
            groupConfig(all).map { (g, r) -> g to r.size },
        )
        assertEquals(listOf("muted", "warn", "info"), listOf("default", "env", "user").map(::sourceTone))
    }
}
