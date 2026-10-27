// Port of src/worktree/git.rs (the only place the worktree inventory spawns git) and mod.rs `parse_porcelain`.
package rtok.worktree

import rtok.sys.Captured
import rtok.sys.ProgressRunner
import rtok.sys.fileName
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString

/** One `git worktree list --porcelain` record. */
data class WorktreeRecord(
    val path: String,
    val head: String? = null,
    /** Short branch name; null when detached or bare. */
    val branch: String? = null,
    val bare: Boolean = false,
    val detached: Boolean = false,
    /** The lock reason; `""` is a lock without one. */
    val locked: String? = null,
    val prunable: String? = null,
) {
    fun owner(): Owner? = locked?.let(Owner::parse)

    /** Locked by someone other than [me]. A lock without a parsable reason holds against everyone. */
    fun heldAgainst(me: String?): Boolean = locked != null && owner().let { it == null || it.owner != me }

    /** `rtok worktree claim` (T285) may rewrite this lock: none, this agent's, or (no agent on it) this owner's. */
    fun claimableBy(
        owner: String,
        agent: String,
    ): Boolean = locked == null || owner()?.let { o -> o.agent?.let { it == agent } ?: (o.owner == owner) } == true
}

/** A git call that failed: it did not start, or exited non-zero (git's own message). */
class GitError(
    message: String,
) : RuntimeException(message)

@Suppress("TooManyFunctions")
object Git {
    private const val CAP = 64L shl 20

    /** `XY ` before each `status --porcelain` path. */
    private const val XY = 3

    /** [label] is the spinner text of a call a person waits for; null is a quick read. */
    private fun spawn(
        dir: String,
        label: String?,
        args: List<String>,
    ): Captured {
        val runner = ProgressRunner(label.orEmpty(), "git", listOf("-C", dir) + args)
        if (label == null) runner.quiet()
        return runCatching { runner.runCapped(CAP).first }.getOrElse { throw GitError("cannot run git ${args.joinToString(" ")}: ${it.message}") }
    }

    private fun git(
        dir: String,
        vararg args: String,
    ) = spawn(dir, null, args.toList())

    private fun checked(
        out: Captured,
        args: List<String>,
    ): Captured {
        if (out.exitCode != 0) throw GitError("git ${args.joinToString(" ")}: ${out.stderr.decodeToString().trim()}")
        return out
    }

    internal fun gitOk(
        dir: String,
        vararg args: String,
    ) = checked(git(dir, *args), args.toList())

    /** [gitOk] with a spinner: a fetch or a checkout can take seconds on a large repository. */
    internal fun gitSlow(
        dir: String,
        label: String,
        vararg args: String,
    ) = checked(spawn(dir, label, args.toList()), args.toList())

    private fun stdout(
        dir: String,
        vararg args: String,
    ) = gitOk(dir, *args).stdout.decodeToString().trim()

    private fun succeeds(
        dir: String,
        vararg args: String,
    ) = runCatching { git(dir, *args).exitCode == 0 }.getOrDefault(false)

    /** NUL-separated attributes; a `worktree <path>` line opens a record, unknown keys are skipped. */
    fun parsePorcelain(out: ByteArray): List<WorktreeRecord> {
        val records = ArrayList<WorktreeRecord>()
        for (attr in out.decodeToString().split('\u0000')) {
            val sp = attr.indexOf(' ')
            val key = if (sp < 0) attr else attr.substring(0, sp)
            val value = if (sp < 0) "" else attr.substring(sp + 1)
            if (key == "worktree") {
                records += WorktreeRecord(value)
                continue
            }
            val r = records.lastOrNull() ?: continue
            records[records.size - 1] =
                when (key) {
                    "HEAD" -> r.copy(head = value)
                    "branch" -> r.copy(branch = value.removePrefix("refs/heads/"))
                    "bare" -> r.copy(bare = true)
                    "detached" -> r.copy(detached = true)
                    "locked" -> r.copy(locked = value)
                    "prunable" -> r.copy(prunable = value)
                    else -> r
                }
        }
        return records
    }

    /** The worktrees of [repo]'s repository; fails when git is missing or [repo] is not a checkout. */
    fun list(repo: String): List<WorktreeRecord> = parsePorcelain(gitOk(repo, "worktree", "list", "--porcelain", "-z").stdout)

    /** Modified, staged or untracked files; ignored files (build output) do not count. */
    fun isDirty(worktree: String): Boolean = stdout(worktree, "status", "--porcelain").isNotEmpty()

    /** Whether git ignores [path] (relative to [worktree]). */
    fun isIgnored(
        worktree: String,
        path: String,
    ): Boolean = succeeds(worktree, "check-ignore", "-q", "--", path)

