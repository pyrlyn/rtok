// Port of src/agents/junk_clear.rs, the parts other junk kinds use so far (`newest`). The plan and the deleting run
// come with `rtok agents junk clear`.
package rtok.agents

import rtok.sys.EntryKind
import rtok.sys.symlinkMeta
import rtok.worktree.usageUntil
import kotlin.time.TimeSource

/**
 * The newest file mtime under [path] in nanoseconds (T152's measure: a folder's own mtime is not use). [Newest.time] is
 * null for a folder without files; the whole answer is null when [path] is unreadable or the walk did not finish, so an
 * unknown age never reads as old.
 */
internal fun newest(path: String): Newest? {
    val meta = runCatching { symlinkMeta(path) }.getOrNull()
    return when {
        meta == null -> {
            null
        }

        meta.kind != EntryKind.Dir -> {
            Newest(meta.mtimeNanos)
        }

        else -> {
            usageUntil(path, TimeSource.Monotonic.markNow() + JunkCache.AGENT_SCAN_LIMIT).let { (u, cut) ->
                if (cut) null else Newest(u.modified)
            }
        }
    }
}

/** Rust's inner `Option<SystemTime>` of `newest`. */
internal data class Newest(
    val time: Long?,
)
