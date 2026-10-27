// Port of src/worktree/remove.rs (T286, T453, T527): an agent removes its own finished worktree. [WorktreeRemove.detach]
// is the single-worktree removal `gc` applies too; [WorktreeRemove.run] refuses before it touches anything.
package rtok.worktree

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import rtok.RemovalPreview
import rtok.store.AgentDetail
import rtok.store.Store
import rtok.sys.canon
import rtok.sys.eprint
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.pathStartsWith
import rtok.sys.samePath

/** Who asks: the rtok agent id and the owner name an old lock carries — either may be unknown. */
data class Caller(
    val agent: String?,
    val owner: String?,
)

@Serializable
data class Removed(
    /** Canonical, as the claim row stores it. */
    val path: String,
    val branch: String?,
    val note: String,
    /** Set by a dry run: what the removal would free. Absent after a real removal. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val bytes: Long? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val files: Long? = null,
) {
    /** The dry-run text: the shared removal line and totals, then what happens to the branch. */
    fun previewText(): String = RemovalPreview.render(listOf(Triple(path, bytes ?: 0, files ?: 0))) + note + "\n"
}

object WorktreeRemove {
    /**
     * Unlock, remove without `--force`, then delete the branch when [dropBranch]. A failed removal puts the lock back. A
     * branch git will not delete is reported, not an error: the worktree is already gone.
     */
    @Suppress("ReturnCount")
    fun detach(
        repo: String,
        record: WorktreeRecord,
        dropBranch: Boolean,
    ): String {
        if (record.locked != null) Git.unlock(repo, record.path)
        try {
            Git.remove(repo, record.path)
        } catch (e: GitError) {
            record.locked?.let { Git.lock(repo, record.path, it) }
            throw e
        }
        val branch = record.branch?.takeIf { dropBranch } ?: return "removed; branch kept"
        try {
            Git.deleteBranch(repo, branch)
        } catch (e: GitError) {
            return "removed; branch kept: ${e.message}"
        }
        return if (Git.hasRemoteBranch(repo, branch)) {
            "removed with its branch; remote left: git push origin --delete $branch"
        } else {
            "removed with its branch"
        }
    }

    /**
     * Remove the worktree at [target] (a path, else a task id) for [who]. Refuses a dirty worktree, anyone else's lock
     * on one that is not done and whose holder is within gc's `--stale-lock` (T527), the one holding [cwd], and an
     * unmerged branch unless [keepBranch].
     */
    fun run(
        cwd: String,
        target: String,
        who: Caller,
        keepBranch: Boolean,
        store: Store?,
    ): Removed = removeOrPreview(cwd, target, who, keepBranch, false, store)

    /** [run] with every refusal and none of the effects: the size and file count it would free, and the branch's fate. */
    fun preview(
        cwd: String,
        target: String,
        who: Caller,
        keepBranch: Boolean,
        store: Store?,
    ): Removed = removeOrPreview(cwd, target, who, keepBranch, true, store)

    private data class Found(
        val entry: Entry,
        val main: String,
        val base: String,
    )

    private fun find(
        cwd: String,
        target: String,
    ): Found {
        val asPath = join(cwd, target)
        val named = exists(asPath)
        val from = if (named) asPath else cwd
        val base = Git.defaultBase(from)
        try {
            Git.fetch(from, base)
        } catch (e: GitError) {
            eprint("warning: ${e.message}; merged is judged against the local $base\n")
        }
        val entries = Worktrees.inventory(from)
        val main = entries.firstOrNull()?.record?.path ?: fail("git lists no worktree")
        val found =
            if (named) {
                entries.filter { samePath(canon(it.record.path), canon(asPath)) }
            } else {
                entries.filter { isTask(it.record, target) }
            }
        val entry =
            when (found.size) {
                1 -> found[0]
                0 -> fail("$target: no worktree with that path or task")
                else -> fail("$target: ${found.size} worktrees match; name the path")
            }
        return Found(entry, main, base)
    }

