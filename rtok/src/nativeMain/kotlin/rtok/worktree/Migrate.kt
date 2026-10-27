// Port of src/worktree/migrate.rs (T660.5): `rtok worktree migrate` moves this repository's worktrees that live outside
// the configured root into it, as `<root>/<repo>-<task>`, with `git worktree move`. Lock and claim follow the worktree.
// [Migrate.plan] decides from paths and records; [Migrate.run] acts only with `yes`.
package rtok.worktree

import kotlinx.serialization.Serializable
import rtok.Col
import rtok.config.resolved
import rtok.store.Store
import rtok.store.moveWorktreeRows
import rtok.sys.canon
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.samePath

private const val SHORT_ID = 8

/** Who runs the command: a worktree locked or claimed by anyone else is not moved. */
data class MigrateMe(
    val agent: String? = null,
    /** `--owner`: matches an old lock that names no agent. */
    val owner: String? = null,
)

/** What else holds worktrees, read from the store. */
data class MigrateHeld(
    /** `(path, agent)` of every open claim. */
    val claims: List<Pair<String, String>> = emptyList(),
    /** Worktrees a post-create script parked before any agent took them. */
    val pending: List<String> = emptyList(),
    /** `(agent, cwd)` of every live agent. */
    val live: List<Pair<String, String>> = emptyList(),
)

@Serializable
data class MigrateOutcome(
    val path: String,
    /** Where it goes; null when kept. */
    val to: String?,
    /** `move` or `keep`. */
    val action: String,
    /** Why it was kept, or git's message when the move failed. */
    val note: String,
    val failed: Boolean,
)

/** One worktree and where it would go ([to]), or why it stays ([why]). */
data class MigrateStep(
    val record: WorktreeRecord,
    val to: String?,
    val why: String?,
)

object Migrate {
    /** Every worktree of the repository [cwd] is in, with its target under [root] or the reason it stays. */
    fun plan(
        cwd: String,
        root: String,
        me: MigrateMe,
        held: MigrateHeld,
    ): Pair<String, List<MigrateStep>> {
        val records = Git.list(cwd)
        val main = records.firstOrNull()?.path ?: fail("git lists no worktree")
        val base = Add.landing(main, root)
        val current = Worktrees.deepest(canon(cwd), records.indices) { records[it].path }
        val taken = mutableSetOf<String>()
        val steps =
            records.mapIndexed { i, record ->
                val target = runCatching { target(record, i, current == i, main, base, me, held) }
                val to = target.getOrNull()
                when {
                    to == null -> MigrateStep(record, null, target.exceptionOrNull()?.message)

                    // Two worktrees of one task: the first takes the name, the second would fail.
                    !taken.add(to) -> MigrateStep(record, null, "$to is another worktree's target")

                    else -> MigrateStep(record, to, null)
                }
            }
        return main to steps
    }

    private fun keep(why: String): Nothing = fail(why)

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ThrowsCount")
    private fun target(
        r: WorktreeRecord,
        i: Int,
        current: Boolean,
        main: String,
        base: String,
        me: MigrateMe,
        held: MigrateHeld,
    ): String {
        if (i == 0) keep("main checkout")
        val dir = canon(r.path)
        if (pathStartsWith(dir, canon(base))) keep("already in the root")
        if (r.prunable != null || !isDir(r.path)) keep("directory is gone")
        if (current) keep("the worktree this command runs from")
        heldByAnother(r, dir, me, held)?.let(::keep)
        if (exists(join(r.path, ".gitmodules"))) keep("has submodules, which `git worktree move` refuses")
        // No task to name it by: it keeps its directory name.
        val name = r.owner()?.let { "${fileName(main).orEmpty()}-${it.task}" } ?: fileName(r.path).orEmpty()
        val to = join(base, name)
        if (exists(to)) keep("$to already exists")
        return to
    }

    /** Why someone other than [me] holds the worktree at [dir] (canonical), if anyone does. */
    @Suppress("ReturnCount")
    private fun heldByAnother(
        r: WorktreeRecord,
        dir: String,
        me: MigrateMe,
        held: MigrateHeld,
    ): String? {
        if (r.locked != null) {
            val owner = r.owner() ?: return "locked, owner unknown"
            val mine = owner.agent?.let { me.agent == it } ?: (me.owner == owner.owner)
            if (!mine) return "locked by ${owner.owner}"
        }
        val at = { p: String -> samePath(canon(p), dir) }
        held.claims.firstOrNull { (p, a) -> at(p) && me.agent != a }?.let { return "claimed by agent ${it.second.take(SHORT_ID)}" }
        if (held.pending.any(at)) return "pending claim, no agent has taken it"
        return held.live
            .firstOrNull { (a, cwd) -> me.agent != a && pathStartsWith(canon(cwd), dir) }
            ?.let { "live session of agent ${it.first.take(SHORT_ID)} works in it" }
    }

    /**
     * [plan], then with [yes] the moves: unlock, `git worktree move`, lock again with the same reason, and the store's
     * rows follow. A failed move is re-locked where it was.
     */
    @Suppress("LongParameterList")
    fun run(
        cwd: String,
        root: String,
        me: MigrateMe,
        held: MigrateHeld,
        store: Store?,
        yes: Boolean,
    ): List<MigrateOutcome> {
        val (main, steps) = plan(cwd, root, me, held)
        return steps.map { (record, to, why) ->
            if (to == null) return@map MigrateOutcome(record.path, null, "keep", why.orEmpty(), false)
            val done = if (yes) runCatching { apply(main, record, to, store) } else null
            MigrateOutcome(
                path = record.path,
                to = to,
                action = "move",
                note =
                    when {
                        done == null -> "outside the root"
                        done.isSuccess -> "moved"
                        else -> "failed, kept: ${done.exceptionOrNull()?.message}"
                    },
                failed = done?.isFailure == true,
            )
        }
    }

    private fun apply(
        main: String,
        record: WorktreeRecord,
        to: String,
        store: Store?,
    ) {
        if (pathStartsWith(resolved(to), canon(main))) Add.ensureIgnored(main)
        parent(to)?.let { p -> runCatching { createDirAll(p) }.getOrElse { fail("cannot create $p: ${it.message}") } }
        val from = record.path
        // Canonical before the move: afterwards `from` no longer exists to resolve.
        val old = canon(from)
        val reason = record.locked
        if (reason != null) {
            Git.unlock(main, from)
            try {
                Git.moveTo(main, from, to)
            } catch (e: GitError) {
                // Put the lock back: an unlocked worktree is one `gc` may take.
                Git.lock(main, from, reason)
                throw e
            }
            Git.lock(main, to, reason)
        } else {
            Git.moveTo(main, from, to)
        }
        store?.moveWorktreeRows(old, canon(to))
    }

    fun toTable(
        outcomes: List<MigrateOutcome>,
        yes: Boolean,
    ): String {
        val lines = listOf(listOf("action", "path", "to")) + outcomes.map { listOf(it.action, it.path, it.to ?: "-") }
        val cols = listOf(Col.left(0), Col.left(0), Col.left(0))
        val out = StringBuilder(notedTable(cols, lines, outcomes.map { it.note }))
        val planned = outcomes.count { it.action == "move" }
        if (!yes && planned > 0) out.append("\ndry run: $planned to move, nothing changed; rerun with --yes\n")
        return out.toString()
    }
}
