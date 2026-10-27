// Port of src/agents/restart.rs tests for the process check (`desktop_app_name` per desktop variant, CLI-only hosts).
package rtok.agents

import rtok.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RestartTest {
    private class FakeProcs(
        val running: Set<String>,
    ) : Procs {
        override fun appRunning(name: String) = name in running

        override fun binRunning(bin: String) = bin in running
    }

    private val expectedNames =
        mapOf(
            "Antigravity" to "Antigravity",
            "Claude Desktop" to "Claude",
            "GitHub Copilot" to "GitHub Copilot",
            "Cursor" to "Cursor",
            "Devin" to "Devin",
            "Codex App" to "ChatGPT",
            "Cline for VS Code" to "Visual Studio Code",
            "Kilo Code for VS Code" to "Visual Studio Code",
            "Kimi Code Desktop" to "Kimi Code",
            "Kiro IDE" to "Kiro",
            "MiMo Desktop" to "Xiaomi MiMo AI",
            "OpenCode Desktop" to "OpenCode",
            "Roo Code" to "Visual Studio Code",
            "Goose" to "Goose",
            "Continue" to "Visual Studio Code",
            "VS Code" to "Visual Studio Code",
            "VS Code - Insiders" to "Visual Studio Code - Insiders",
            "Warp" to "Warp",
            "Windsurf" to "Windsurf",
            "WorkBuddy" to "WorkBuddy",
            "ZCode" to "ZCode",
            "Zed" to "Zed",
        )

    private fun expectedDesktopName(variant: String): String = expectedNames[variant] ?: fail("desktop variant `$variant` has no expected-name row (T141) — add one")

    @Test
    fun everyDesktopVariantOfEveryHostResolvesItsKnownAppName() {
        for (host in Hosts.ALL) {
            val desktop = host.variants.filter { it.kind == Kind.Desktop }
            for (v in desktop) {
                assertTrue(v.apps.isNotEmpty(), "${host.id}/${v.name} has no apps[]")
                val (name, _) = desktopAppName(HostBins.expandSpec(v.apps[0]), v.name)
                assertEquals(expectedDesktopName(v.name), name, "host ${host.id}")
            }
            if (desktop.isEmpty()) {
                val procs = FakeProcs(host.variants.flatMap { it.bins }.toSet())
                assertNull(
                    desktop.firstNotNullOfOrNull { runningDesktopApp(Config(), host.id, it, procs) },
                    "CLI-only host ${host.id} must never produce a desktop restart target",
                )
            }
        }
    }

    @Test
    fun aRunningCliBinaryMarksItsHostRunning() {
        val claude = Hosts.host("claude")!!
        assertTrue(hostRunning(Config(), claude, FakeProcs(setOf("claude"))))
        assertFalse(hostRunning(Config(), claude, FakeProcs(emptySet())))
    }
}
