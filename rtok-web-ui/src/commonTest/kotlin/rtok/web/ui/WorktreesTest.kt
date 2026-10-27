package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixture and cases from web/src/pages/textFixtures.ts and text.test.ts (worktrees).
class WorktreesTest {
    private val text =
        listOf(
            "path                                       branch                 owner                   agent               agent state  state     seen  modified   source   cache",
            "~/GitHub/listepo/apps/rtok                 main                   -                       -                   -            main      -     2h         41 MB   6.2 GB",
            "~/GitHub/listepo/_worktrees/rtok-t307      t307-report-sparkline  Claude Code / sonnet-5  1a2b3c4d claude     active       dirty     3m    3m         41 MB   2.8 GB",
            "~/GitHub/listepo/_worktrees/rtok-web       design/web-admin       locked, owner unknown   -                   -            unmerged  -     1d         44 MB      0 B",
            "~/GitHub/listepo/_worktrees/rtok-t305      t305-stats-replay      -                       seen codex 9f8e7d6c  -           merged    4d    4d         41 MB   3.1 GB",
            "~/GitHub/listepo/_worktrees/rtok-t290      -                      -                       -                   -            stale     -     -           0 B      0 B",
            "5 worktrees: 167 MB source, 12.1 GB build cache (logical bytes; clones and hard links count in full)",
        ).joinToString("\n")

    private val v = parseWorktrees(text)

    @Test fun mapsTheHeaderToFields() {
        assertNull(v.message)
        assertEquals(5, v.rows.size)
        val r = v.rows[1]
        assertEquals("~/GitHub/listepo/_worktrees/rtok-t307", r.path)
        assertEquals("t307-report-sparkline", r.branch)
        assertEquals("Claude Code / sonnet-5", r.owner)
        assertEquals("1a2b3c4d claude", r.agent)
        assertEquals("active", r.agentState)
        assertEquals("dirty", r.state)
        assertEquals("3m", r.seen)
        assertEquals("41 MB", r.source)
        assertEquals("2.8 GB", r.cache)
        assertEquals("seen codex 9f8e7d6c", v.rows[3].agent)
        assertEquals("5 worktrees: 167 MB source, 12.1 GB build cache", v.total)
        assertTrue("logical bytes" in v.note)
    }

    @Test fun filtersByStateAndText() {
        assertEquals(1, v.rows.count { matchesWorktree(it, "dirty", "") })
        assertEquals(1, v.rows.count { matchesWorktree(it, "all", "CLAUDE") })
    }

    @Test fun nonTableStatesAreMessages() {
        assertEquals("reading worktrees…", parseWorktrees("reading worktrees…\n").message)
        assertEquals("not a git repository", parseWorktrees("not a git repository\n").message)
        assertEquals("rtok-t307", baseName("~/x/rtok-t307/"))
    }
}
