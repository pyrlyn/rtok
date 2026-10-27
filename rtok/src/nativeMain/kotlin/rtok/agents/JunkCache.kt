// Port of src/agents/junk_cache.rs: the `cache` junk kind. A path is cache only with evidence (a research.md §22 row,
// a valid `CACHEDIR.TAG`, or rtok's own say-so); a tagged directory is judged by `rtok worktree clean`'s rules.
package rtok.agents

import rtok.sys.EntryKind
import rtok.sys.canon
import rtok.sys.diskUsageUntil
import rtok.sys.entryKind
import rtok.sys.isDir
import rtok.sys.pathStartsWith
import rtok.worktree.Cache
import rtok.worktree.Policy
import rtok.worktree.keptBecause
import rtok.worktree.usageUntil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

object JunkCache {
    /** T152's default idle window for a tagged cache. */
    val DEFAULT_IDLE: Duration = 24.hours

    const val SECTION_22 = "research.md §22"
    const val TAG = "CACHEDIR.TAG"
    const val RTOK_OWN = "rtok-owned"

    /** `rtok worktree gc` would remove it: its verdict is the evidence. */
    const val GC_VERDICT = "gc verdict"

    /** src/disk_usage.rs `AGENT_SCAN_LIMIT`: how long one agent's folders may be walked. */
    val AGENT_SCAN_LIMIT: Duration = 10.seconds

    /** A walk that hit its deadline: its newest mtime is a lower bound, so it never reads as idle. */
    const val SCAN_STOPPED = "scan stopped: not cleared"
}

/** One directory `clear` may empty as cache; [kept] says why `clear` leaves it for now (counted in no "Freed"). */
data class JunkItem(
    val kind: String,
    val cls: String,
    val path: String,
    val bytes: Long,
    val evidence: String,
    val kept: String? = null,
) {
    val counted: Boolean get() = kept == null
}

/** A directory that is cache without a tag; [idleRule] applies T152's idle rule (a tool may be using it now). */
data class OwnedCache(
    val path: String,
    val evidence: String,
    val idleRule: Boolean,
)

/** What the rules need about this run; [cwd] is canonical so a symlinked checkout is still the current worktree. */
class JunkCtx(
    cwd: String,
    idle: Duration,
    now: Long,
) {
    val cwd: String = canon(cwd)
    val policy = Policy(idle, now)
}

internal fun isSymlink(p: String) = entryKind(p) == EntryKind.Symlink

/** An item of [kind] sized within [limit] (a huge directory is a lower bound, not a hang). */
internal fun junkItem(
    kind: String,
    path: String,
    evidence: String,
    kept: String?,
    limit: Duration,
) = JunkItem(kind, "safe", path, diskUsageUntil(path, TimeSource.Monotonic.markNow() + limit).bytes, evidence, kept)

/** T152's idle answer for one directory, or [JunkCache.SCAN_STOPPED] when the walk did not finish. */
private fun idleKeep(
    path: String,
    cx: JunkCtx,
    limit: Duration,
): String? {
    val (u, cut) = usageUntil(path, TimeSource.Monotonic.markNow() + limit)
    if (cut) return JunkCache.SCAN_STOPPED
    return keptBecause(Cache(path, 0, u.modified, u.incomplete), false, false, cx.policy)
}

/**
 * The cache items under one agent: its [owned] directories plus every tagged directory below [roots]. Each size and
 * walk gets [limit]. A directory inside a counted one is dropped, so a byte is never listed twice.
 */
fun cacheItems(
    owned: List<OwnedCache>,
    roots: List<String>,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> {
    val items = ArrayList<JunkItem>()
    for (o in owned) {
        // A link out of the folder is never followed; `list` still shows its target.
        if (isSymlink(o.path) || !isDir(o.path)) continue
        val kept = if (o.idleRule) idleKeep(o.path, cx, limit) else null
        items += junkItem("cache", o.path, o.evidence, kept, limit)
    }
    for (root in roots.filter(::isDir)) {
        val (found, cut) = usageUntil(root, TimeSource.Monotonic.markNow() + limit)
        val current = pathStartsWith(cx.cwd, canon(root))
        for (cache in found.caches) {
            val kept = if (cut) JunkCache.SCAN_STOPPED else keptBecause(cache, current, false, cx.policy)
            items += junkItem("cache", cache.path, JunkCache.TAG, kept, limit)
        }
    }
    return dropNested(items)
}

/** One row per path (the first evidence wins), none inside a counted path; sorted by path. */
internal fun dropNested(items: List<JunkItem>): List<JunkItem> {
    val unique = items.sortedBy { it.path }.distinctBy { it.path }
    val counted = unique.filter { it.counted }.map { it.path }
    return unique.filter { i -> counted.none { c -> c != i.path && pathStartsWith(i.path, c) } }
}
