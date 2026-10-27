// Port of the deleting half of src/worktree/clean.rs (`targets`, `run`, `preview`, `to_table`): `rtok worktree clean`
// deletes idle tagged build caches (`CACHEDIR.TAG`) and keeps the worktrees. The pure half is in Policy.kt.
package rtok.worktree

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.Col
import rtok.RemovalPreview
import rtok.agents.SessionsTable
import rtok.sys.ChangePreview.humanBytes
import rtok.sys.canon
import rtok.sys.removeDirAll
import rtok.sys.samePath
import rtok.sys.stripPrefix

private const val NANOS = 1_000_000_000L

@Serializable
data class CleanOutcome(
    val worktree: String,
    /** Relative to [worktree]. */
    val cache: String,
    val bytes: Long,
    val files: Long,
    @SerialName("modified_unix") val modifiedUnix: Long?,
    /** `clean` or `keep`. */
    val action: String,
    val note: String,
    val failed: Boolean,
)

object Clean {
    /** Every worktree and orphan of the repository [cwd] is in, or only [paths], each of which must be one of them. */
    private fun targets(
        cwd: String,
        paths: List<String>,
    ): List<String> {
        val listed = Git.list(cwd).map { it.path }
        val dirs = listed + WorktreeList.orphans(listed)
        if (paths.isEmpty()) return dirs
        val known = dirs.map(::canon)
        for (p in paths) {
            if (known.none { samePath(it, canon(p)) }) fail("$p is not a worktree of this repository")
        }
        return paths
    }

    /** Judge every tagged cache under the targets by [policy]; with [yes] delete the ones that go. */
    fun run(
        cwd: String,
        paths: List<String>,
        policy: Policy,
        yes: Boolean,
    ): List<CleanOutcome> {
        val here = canon(cwd)
        val explicit = paths.isNotEmpty()
        val dirs = targets(cwd, paths)
        val currentAt = Worktrees.deepest(here, dirs.indices) { dirs[it] }
        val found = Worktrees.parMap(dirs) { _, dir -> usage(dir).caches }
        val judged =
            dirs.zip(found).flatMapIndexed { i, (dir, caches) ->
                caches.map { Triple(dir, it, keptBecause(it, currentAt == i, explicit, policy)) }
            }
        return judged.map { (dir, cache, kept) ->
            val relative = stripPrefix(cache.path, dir) ?: cache.path
            val files = if (kept == null) RemovalPreview.sizeOf(cache.path).second else 0L
            val done =
                if (yes && kept == null) {
                    runCatching { removeDirAll(cache.path) }.recoverCatching { throw WorktreeException("cannot delete ${cache.path}: ${it.message}") }
                } else {
                    null
                }
            CleanOutcome(
                worktree = dir,
                cache = relative,
                bytes = cache.bytes,
                files = files,
                modifiedUnix = cache.modified?.let { it / NANOS },
                action = if (kept != null) "keep" else "clean",
                note =
                    when {
                        kept != null -> kept
                        done == null -> "tagged cache, idle"
                        done.isSuccess -> "deleted"
                        else -> "failed, kept: ${done.exceptionOrNull()?.message}"
                    },
                failed = done?.isFailure == true,
            )
        }
    }

    private fun preview(outcomes: List<CleanOutcome>): String =
        RemovalPreview.render(
            outcomes.filter { it.action == "clean" && !it.failed }.map { Triple("${it.worktree}/${it.cache}", it.bytes, it.files) },
        )

    /** [now] is in seconds since the epoch. */
    fun toTable(
        outcomes: List<CleanOutcome>,
        yes: Boolean,
        now: Long,
    ): String {
        val head = listOf("action", "worktree", "cache", "modified", "bytes")
        val lines =
            listOf(head) +
                outcomes.map { o ->
                    val age = o.modifiedUnix?.let { SessionsTable.duration(now - it) } ?: "-"
                    listOf(o.action, o.worktree, o.cache, age, humanBytes(o.bytes))
                }
        val cols = listOf(Col.left(0), Col.left(0), Col.left(0), Col.left(0), Col.right(0))
        val out = StringBuilder(notedTable(cols, lines, outcomes.map { it.note }))
        val cleaned = outcomes.filter { it.action == "clean" && !it.failed }
        val n = cleaned.size
        val bytes = cleaned.sumOf { it.bytes }
        val planned = preview(outcomes)
        if (!yes && planned.isNotEmpty()) out.append('\n').append(planned)
        out.append(
            when {
                !yes && n == 0 -> "\nnothing to clean\n"
                !yes -> "\ndry run: $n caches, ${humanBytes(bytes)} to free, nothing changed; rerun with --yes\n"
                else -> "\nfreed ${humanBytes(bytes)} in $n caches (logical bytes)\n"
            },
        )
        return out.toString()
    }
}
