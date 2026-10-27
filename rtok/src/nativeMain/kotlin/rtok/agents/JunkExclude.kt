// Port of src/agents/junk_review.rs `exclude`: the `[agents.junk] exclude` globs (globset semantics via :globset).
package rtok.agents

import globset.Glob
import globset.GlobError
import globset.GlobSet
import rtok.hook.expandWith
import rtok.sys.EntryKind
import rtok.sys.isDir
import rtok.sys.listDirOrEmpty
import rtok.sys.symlinkMeta
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Whether anything under the directory [dir] matches [set]. A walk cut by [deadline] answers yes. */
private fun holdsMatch(
    dir: String,
    set: GlobSet,
    deadline: TimeSource.Monotonic.ValueTimeMark,
): Boolean {
    val stack = arrayListOf(dir)
    var found = false
    while (!found && stack.isNotEmpty()) {
        val d = stack.removeLast()
        found = deadline.hasPassedNow() ||
            listDirOrEmpty(d).any { name ->
                val p = join(d, name)
                if (runCatching { symlinkMeta(p).kind == EntryKind.Dir }.getOrDefault(false)) stack += p
                set.isMatch(p)
            }
    }
    return found
}

/**
 * Keeps every item `[agents.junk] exclude` matches, and every folder that holds a match: a cache emptied as a whole
 * would take the excluded file with it. A glob that does not compile keeps every item, so protection that cannot be
 * checked is never dropped.
 */
fun exclude(
    items: MutableList<JunkItem>,
    globs: List<String>,
    rtokHome: String,
    home: String,
    limit: Duration,
) {
    if (globs.isEmpty()) return
    val set =
        try {
            Result.success(GlobSet(globs.map { Glob.new(expandWith(it, rtokHome, home)) }))
        } catch (e: GlobError) {
            Result.failure(e)
        }
    for (idx in items.indices) {
        val i = items[idx]
        if (!i.counted) continue
        val hit =
            set.fold(
                { s -> s.isMatch(i.path) || (isDir(i.path) && !isSymlink(i.path) && holdsMatch(i.path, s, TimeSource.Monotonic.markNow() + limit)) },
                { true },
            )
        if (hit) {
            items[idx] =
                i.copy(kept = set.fold({ JunkReview.EXCLUDED }, { e -> "invalid [agents.junk] exclude, nothing cleared: ${e.message}" }))
        }
    }
}
