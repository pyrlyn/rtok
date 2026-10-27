// Port of src/agents/junk_cache.rs tests.
package rtok.agents

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.diskUsageUntil
import rtok.sys.isDir
import rtok.sys.listDir
import rtok.sys.parent
import rtok.sys.setModified
import rtok.sys.writeString
import rtok.worktree.KEPT_CURRENT
import rtok.worktree.KEPT_IDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** Every file under [dir] last modified [secs] ago (junk_cache.rs `age_files`). */
@OptIn(ExperimentalTime::class)
internal fun ageFiles(
    dir: String,
    secs: Long,
) {
    val now = Clock.System.now()
    val whenNanos = (now.epochSeconds - secs) * NANOS + now.nanosecondsOfSecond
    for (name in listDir(dir)) {
        val p = "$dir/$name"
        if (isDir(p)) ageFiles(p, secs) else setModified(p, whenNanos)
    }
}

/** A valid `CACHEDIR.TAG` in [dir] (junk_cache.rs `tagged`). */
internal fun tagged(dir: String) = writeString("$dir/CACHEDIR.TAG", "Signature: 8a477f597d28d172789f06886806bc55\n# a cache\n")

@OptIn(ExperimentalTime::class)
internal fun nowNanos(): Long = Clock.System.now().let { it.epochSeconds * NANOS + it.nanosecondsOfSecond }

private const val NANOS = 1_000_000_000L
private const val TWO_DAYS = 172_800L
private const val SIZE = 10
private const val LISTED = 3

class JunkCacheTest {
    private fun item(
        path: String,
        kept: String? = null,
    ) = JunkItem("cache", "safe", path, 1, JunkCache.TAG, kept)

    private fun put(
        path: String,
        bytes: Int,
    ) {
        createDirAll(parent(path)!!)
        writeString(path, "x".repeat(bytes))
    }

    private val limit = SIZE.seconds

    @Test fun anItemInsideACountedOneIsDroppedButNotInsideAKeptOne() {
        val items = listOf(item("/a/cache"), item("/a/cache/inner"), item("/b/target", KEPT_IDLE), item("/b/target/inner"), item("/a/cache"))
        assertEquals(listOf("/a/cache", "/b/target", "/b/target/inner"), dropNested(items).map { it.path })
    }

    @Test fun taggedCachesFollowTheIdleAndCurrentWorktreeRules() {
        val root = tmpDir("junk-cache-tags")
        for (d in listOf("old", "new", "bad", "owned", "owned/inner")) put("$root/$d/o", SIZE)
        for (d in listOf("old", "new", "owned/inner")) tagged("$root/$d")
        writeString("$root/bad/CACHEDIR.TAG", "Signature: nope\n")
        ageFiles("$root/old", TWO_DAYS)
        ageFiles("$root/owned", TWO_DAYS)
        val owned = listOf(OwnedCache("$root/owned", JunkCache.SECTION_22, false))

        val elsewhere = JunkCtx(tmpDir("junk-cache-elsewhere"), JunkCache.DEFAULT_IDLE, nowNanos())
        val items = cacheItems(owned, listOf(root), elsewhere, limit)

        fun row(name: String) = items.first { it.path.endsWith("/$name") }
        assertEquals(LISTED, items.size, "bad signature and nested item are not listed: $items")
        assertEquals(JunkCache.TAG to null, row("old").evidence to row("old").kept)
        assertEquals(JunkCache.SECTION_22, row("owned").evidence)
        assertEquals(KEPT_IDLE, row("new").kept)
        assertEquals(diskUsageUntil("$root/old", null).bytes, row("old").bytes)

        val inside = JunkCtx("$root/old", JunkCache.DEFAULT_IDLE, nowNanos())
        assertTrue(cacheItems(emptyList(), listOf(root), inside, limit).all { it.kept == KEPT_CURRENT })
    }

    @Test fun anOwnedToolCacheWaitsForTheIdleWindowAndAMissingOneIsSkipped() {
        val root = tmpDir("junk-cache-owned")
        put("$root/busy/o", SIZE)
        put("$root/quiet/o", SIZE)
        ageFiles("$root/quiet", TWO_DAYS)

        fun own(name: String) = OwnedCache("$root/$name", JunkCache.RTOK_OWN, true)
        val cx = JunkCtx(root, JunkCache.DEFAULT_IDLE, nowNanos())
        val items = cacheItems(listOf(own("busy"), own("quiet"), own("gone")), emptyList(), cx, limit)
        assertEquals(listOf(true to false, false to true), items.map { it.path.endsWith("busy") to it.counted })
    }

    @Test fun aCutShortScanFreesNothing() {
        val root = tmpDir("junk-cache-cut")
        put("$root/old/o", SIZE)
        tagged("$root/old")
        ageFiles("$root/old", TWO_DAYS)
        val cx = JunkCtx(tmpDir("junk-cache-cut-cwd"), JunkCache.DEFAULT_IDLE, nowNanos())
        val items = cacheItems(listOf(OwnedCache("$root/old", JunkCache.RTOK_OWN, true)), listOf(root), cx, Duration.ZERO)
        assertTrue(items.none { it.counted }, "a stopped scan must not count as freed: $items")
    }
}
