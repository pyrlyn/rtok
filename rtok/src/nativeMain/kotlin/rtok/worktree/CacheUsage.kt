// Port of the walk in src/worktree/list.rs (`is_cache_dir`, `Usage`, `Cache`, `usage`, `usage_sources`, `usage_until`,
// `newest_until`): what a tree
// costs, split into source and tagged build cache (`CACHEDIR.TAG`). Read-only.
package rtok.worktree

import rtok.sys.EntryKind
import rtok.sys.IoException
import rtok.sys.exists
import rtok.sys.readDir
import rtok.sys.readPrefix
import rtok.sys.symlinkMeta
import kotlin.time.TimeSource

/** First line of a valid `CACHEDIR.TAG` (https://bford.info/cachedir/); the tag, not a name, makes bytes "cache". */
private const val CACHEDIR_SIGNATURE = "Signature: 8a477f597d28d172789f06886806bc55"

/** `is_cache_dir`. */
fun isCacheDir(dir: String): Boolean =
    runCatching { readPrefix("$dir/CACHEDIR.TAG", CACHEDIR_SIGNATURE.length.toLong()) }
        .getOrNull()
        ?.contentEquals(CACHEDIR_SIGNATURE.encodeToByteArray()) == true

/** One tagged cache root: the unit `rtok worktree clean` deletes. [modified] is nanoseconds since the epoch. */
data class Cache(
    val path: String,
    val bytes: Long = 0,
    val modified: Long? = null,
    /** [Usage.incomplete] for this cache root alone. */
    val incomplete: Boolean = false,
)

/** Logical bytes (a clone or hard link counts in full); [caches] are every tagged root, [cache] their sum. */
data class Usage(
    val source: Long = 0,
    val cache: Long = 0,
    val modified: Long? = null,
    val caches: List<Cache> = emptyList(),
    /** A directory, entry or mtime could not be read: sizes and [modified] are lower bounds. */
    val incomplete: Boolean = false,
)

/** `usage`. */
fun usage(dir: String): Usage = TreeWalk(null).run(dir).first

/**
 * `usage_sources` (T936): [usage] without the tagged cache roots, which hold nearly every file of a built worktree.
 * Cache bytes stay zero and `modified` is the newest source file, as for [newestUntil].
 */
fun usageSources(dir: String): Usage = TreeWalk(null, sourcesOnly = true).run(dir).first

/**
 * `newest_until` (T948): the newest mtime (nanoseconds) of [dir]'s source files, tagged cache roots left out, except
 * that the walk ends at the first file whose mtime satisfies [enough]; [enough] must hold for every later mtime too.
 * Null when the walk could not read everything and found no file that settles it.
 */
fun newestUntil(
    dir: String,
    enough: (Long) -> Boolean,
): Long? {
    val (total, settled) = TreeWalk(null, sourcesOnly = true, enough = enough).run(dir)
    return total.modified?.takeIf { settled || !total.incomplete }
}

/** `usage_until`: the flag says the walk was cut at [deadline], so the result (and every mtime) is a lower bound. */
fun usageUntil(
    dir: String,
    deadline: TimeSource.Monotonic.ValueTimeMark,
): Pair<Usage, Boolean> = TreeWalk(deadline).run(dir)

private class TreeWalk(
    private val deadline: TimeSource.Monotonic.ValueTimeMark?,
    private val sourcesOnly: Boolean = false,
    private val enough: (Long) -> Boolean = { false },
) {
    private var source = 0L
    private var cacheBytes = 0L
    private var modified: Long? = null
    private var incomplete = false
    private val caches = ArrayList<Cache>()

    fun run(dir: String): Pair<Usage, Boolean> {
        val cut = !walk(dir, true, null)
        return Usage(source, cacheBytes, modified, caches.toList(), incomplete) to cut
    }

    /** A read the walk could not make; a path gone mid-walk is no missing evidence. */
    private fun missed(
        cache: Int?,
        e: IoException?,
    ) {
        if (e?.kind == IoException.Kind.NotFound) return
        incomplete = true
        if (cache != null) caches[cache] = caches[cache].copy(incomplete = true)
    }

    /** False when the deadline or [enough] stopped the walk (`ControlFlow::Break`). */
    private fun walk(
        dir: String,
        root: Boolean,
        inCache: Int?,
    ): Boolean = deadline?.hasPassedNow() != true && visitDir(dir, root, inCache)

    @Suppress("ReturnCount")
    private fun visitDir(
        dir: String,
        root: Boolean,
        inCache: Int?,
    ): Boolean {
        // A nested checkout (another worktree, a submodule) is its own row.
        val names = read(inCache) { readDir(dir) }?.takeUnless { !root && ".git" in it && exists("$dir/.git") } ?: return true
        val tagged = inCache == null && "CACHEDIR.TAG" in names && isCacheDir(dir)
        if (tagged && sourcesOnly) return true
        val cache = inCache ?: tagRoot(dir, tagged)
        return names.all { entry("$dir/$it", cache) }
    }

    /** The index of a new cache root when [dir] carries a valid tag. */
    private fun tagRoot(
        dir: String,
        tagged: Boolean,
    ): Int? {
        if (!tagged) return null
        caches += Cache(dir)
        return caches.size - 1
    }

    @Suppress("ReturnCount")
    private fun entry(
        path: String,
        cache: Int?,
    ): Boolean {
        val meta = read(cache) { symlinkMeta(path) }
        when {
            meta == null -> {}

            meta.kind == EntryKind.Dir -> {
                return walk(path, false, cache)
            }

            else -> {
                add(cache, meta.len, meta.mtimeNanos)
                return !enough(meta.mtimeNanos)
            }
        }
        return true
    }

    private fun <T> read(
        cache: Int?,
        op: () -> T,
    ): T? =
        try {
            op()
        } catch (e: IoException) {
            missed(cache, e)
            null
        }

    private fun add(
        cache: Int?,
        len: Long,
        mtime: Long,
    ) {
        if (cache != null) {
            val c = caches[cache]
            caches[cache] = c.copy(bytes = c.bytes + len, modified = maxOf(c.modified ?: mtime, mtime))
            cacheBytes += len
        } else {
            source += len
        }
        modified = maxOf(modified ?: mtime, mtime)
    }
}
