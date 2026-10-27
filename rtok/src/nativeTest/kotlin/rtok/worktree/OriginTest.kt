// Port of the src/worktree/origin.rs tests.
package rtok.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginTest {
    @Test fun eachDocumentedPoolNamesItsHost() {
        val cases =
            listOf(
                "/h/.cursor/worktrees/app/abc" to "cursor",
                "/h/.windsurf/worktrees/app/abc" to "windsurf",
                "/h/.codex/worktrees/1a2b/app" to "codex",
                "/r/app/.claude/worktrees/x" to "claude",
                "/r/app/.kilo/worktrees/x" to "kilo",
                "/r/app/.rtok/worktrees/app-t1" to "rtok",
                "/h/conductor/workspaces/app/x" to "conductor",
                "/h/_worktrees/app-t1" to "other",
                "/h/.cursor/other/app" to "other",
            )
        for ((path, want) in cases) assertEquals(want, Origin.ofWith(path, null), path)
    }

    @Test fun codexHomeMovesTheCodexPool() {
        assertEquals("codex", Origin.ofWith("/data/codex/worktrees/a/app", "/data/codex"))
        assertEquals("other", Origin.ofWith("/data/codex/elsewhere/app", "/data/codex"))
    }

    @Test fun onlyTheCappedPoolsEvict() {
        assertTrue(Origin.evicts("cursor") && Origin.evicts("windsurf") && Origin.evicts("codex"))
        assertFalse(Origin.evicts("kilo") || Origin.evicts("claude") || Origin.evicts("other"))
    }
}