    /** The remote's default branch, `origin/main` when `origin/HEAD` is not set. */
    fun defaultBase(repo: String): String =
        runCatching { stdout(repo, "symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "origin/main"

    /** The tree [rev] points at. */
    fun tree(
        repo: String,
        rev: String,
    ): String = stdout(repo, "rev-parse", "$rev^{tree}")

    /**
     * Squash-aware: [rev] is merged when merging it into [base] would produce exactly [base]'s tree ([baseTree], resolved
     * once per inventory). A conflict falls back to the patch-equivalence check.
     */
    fun isMerged(
        repo: String,
        base: String,
        baseTree: String?,
        rev: String,
    ): Boolean {
        val merge = git(repo, "merge-tree", "--write-tree", base, rev)
        return when (merge.exitCode) {
            0 -> {
                merge.stdout
                    .decodeToString()
                    .lineSequence()
                    .firstOrNull() == (baseTree ?: throw GitError("no tree for $base"))
            }

            1 -> {
                isPatchEquivalent(repo, base, rev)
            }

            else -> {
                throw GitError("git merge-tree: ${merge.stderr.decodeToString().trim()}")
            }
        }
    }

    /** The branch's whole diff as one commit already has a patch-equivalent commit in [base] (a squash merge edited around). */
    private fun isPatchEquivalent(
        repo: String,
        base: String,
        rev: String,
    ): Boolean {
        val fork = stdout(repo, "merge-base", base, rev)
        // An identity of its own: `commit-tree` fails where `user.name` is not configured.
        val squashed =
            stdout(repo, "-c", "user.name=rtok", "-c", "user.email=rtok@localhost", "commit-tree", "$rev^{tree}", "-p", fork, "-m", "squash")
        return stdout(repo, "cherry", base, squashed).startsWith('-')
    }

    /** [rev] has commits [base] does not reach: never a fresh branch still at the base. */
    fun hasOwnCommits(
        repo: String,
        base: String,
        rev: String,
    ): Boolean = (stdout(repo, "rev-list", "--count", "$base..$rev").toULongOrNull() ?: throw GitError("git rev-list --count")) > 0u

    fun lock(
        repo: String,
        worktree: String,
        reason: String,
    ) {
        gitOk(repo, "worktree", "lock", "--reason", reason, worktree)
    }

    fun unlock(
        repo: String,
        worktree: String,
    ) {
        gitOk(repo, "worktree", "unlock", worktree)
    }

    /**
     * T958: release the lock that names [agent] on the worktree [cwd] is in; a lock that names someone else, or no
     * agent, stays. A missing checkout or lock is not an error: SessionEnd fails open.
     */
    fun unlockEnded(
        cwd: String,
        agent: String,
    ) {
        val root = Worktrees.lockedBy(cwd, agent) ?: return
        val admin = Worktrees.checkoutOf(root)?.second ?: return
        // A linked worktree's `commondir` points at the main `.git`; the main checkout's own lock has none.
        val repo =
            runCatching { readToString(join(admin, "commondir")) }.getOrNull()?.let { parent(join(admin, it.trim())) } ?: root
        unlock(repo, root)
    }

    /** `git worktree move`, never forced. */
    fun moveTo(
        repo: String,
        from: String,
        to: String,
    ) {
        gitSlow(repo, "moving worktree ${fileName(from).orEmpty()}…", "worktree", "move", from, to)
    }

    /** Never `--force`: git refuses a worktree with modified or untracked files. */
    fun remove(
        repo: String,
        worktree: String,
    ) {
        gitSlow(repo, "removing worktree ${fileName(worktree).orEmpty()}…", "worktree", "remove", worktree)
    }

    /** `-D`: the caller has established "merged" squash-aware, which `-d` cannot see. */
    fun deleteBranch(
        repo: String,
        branch: String,
    ) {
        gitOk(repo, "branch", "-D", "--", branch)
    }

    fun hasBranch(
        repo: String,
        branch: String,
    ): Boolean = succeeds(repo, "show-ref", "--verify", "-q", "refs/heads/$branch")

    /** Drops the records of worktrees whose directory is gone; a locked one stays. */
    fun prune(repo: String) {
        gitOk(repo, "worktree", "prune")
    }

    /** From the local remote-tracking ref: no network on this path. */
    fun hasRemoteBranch(
        repo: String,
        branch: String,
    ): Boolean = succeeds(repo, "show-ref", "--verify", "-q", "refs/remotes/origin/$branch")

    /** `origin/<x>` → `git fetch origin <x>`; a base that is not on `origin` is left as is. */
    fun fetch(
        repo: String,
        base: String,
    ) {
        if (!base.startsWith("origin/")) return
        val branch = base.removePrefix("origin/")
        gitSlow(repo, "fetching $branch…", "fetch", "--quiet", "origin", branch)
    }

    /** The paths `git add -A` would stage, deletions left out; a rename's or copy's second field is its source. */
    fun pendingPaths(worktree: String): List<String> {
        val fields =
            gitOk(worktree, "status", "--porcelain", "-z", "--untracked-files=all")
                .stdout
                .decodeToString()
                .split('\u0000')
                .filter { it.length > 3 }
        val paths = mutableListOf<String>()
        val it = fields.iterator()
        while (it.hasNext()) {
            val field = it.next()
            val xy = field.substring(0, XY)
            if (xy[0] == 'R' || xy[0] == 'C') {
                if (it.hasNext()) {
                    it.next()
                }
            }
            if ('D' !in xy.substring(0, 2)) paths += field.substring(XY)
        }
        return paths
    }

    /** `git add -A` and one commit with [message]; the repository's own hooks and identity apply. */
    fun commitAll(
        worktree: String,
        message: String,
    ) {
        gitOk(worktree, "add", "-A")
        gitSlow(worktree, "committing…", "commit", "--quiet", "-m", message)
    }

    /** `git push -u origin <branch>`. */
    fun push(
        worktree: String,
        branch: String,
    ) {
        gitSlow(worktree, "pushing $branch…", "push", "--quiet", "-u", "origin", branch)
    }

    /** One locked worktree on a new branch with no upstream. */
    fun addLocked(
        repo: String,
        path: String,
        branch: String,
        reason: String,
        base: String,
    ) {
        gitSlow(repo, "creating worktree…", "worktree", "add", "--quiet", "--lock", "--reason", reason, "--no-track", "-b", branch, path, base)
    }
}
