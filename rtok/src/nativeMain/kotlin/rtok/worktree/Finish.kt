// Port of src/worktree/finish.rs (T952.1): `rtok worktree finish` hands a task's worktree to review in one step — commit
// what is left, push, open (or reuse) the PR, ask for auto-merge, then unlock. It never closes the task: it marks the
// claim, and `rtok mcp` closes the task and removes the worktree once the PR merges (T952.2).
package rtok.worktree

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.HostPaths
import rtok.store.AgentDetail
import rtok.store.Store
import rtok.store.finishWorktreeClaim
import rtok.sys.ProgressRunner
import rtok.sys.canon
import rtok.sys.samePath

/** What `finish` asks of `gh` beyond the defaults. */
data class FinishOptions(
    /** The commit message for uncommitted work; else `<task>: <branch slug>`. */
    val message: String? = null,
    /** `owner/name` when the `origin` URL does not name the repository the PR belongs to. */
    val repo: String? = null,
    val noAutoMerge: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Finished(
    val path: String,
    val branch: String,
    /** The commit message `finish` used, when the tree was dirty. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val committed: String? = null,
    val pr: String,
    @SerialName("auto_merge") val autoMerge: Boolean,
    val unlocked: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val warnings: List<String> = emptyList(),
) {
    fun toText(): String =
        buildString {
            append("$path: $pr\n")
            committed?.let { append("committed: $it\n") }
            append("auto-merge: ${if (autoMerge) "on" else "off"}\n")
            if (unlocked) append("unlocked\n")
            for (w in warnings) append("warning: $w\n")
        }
}

/** One `gh` call in [dir]: its trimmed stdout, or a [WorktreeException] naming the call and gh's stderr. */
fun interface GhRunner {
    fun run(
        dir: String,
        label: String,
        args: List<String>,
    ): String

    companion object {
        private const val CAP = 4L shl 20

        val HOST =
            GhRunner { dir, label, args ->
                val out =
                    runCatching { ProgressRunner(label, "gh", args).cwd(dir).runCapped(CAP).first }
                        .getOrElse { fail("cannot run gh; install GitHub CLI and run `gh auth login`") }
                if (out.exitCode != 0) fail("gh ${args.joinToString(" ")}: ${out.stderr.decodeToString().trim()}")
                out.stdout.decodeToString().trim()
            }
    }
}

object Finish {
    /** The default commit message: `<task>: <slug>` from a `<task>-<slug>` branch, the branch alone when no task names it. */
    fun messageFor(
        task: String?,
        branch: String,
    ): String {
        task ?: return branch
        val prefix = "${task.lowercase()}-"
        val lower = branch.lowercase()
        val slug =
            lower
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.replace('-', ' ')
                ?.takeIf { it.isNotEmpty() }
        return "$task: ${slug ?: branch}"
    }

    private fun withRepo(
        args: List<String>,
        repo: String?,
    ) = if (repo != null) args + listOf("--repo", repo) else args

    /** The open PR for [branch], else a new one filled from its commits. */
    private fun openPr(
        gh: GhRunner,
        dir: String,
        branch: String,
        base: String,
        repo: String?,
    ): String {
        val open =
            runCatching { gh.run(dir, "looking for the PR…", withRepo(listOf("pr", "view", branch, "--json", "url,state"), repo)) }
                .mapCatching { Json.parseToJsonElement(it) as JsonObject }
                .getOrNull()
                ?.takeIf { (it["state"] as? JsonPrimitive)?.content == "OPEN" }
                ?.let { (it["url"] as? JsonPrimitive)?.takeIf { u -> u.isString }?.content }
        if (open != null) return open
        val out = gh.run(dir, "opening the PR…", withRepo(listOf("pr", "create", "--head", branch, "--base", base, "--fill-first"), repo))
        // `gh pr create` prints progress lines before the URL.
        return out.lines().lastOrNull().orEmpty()
    }

    /**
     * Finish the worktree holding [path] for [agent] (named by [ownerFlag] when given). Every refusal comes before the
     * first change; the lock goes last, so a failed push or PR leaves the worktree as protected as it was.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    fun forAgent(
        store: Store?,
        path: String,
        agent: AgentDetail?,
        ownerFlag: String?,
        opts: FinishOptions,
        gh: GhRunner = GhRunner.HOST,
    ): Finished {
        val here = canon(path)
        val records = Git.list(here)
        val main = records.firstOrNull()?.path ?: fail("git lists no worktree")
        val record = Worktrees.deepest(here, records) { it.path } ?: fail("not inside a worktree of this repository")
        val shown = canon(record.path)
        if (samePath(shown, canon(main)) || record.bare) fail("$shown is the main checkout; run finish inside a task worktree")
        val branch = record.branch ?: fail("$shown: detached HEAD; check out the task branch first")
        val owner = if (ownerFlag == null && agent == null) null else Claim.owner(ownerFlag, agent, store)
        Claim.heldAgainst(record, owner.orEmpty(), agent?.id.orEmpty(), store)?.takeIf { it.holds }?.let {
            fail("$shown is ${it.locked()}; not finished")
        }

        val base = Git.defaultBase(main)
        val warnings = mutableListOf<String>()
        try {
            Git.fetch(main, base)
        } catch (e: GitError) {
            warnings += "${e.message}; own commits are judged against the local $base"
        }
        val pending = Git.pendingPaths(record.path)
        val secrets = pending.filter { HostPaths.isSecret(it) }
        if (secrets.isNotEmpty()) {
            fail("$shown: refusing to commit credential files: ${secrets.joinToString(", ")}; ignore or remove them, or commit by hand")
        }
        if (pending.isEmpty() && !Git.hasOwnCommits(main, base, branch)) fail("$shown: nothing to finish; $branch has no commits of its own")

        val committed =
            if (pending.isEmpty()) {
                null
            } else {
                (opts.message ?: messageFor(record.owner()?.task, branch)).also { Git.commitAll(record.path, it) }
            }
        Git.push(record.path, branch)
        val pr = openPr(gh, record.path, branch, base.removePrefix("origin/"), opts.repo)
        val autoMerge =
            !opts.noAutoMerge &&
                try {
                    gh.run(record.path, "asking for auto-merge…", withRepo(listOf("pr", "merge", pr, "--auto", "--squash"), opts.repo))
                    true
                } catch (e: WorktreeException) {
                    warnings += "auto-merge not set: ${e.message}"
                    false
                }
        val unlocked = record.locked != null
        if (unlocked) Git.unlock(main, record.path)
        // The push and the PR already happened, so a missing mark costs only the cleanup.
        val marked = store?.let { runCatching { it.finishWorktreeClaim(shown) } }
        when {
            marked == null || marked.getOrNull() == false -> {
                warnings += "no claim in the rtok store, so nothing removes it after the merge; run `rtok worktree remove` then"
            }

            marked.isFailure -> {
                warnings += "not removed after the merge: ${marked.exceptionOrNull()?.message}"
            }
        }
        return Finished(shown, branch, committed, pr, autoMerge, unlocked, warnings)
    }
}
