// Port of src/model/mod.rs `worktrees_page_text` (T232): the `rtok worktree list` table as the Worktrees page,
// read off the tick with its own slow cache (`WORKTREES_TTL`): the walk takes tens of seconds in a built checkout.
package rtok.model

import rtok.sys.cwd
import rtok.worktree.WorktreeList
import kotlin.time.Duration.Companion.seconds

object WorktreesPage {
    /** `WORKTREES_TTL`: how long a worktrees read (and the junk walk) may be reused. */
    val TTL = 300.seconds

    const val READING = "reading worktrees…\n"

    const val NOT_A_REPO = "not a git repository\n"

    private val cache = Background<String>("web-worktrees")

    /** The last table, or [READING] before the first read lands; outside a repository the page says so. */
    fun text(
        read: () -> String = ::read,
        from: Background<String> = cache,
    ): String = from.get(TTL, read) ?: READING

    internal fun read(): String = runCatching { WorktreeList.toTable(WorktreeList.rows(cwd()), rtok.log.now()) }.getOrElse { NOT_A_REPO }

    internal fun forget() = cache.forget()
}