    @Suppress("LongParameterList")
    private fun removeOrPreview(
        cwd: String,
        target: String,
        who: Caller,
        keepBranch: Boolean,
        dryRun: Boolean,
        store: Store?,
    ): Removed {
        val (entry, main, base) = find(cwd, target)
        val record = entry.record
        val path = canon(record.path)
        if (entry.state == State.Main) fail("$path is the main checkout")
        if (pathStartsWith(canon(cwd), path)) fail("$path holds the current directory; run from the main checkout")
        // An unknown caller holds no lock: "" is never a parsed owner or agent (T527: a gone holder holds nothing).
        val held = Claim.heldAgainst(record, who.owner ?: "", who.agent ?: "", store)
        // T453: a finished task has nothing left to protect, so its lock does not hold.
        held?.takeIf { it.holds && !entry.done }?.let { fail("$path is ${it.locked()}; not removed") }
        refuseState(entry, path, base, keepBranch)
        val dropBranch = entry.merged && !keepBranch
        if (dryRun) {
            val (bytes, files) = RemovalPreview.sizeOf(path)
            val note =
                when {
                    record.branch == null -> "detached HEAD: no branch"
                    dropBranch -> "branch ${record.branch}: would be deleted (merged)"
                    else -> "branch ${record.branch}: would be kept"
                }
            return Removed(path, record.branch, note, bytes, files)
        }
        var note = detach(main, record, dropBranch)
        if (held != null && entry.done) note += "; finished, so the lock by ${held.by} was opened"
        return Removed(path, record.branch, note)
    }

    private fun refuseState(
        entry: Entry,
        path: String,
        base: String,
        keepBranch: Boolean,
    ) {
        val b = entry.record.branch
        when {
            entry.state == State.Stale -> {
                fail("$path is gone; `rtok worktree gc --yes` drops its record")
            }

            entry.state == State.Dirty -> {
                fail("$path has uncommitted or untracked files")
            }

            entry.state != State.Unmerged -> {}

            b == null -> {
                fail("$path: detached HEAD not merged into $base; its commits would be lost")
            }

            !keepBranch -> {
                fail(
                    "$path: $b is not merged into $base; check `gh pr view $b`, or pass " +
                        "--keep-branch (MCP: keep_branch) to remove the worktree and keep the branch",
                )
            }
        }
    }

    /**
     * `rtok worktree remove` and MCP `worktree_remove`: [run] for [agent] (named by [ownerFlag] when it gives one), then
     * release the claim. With neither an agent nor an owner only an unlocked worktree goes.
     */
    fun forAgent(
        store: Store?,
        cwd: String,
        target: String,
        agent: AgentDetail?,
        ownerFlag: String?,
        keepBranch: Boolean,
    ): Removed {
        val who = Caller(agent?.id, callerOwner(store, ownerFlag, agent))
        val done = run(cwd, target, who, keepBranch, store)
        if (store != null) context({ "${done.path} was removed" }) { ClaimStore.release(store, done.path) }
        return done
    }

    /** [forAgent] that only previews: the same refusals, no removal and no claim release. */
    fun previewForAgent(
        store: Store?,
        cwd: String,
        target: String,
        agent: AgentDetail?,
        ownerFlag: String?,
        keepBranch: Boolean,
    ): Removed = preview(cwd, target, Caller(agent?.id, callerOwner(store, ownerFlag, agent)), keepBranch, store)

    private fun callerOwner(
        store: Store?,
        ownerFlag: String?,
        agent: AgentDetail?,
    ): String? = if (ownerFlag == null && agent == null) null else Claim.owner(ownerFlag, agent, store)

    /** A task id names the lock's task or the branch `<task>[-<slug>]`, case aside. */
    internal fun isTask(
        record: WorktreeRecord,
        task: String,
    ): Boolean {
        val t = asciiLower(task)
        val locked = record.owner()?.task?.let(::asciiLower) == t
        val branch = record.branch?.let(::asciiLower)
        return locked || (branch != null && (branch == t || branch.startsWith("$t-")))
    }

    /** `to_ascii_lowercase`: only A–Z fold. */
    private fun asciiLower(s: String): String = buildString { s.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }
}
