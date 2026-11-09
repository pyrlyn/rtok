// Port of the hook_events.rs table invariants (the Rust table is pinned by tests/hook_manifests.rs).
package rtok.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookEventsTest {
    @Test fun rowCountsPerHostMatchTheRustTable() {
        val want =
            mapOf(
                "claude" to 11,
                "codebuddy" to 9,
                "cline" to 5,
                "commandcode" to 4,
                "codex" to 5,
                "copilot" to 7,
                "cursor" to 7,
                "devin" to 7,
                "gemini" to 6,
                "qwen" to 10,
                "grok" to 7,
                "kimi" to 9,
                "zcode" to 5,
            )
        assertEquals(want, HookEvents.ALL.groupingBy { it.host }.eachCount())
        assertEquals(want.keys.toList(), HookEvents.ALL.map { it.host }.distinct(), "row order is the host order of the Rust table")
    }

    @Test fun installerRowsLeaveOutPluginOnlyOnes() {
        assertTrue(HookEvents.installerRows("claude").isEmpty())
        assertTrue(HookEvents.installerRows("grok").isEmpty())
        assertEquals(listOf("beforeShellExecution", "afterShellExecution", "sessionEnd", "preCompact"), HookEvents.installed("cursor").map { it.first })
        assertEquals(listOf("PreToolUse", "PostToolUse", "TaskStart", "UserPromptSubmit", "SessionEnd"), HookEvents.installed("cline").map { it.first })
    }

    @Test fun hostEventsMapToRtokEventsAndMatchers() {
        assertEquals("SessionEnd" to "SessionEnd", HookEvents.installed("commandcode").last().let { it.second to "SessionEnd" })
        assertEquals("Stop", HookEvents.installed("commandcode").last().first)
        assertEquals(listOf("BeforeTool" to "PreToolUse", "AfterTool" to "PostToolUse"), HookEvents.installed("gemini").take(2))
        assertEquals(listOf("PreToolUse" to "^exec$", "PreToolUse" to "^read$"), HookEvents.entries("devin").take(2))
        assertEquals(listOf("Bash", "Read", "Skill", "*", "", "", "", "", "", ""), HookEvents.entries("qwen").map { it.second })
        assertEquals("SubagentStart", HookEvents.forHost("claude").last().hostEvent)
        assertTrue(HookEvents.forHost("codebuddy").none { it.hostEvent == "SubagentStart" })
    }
}
