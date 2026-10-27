// Port of the src/worktree/list.rs tests not already in CleanTest (the walk split and the unreadable directory).
package rtok.worktree

import rtok.store.SessionSeen
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.setModified
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NANOS = 1_000_000_000L
private const val SIG = "Signature: 8a477f597d28d172789f06886806bc55"
private const val KB = 1024L
private const val SRC = 10
private const val BIN = 1000
private const val OLD = 1_000L
private const val NEW = 2_000L
private const val DAY = 86_400L
private const val HOUR = 3_600L
private const val SEEN_A = 100L
private const val ENDED_A = 150L
private const val SEEN_B = 200L
private const val SEEN_MAIN = 300L

class WorktreeListTest {
    @Test fun usageSourcesSkipsTaggedCaches() {
        val dir = tmpDir("wt-t936-sources")
        createDirAll("$dir/src")
        createDirAll("$dir/target/debug")
        writeString("$dir/src/a.rs", "x".repeat(SRC))
        writeString("$dir/target/CACHEDIR.TAG", SIG)
        writeString("$dir/target/debug/bin", "x".repeat(BIN))
        val full = usage(dir)
        val quick = usageSources(dir)
        assertEquals(full.source, quick.source)
        assertEquals(0L to 0, quick.cache to quick.caches.size)
        assertTrue(full.cache > 0)
    }

    @Test fun newestUntilLeavesTaggedCachesOut() {
        val dir = tmpDir("wt-t948-sources")
        createDirAll("$dir/target/debug")
        writeString("$dir/a.rs", "x")
        writeString("$dir/target/CACHEDIR.TAG", SIG)
        writeString("$dir/target/debug/bin", "x")
        setModified("$dir/a.rs", OLD * NANOS)
        setModified("$dir/target/CACHEDIR.TAG", NEW * NANOS)
        setModified("$dir/target/debug/bin", NEW * NANOS)
        // Nothing is ever enough: the whole walk.
        val sources = newestUntil(dir) { false }
        val used = usage(dir)
        removeDirAll(dir)
        assertEquals(OLD * NANOS, sources)
        assertEquals(NEW * NANOS, used.modified)
        assertEquals(NEW * NANOS, used.caches[0].modified)
    }

    @Test fun newestUntilStopsAtTheFirstFileThatIsEnough() {
        val dir = tmpDir("wt-newest")
        for (name in listOf("a", "b", "c")) writeString("$dir/$name", "x")
        var seen = 0
        val found =
            newestUntil(dir) {
                seen++
                true
            }
        assertEquals(1, seen, "one recent file ends the walk")
        assertTrue(found != null && found <= usage(dir).modified!!)
        assertNull(newestUntil("$dir/missing") { true })
        removeDirAll(dir)
    }

    private fun row(
        state: String,
        owner: String?,
        locked: Boolean,
        session: Seen?,
        cache: Long?,
    ) = Row(
        path = "/w/x",
        branch = "t1",
        owner = owner,
        locked = locked,
        state = state,
        origin = "other",
        session = session,
        sourceBytes = KB,
        cacheBytes = cache,
        modifiedUnix = OLD,
    )

    @Test fun theTableNamesAnUnknownOwnerAndTotalsBothKindsOfBytes() {
        val seen = Seen("b1e2c3d4-0000-4000-8000-000000000001", "claude", OLD + 3 * DAY, true)
        val rows =
            listOf(
                row("merged", "Cursor / grok", true, seen, 2 * KB),
                row("dirty", null, true, null, 0),
                row("unmerged", null, false, seen, 0),
                row("orphan", null, false, null, 0),
                row("unmerged", "claude / sonnet", true, null, 0)
                    .copy(agent = Bound("0193ab12-0000-7000-8000-000000000000", "claude", "live")),
            )
        val now = OLD + 3 * DAY + 4 * HOUR
        val want =
            """
            path branch owner                 agent                agent state state    origin seen  modified source  cache
            /w/x t1     Cursor / grok         seen claude b1e2c3d4 -           merged   other  4h00m 3d04h    1.0 KB 2.0 KB
            /w/x t1     locked, owner unknown -                    -           dirty    other  -     3d04h    1.0 KB    0 B
            /w/x t1     -                     seen claude b1e2c3d4 -           unmerged other  4h00m 3d04h    1.0 KB    0 B
            /w/x t1     -                     -                    -           orphan   other  -     3d04h    1.0 KB    0 B
            /w/x t1     claude / sonnet       0193ab12 claude      live        unmerged other  -     3d04h    1.0 KB    0 B

            5 worktrees: 5.0 KB source, 2.0 KB build cache (logical bytes; clones and hard links count in full)

            """.trimIndent()
        assertEquals(want, WorktreeList.toTable(rows, now).lines().joinToString("\n") { it.trimEnd() })
    }

    /** T936: a row whose caches were not walked prints `-` and the footer says why. */
    @Test fun anUnsizedRowHasNoCacheBytesInTheTable() {
        val table = WorktreeList.toTable(listOf(Row.of("/w/x", "dirty", false)), 0)
        assertTrue("build cache not sized (--sizes)" in table, table)
        assertTrue(table.lines()[1].endsWith(" -"), table)
    }

    /** T154: the newest session under a linked worktree wins; the main checkout and an unvisited worktree stay unattributed. */
    @Test fun attributePicksTheNewestSessionUnderEachLinkedWorktree() {
        val dir = tmpDir("wt-attribute")
        for (sub in listOf("work", "wt-a/src", "wt-b")) createDirAll("$dir/$sub")
        val sessions =
            listOf(
                SessionSeen("old", "claude", "$dir/wt-a", SEEN_A, ENDED_A),
                SessionSeen("new", "claude", "$dir/wt-a/src", SEEN_B, null),
                SessionSeen("main", "claude", "$dir/work", SEEN_MAIN, null),
            )
        val rows = listOf("work" to "main", "wt-a" to "dirty", "wt-b" to "merged").map { (n, s) -> Row.of("$dir/$n", s, false) }
        WorktreeList.attribute(rows, sessions)
        assertNull(rows[0].session, "the main checkout belongs to nobody")
        assertEquals(Seen("new", "claude", SEEN_B, true), rows[1].session)
        assertNull(rows[2].session)
        removeDirAll(dir)
    }
}
