// Port of the pure half of src/worktree/clean.rs (`Policy`, `kept_because`, `AGE_UNKNOWN`): whether a tagged cache
// may go. The deleting `run` comes with `rtok worktree clean`.
package rtok.worktree

import kotlin.time.Duration

/** The idle window and "now" (nanoseconds since the epoch) a run judges against. */
data class Policy(
    val idle: Duration,
    val now: Long,
)

/** Why a cache or worktree stays when its newest mtime could not be established. */
const val AGE_UNKNOWN = "age unknown: a file or its mtime could not be read"

const val KEPT_CURRENT = "the worktree this command runs from; name its path to clean it"
const val KEPT_IDLE = "modified within the idle window"

/**
 * Why [cache] stays, or null when it goes. [current]: it sits in the worktree this command runs from; [explicit]: its
 * worktree was named on the command line. Missing evidence of age keeps it, since deletion cannot be undone.
 */
fun keptBecause(
    cache: Cache,
    current: Boolean,
    explicit: Boolean,
    p: Policy,
): String? {
    val m = cache.modified
    return when {
        current && !explicit -> KEPT_CURRENT
        m == null -> AGE_UNKNOWN
        (p.now - m).coerceAtLeast(0) < p.idle.inWholeNanoseconds -> KEPT_IDLE
        cache.incomplete -> AGE_UNKNOWN
        else -> null
    }
}
