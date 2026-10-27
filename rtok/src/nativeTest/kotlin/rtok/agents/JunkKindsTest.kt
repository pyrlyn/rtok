// Port of src/agents/junk_kinds.rs tests (agent_worktrees is in JunkWorktreesTest).
package rtok.agents

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.parent
import rtok.sys.pid
import rtok.sys.setModified
import rtok.sys.symlink
import rtok.sys.tryLockExclusive
import rtok.sys.writeBytes
import rtok.worktree.KEPT_CURRENT
import rtok.worktree.KEPT_IDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val DAY = 86_400L
private const val TWO_DAYS = 2 * DAY
private const val THREE_DAYS = 3 * DAY
private const val HOUR = 3_600L
private const val MINUTE = 60L
private const val STALE_DAYS = 40
private const val STALER_DAYS = 80
private const val BIG = 100
private const val SMALL = 50
private const val TARGET = 200
private const val NO_SUCH_PID = 0x7fff_fff0
private const val BUILD_ROWS = 5
private const val TEMP_ROWS = 4

class JunkKindsTest {
    private val limit = LIMIT

    private fun oldDir(
        root: String,
        name: String,
        secs: Long,
    ): String {
        val d = "$root/$name"
        putFile("$d/x.js.cache", "v8".encodeToByteArray())
        ageBy("$d/x.js.cache", secs)
        ageBy(d, secs)
        return d
    }

    private fun cx(cwd: String) = JunkCtx(cwd, JunkCache.DEFAULT_IDLE, nowNanos())

    @Test fun codeCacheKeepsTheNewestCommitAndOffersTheOlderOnes() {
        val root = tmpDir("junk-code-cache")
        val cur = oldDir(root, "cccc", HOUR)
        val old = oldDir(root, "aaaa", STALE_DAYS * DAY)
        val older = oldDir(root, "bbbb", STALER_DAYS * DAY)
        putFile("$root/stray-file")
        symlink(cur, "$root/link")
        val stale = staleCodeCaches(root)
        assertEquals(listOf(old, older), stale.map { it.path }.sorted())
        assertTrue(stale.all { it.idleRule && it.evidence == JunkCache.SECTION_22 })
        assertTrue(staleCodeCaches(cur).isEmpty())
        assertTrue(staleCodeCaches("$root/missing").isEmpty())
    }

    @Test fun aStaleCommitFolderEditedInsideTheIdleWindowIsKeptByCacheItems() {
        val root = tmpDir("junk-code-cache-idle")
        oldDir(root, "cccc", MINUTE)
        val busy = oldDir(root, "aaaa", STALE_DAYS * DAY)
        putFile("$busy/fresh")
        ageBy(busy, STALE_DAYS * DAY)
        val gone = oldDir(root, "bbbb", STALER_DAYS * DAY)
        val items = cacheItems(staleCodeCaches(root), emptyList(), cx(root), limit)

        fun kept(p: String) = items.first { it.path == p }.kept != null
        assertTrue(kept(busy))
        assertTrue(!kept(gone))
        assertEquals(2, items.size)
    }

    @Test fun tempGoesWhenIdleAndStaysWhenFreshHeldOrALink() {
        val root = tmpDir("kinds-temp")
        val dir = "$root/shell-snapshots"
        putFile("$dir/old.sh", ByteArray(BIG) { 'x'.code.toByte() })
        putFile("$dir/new.sh")
        putFile("$dir/held.sh")
        putFile("$dir/old-dir/f", ByteArray(SMALL) { 'x'.code.toByte() })
        for (f in listOf("old.sh", "held.sh", "old-dir/f")) ageBy("$dir/$f", TWO_DAYS)
        assertNotNull(tryLockExclusive("$dir/held.sh"))
        symlink(root, "$dir/link")

        val items = tempItems(listOf(dir), JunkCache.DEFAULT_IDLE, cx(tmpDir("kinds-temp-cwd")), limit)

        assertEquals(TEMP_ROWS, items.size, "a symlink is no item: $items")
        for (gone in listOf("old.sh", "old-dir")) {
            val i = junkRow(items, gone)
            assertTrue(i.counted && i.evidence == JunkCache.SECTION_22 && i.kind == "temp")
        }
        assertEquals(KEPT_IDLE, junkRow(items, "new.sh").kept)
        assertEquals(JunkKinds.HELD, junkRow(items, "held.sh").kept)
    }

    @Test fun aTaggedTargetFollowsT152AndUntaggedBuildDirsAreListedOnly() {
        val wt = tmpDir("kinds-build")
        putFile("$wt/src/main.rs")
        putFile("$wt/target/o", ByteArray(TARGET) { 'x'.code.toByte() })
        tagged("$wt/target")
        for (f in listOf("target/pkg/__pycache__/c", "web/dist/app.js", "py/__pycache__/m.pyc", "node_modules/pkg/dist/i.js")) putFile("$wt/$f")
        putFile("$wt/app/package.json", "{}".encodeToByteArray())
        putFile("$wt/app/node_modules/m/i.js")
        ageFiles(wt, THREE_DAYS)

        val items = buildItems(listOf(wt), cx(tmpDir("kinds-b-cwd")), limit)

        assertEquals(BUILD_ROWS, items.size, "$items")

        fun deps(n: String): String {
            val i = junkRow(items, n)
            assertEquals(Triple("deps", "review", false), Triple(i.kind, i.cls, i.counted))
            return i.kept!!
        }
        assertEquals(JunkKinds.NOT_DOCUMENTED, deps("app/node_modules"))
        assertTrue(deps("node_modules").startsWith("no lockfile or manifest"))
        val target = junkRow(items, "target")
        assertTrue(target.counted && target.evidence == JunkCache.TAG && target.kind == "build")
        for (listed in listOf("web/dist", "py/__pycache__")) {
            val i = junkRow(items, listed)
            assertEquals(JunkKinds.NOT_DOCUMENTED to "none", i.kept to i.evidence)
        }

        val here = buildItems(listOf(wt), cx("$wt/src"), limit)
        assertEquals(KEPT_CURRENT, junkRow(here, "target").kept)
    }

    @Test fun onlyPluginVersionsTheHostRecordNoLongerNamesAreStagingJunk() {
        val claude = tmpDir("kinds-staging")
        val cache = "$claude/${ClaudeUpdate.PLUGIN_CACHE}"
        for (v in listOf("0.1.0", "0.2.0", "0.3.0")) putFile("$cache/$v/plugin.json", "{}".encodeToByteArray())
        assertTrue(stagingCaches(claude).isEmpty(), "no record, nothing is stale")

        fun record(body: String) = putFile("$claude/plugins/installed_plugins.json", body.encodeToByteArray())
        record("""{"version":2,"plugins":{"other@x":[{"installPath":"/p"}]}}""")
        assertTrue(stagingCaches(claude).isEmpty(), "no rtok install named")
        record("not json")
        assertTrue(stagingCaches(claude).isEmpty())

        record("""{"version":2,"plugins":{"rtok@rtok":[{"installPath":"$cache/0.2.0"}]}}""")
        val stale = stagingCaches(claude)
        assertEquals(listOf("0.1.0", "0.3.0"), stale.map { it.path.substringAfterLast('/') }.sorted())
        assertTrue(stale.all { it.evidence == JunkCache.RTOK_OWN && it.idleRule })
    }
}
