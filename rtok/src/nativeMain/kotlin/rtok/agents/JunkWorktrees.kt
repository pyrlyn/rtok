// Port of src/agents/junk_worktrees.rs: the `stale-worktrees` junk kind (T330.5.3, decided in T341). A worktree is junk
// exactly when `rtok worktree gc` would remove it, and `clear` removes it through gc's own per-record
// `WorktreeRemove.detach`. No second verdict and no blanket `git worktree prune` (T153): an orphan or a record whose
// directory is gone is listed with gc's wording and never removed here. gc's override verdicts (an abandoned foreign
// lock, a finished task a live agent or a lock holds) are listed and left to `rtok worktree gc`.
package rtok.agents

import rtok.config.Config
import rtok.config.HOSTS
import rtok.sys.canon
import rtok.sys.cwd
import rtok.sys.diskUsageUntil
import rtok.sys.samePath
import rtok.worktree.Gc
import rtok.worktree.GcPolicy
import rtok.worktree.Judged
import rtok.worktree.Ops
import rtok.worktree.State
import rtok.worktree.Verdict
import rtok.worktree.WorktreeRemove
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A linked worktree of the repository and the host that made it, when known. [state] `stale`: the directory is gone. */
data class JunkWorktree(
    val host: String?,
    val path: String,
    val state: String,
)

object JunkWorktrees {
    const val KIND = "stale-worktrees"

    private const val DAY_SECONDS = 86_400L

    /**
     * Every linked worktree of the repository [cwd] is in, orphans included, with the host the store, the lock or the
     * host's own pool (T289) names. Without a repository or a store the list is short, never an error.
     */
    fun worktrees(
        cfg: Config,
        cwd: String,
    ): List<JunkWorktree> {
        val rows = runCatching { Ops.list(cfg, cwd, false) }.getOrNull() ?: return emptyList()
        return rows.filter { it.state != "main" }.map { r ->
            val host = r.agent?.host ?: r.session?.host ?: r.origin
            JunkWorktree(HOSTS.firstOrNull { it == host }, r.path, r.state)
        }
    }

    /** junk_kinds.rs `agent_worktrees`: the host and path of every agent worktree of the repository [cwd] is in. */
    fun agentWorktrees(
        cfg: Config,
        cwd: String,
    ): List<Pair<String, String>> = inHosts(worktrees(cfg, cwd))

    /** junk_kinds.rs `in_hosts`: the worktrees [agentWorktrees] keeps, a host is known and git lists the directory. */
    fun inHosts(worktrees: List<JunkWorktree>): List<Pair<String, String>> = worktrees.filter { it.state != "stale" && it.state != "orphan" }.mapNotNull { w -> w.host?.let { it to w.path } }

    /** The gc policy junk judges by: the idle window is `stale_worktree_days` and no lock is "ours". */
    internal fun policy(
        cfg: Config,
        now: Long,
    ): GcPolicy {
        val idle =
            (
                cfg.agents.junk.staleWorktreeDays
                    .toLong() * DAY_SECONDS
            ).seconds
        return GcPolicy(null, idle, idle, now, Ops.liveIds(cfg), Ops.endedIds(cfg), Ops.pendingPaths(cfg))
    }

    private fun item(
        path: String,
        limit: Duration,
        kept: String?,
    ) = JunkItem(KIND, "review", path, diskUsageUntil(path, TimeSource.Monotonic.markNow() + limit).bytes, JunkCache.GC_VERDICT, kept)

    /**
     * One item per non-main worktree, under the host that made it, else under `rtok`. A removal verdict is a counted
     * `review` item; every other verdict is listed with gc's reason. [now] is Unix nanoseconds.
     */
    fun items(
        cfg: Config,
        cwd: String,
        owners: List<JunkWorktree>,
        now: Long,
        limit: Duration,
    ): List<Pair<String, JunkItem>> {
        val judged = runCatching { Gc.judge(cwd, policy(cfg, now)) }.getOrNull()?.second ?: return emptyList()
        val same = { a: String, b: String -> samePath(canon(a), canon(b)) }
        val listed =
            judged.filter { it.entry.state != State.Main }.map { (entry, verdict) ->
                val path = entry.record.path
                val host = owners.firstOrNull { same(it.path, path) }
                val note = verdict.plan().second
                val kept =
                    when (verdict) {
                        Verdict.Remove -> null

                        // gc drops such a record itself; junk never does (T341).
                        Verdict.DropRecord -> "$note: only `rtok worktree gc` drops its record"

                        is Verdict.Reclaim, is Verdict.Finished -> "$note: left to `rtok worktree gc`"

                        is Verdict.Keep -> note
                    }
                (host?.host ?: "rtok") to item(path, limit, kept)
            }
        val orphans =
            owners.filter { it.state == "orphan" }.map { w ->
                (w.host ?: "rtok") to item(w.path, limit, "orphan: git no longer lists it; check it and delete the folder yourself")
            }
        return listed + orphans
    }

    /** gc's verdicts for `clear`, taken once per run and only if a worktree is actually removed. */
    class Remover {
        private var taken = false
        private var judged: Pair<String, List<Judged>>? = null

        /**
         * Removes the worktree at [path] if gc still would, one record at a time, with its merged branch. A failure is
         * why it stayed.
         */
        @Suppress("ReturnCount")
        fun remove(
            cfg: Config,
            now: Long,
            path: String,
        ): Result<String> {
            if (!taken) {
                taken = true
                judged = runCatching { Gc.judge(cwd(), policy(cfg, now)) }.getOrNull()
            }
            val (repo, all) = judged ?: return Result.failure(IllegalStateException("cannot judge the worktrees: not inside their repository"))
            val target = canon(path)
            val found =
                all.firstOrNull { samePath(canon(it.entry.record.path), target) }
                    ?: return Result.failure(IllegalStateException("no longer a worktree of this repository"))
            return when (found.verdict) {
                Verdict.Remove -> {
                    runCatching { WorktreeRemove.detach(repo, found.entry.record, found.entry.merged) }
                        .recoverCatching { throw IllegalStateException("failed, kept: ${it.message}") }
                }

                else -> {
                    Result.failure(IllegalStateException("not removable now: ${found.verdict.plan().second}"))
                }
            }
        }
    }
}
