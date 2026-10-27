// Port of src/worktree/add.rs (T158, T410, T660.4, T970): the one creation path — one location, one name, one owner.
// [Add.plan] decides everything from paths and strings; [Add.run] is one fetch and one `git worktree add`.
package rtok.worktree

import rtok.config.ProjectRoot
import rtok.config.contain
import rtok.config.resolved
import rtok.store.Store
import rtok.sys.IoException
import rtok.sys.ancestors
import rtok.sys.appendString
import rtok.sys.canon
import rtok.sys.createDirAll
import rtok.sys.eprint
import rtok.sys.exists
import rtok.sys.fileName
import rtok.sys.freeBytes
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.readBytes
import rtok.sys.removeTree

/** What `add` will create; [reason] is the lock reason [Owner.parse] reads back. */
data class AddPlan(
    val path: String,
    val branch: String,
    val task: String,
    val reason: String,
)

/** The two effects a healthy test machine cannot cause: a nearly full volume and a `git worktree add` that dies. */
data class Seams(
    val freeBytes: (String) -> Long,
    val create: (repo: String, path: String, branch: String, reason: String, base: String) -> Unit,
) {
    companion object {
        val HOST = Seams({ freeBytes(it) }, Git::addLocked)
    }
}

/** What a failed add takes back, newest first, unless [keep] says the add went through (T970). */
class Undo {
    private val steps = mutableListOf<() -> Unit>()

    internal fun branch(
        repo: String,
        name: String,
    ) {
        steps += { if (Git.hasBranch(repo, name)) Git.deleteBranch(repo, name) }
    }

    internal fun worktree(
        repo: String,
        path: String,
    ) {
        steps += {
            // A lock outlives a failed add: `prune` leaves a locked record alone.
            runCatching { Git.unlock(repo, path) }
            if (exists(path)) removeTree(path)
            Git.prune(repo)
        }
    }

    /** A claim in the store, released on a failure. */
    fun claim(
        store: Store,
        path: String,
    ) {
        val p = canon(path)
        steps += { ClaimStore.release(store, p) }
    }

    /** The add went through; nothing is taken back. */
    fun keep() = steps.clear()

    /** Runs [op]; when it throws, every recorded step is taken back before the error goes on. */
    inline fun guard(op: () -> Unit) {
        var done = false
        try {
            op()
            done = true
        } finally {
            if (!done) rollback()
        }
    }

    /** Takes every recorded step back; a step that fails is a warning. */
    fun rollback() {
        steps.reversed().forEach { step ->
            runCatching(step).onFailure { eprint("warning: add not fully undone: ${it.message}\n") }
        }
        steps.clear()
    }
}

private val IDENT = Regex("""[a-z0-9][a-z0-9._-]*""")
private const val ASCII_END = 128
private const val MIB_SHIFT = 20

object Add {
    /** The rule [ensureIgnored] adds. */
    const val IGNORE_RULE = ".rtok/"

    /** Lower-cased, then `[a-z0-9][a-z0-9._-]*` minus what git refuses in a ref name. */
    internal fun ident(
        what: String,
        raw: String,
    ): String {
        val id = raw.lowercase()
        val shape = IDENT.matches(id)
        val refOk = !id.contains("..") && !id.endsWith('.') && !id.endsWith(".lock")
        if (!(shape && refOk)) fail("$what `$raw` must match [a-z0-9][a-z0-9._-]* and be a valid branch name")
        return id
    }

    /** Whether [root] names a place inside the project (relative, no root). */
    internal fun inProject(root: String) = !root.startsWith('/')

    /** The directory [root] names for the repository whose main checkout is [main]; relative is under [main]. */
    fun landing(
        main: String,
        root: String,
    ): String = if (inProject(root)) join(main, root) else root

    /** [temp] lists the directories the OS may purge; a worktree under one loses its files. */
    fun plan(
        main: String,
        root: String,
        temp: List<String>,
        id: Pair<String, String?>,
        who: Pair<String, String?>,
        date: String,
    ): AddPlan {
        val task = ident("task id", id.first)
        val branch = id.second?.let { "$task-${ident("slug", it)}" } ?: task
        val (owner, agent) = who
        val reason = Owner(owner, task, date, agent).reason()
        // ASCII: `git worktree list --porcelain` C-quotes anything else.
        val roundTrips = Owner.parse(reason)?.owner == owner
        val plain = owner.all { it.code < ASCII_END } && owner.none { it == '\n' || it == '\r' }
        if (!(plain && roundTrips)) {
            fail("--owner `$owner` must be non-empty ASCII without ` | `, e.g. \"Claude Code / sonnet\"")
        }
        if (root.isEmpty()) fail("`[worktree] root` is empty; set it or drop the key for .rtok/worktrees")
        val base = landing(main, root)
        val landed = resolved(base)
        val checkout = resolved(main)
        // A repository that itself lives under the temp directory (a fixture, a scratch clone) keeps its worktrees beside it.
        temp.map { resolved(it) }.firstOrNull { pathStartsWith(landed, it) && !pathStartsWith(checkout, it) }?.let {
            fail("worktree root $root is under the temporary directory $it; set `[worktree] root`")
        }
        val repo = fileName(main) ?: fail("the main checkout has no directory name")
        val name = "$repo-$task"
        val path = join(base, name)
        if (inProject(root)) {
            // A relative root is a promise to stay in the project; `../x` would break it.
            try {
                contain(main, join(root, name))
            } catch (_: IoException) {
                fail("worktree path $path is outside the main checkout $main; use an absolute `[worktree] root`")
            }
        }
        if (exists(path)) fail("$path already exists: one worktree per task")
        return AddPlan(path, branch, task, reason)
    }

    /**
     * Makes git ignore `<main>/.rtok/`: a rule in effect is left alone; else `.gitignore` gets it when the project has
     * one (the rest kept byte for byte), else the clone-local `info/exclude`.
     */
    fun ensureIgnored(main: String) {
        if (Git.isIgnored(main, IGNORE_RULE)) return
        val gitignore = join(main, ".gitignore")
        val file =
            if (isFile(gitignore)) {
                gitignore
            } else {
                val gitDir = ProjectRoot.find(main)?.gitDir ?: join(main, ".git")
                val info = join(gitDir, "info")
                context({ "cannot create $info" }) { createDirAll(info) }
                join(info, "exclude")
            }
        val bytes = if (exists(file)) context({ "cannot read $file" }) { readBytes(file) } else ByteArray(0)
        val sep = if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) "\n" else ""
        context({ "cannot write $file" }) { appendString(file, "$sep$IGNORE_RULE\n") }
    }

    /** Refuses when the volume of [path] (its nearest existing ancestor) has under [minMib] MiB free; unknown passes. */
    internal fun ensureRoom(
        seams: Seams,
        path: String,
        minMib: UInt,
    ) {
        val on = ancestors(path).firstOrNull { exists(it) }?.takeIf { minMib > 0u } ?: return
        val read = runCatching { seams.freeBytes(on) shr MIB_SHIFT }
        read.exceptionOrNull()?.let { eprint("warning: free space of $on unknown: ${it.message}\n") }
        val free = read.getOrNull() ?: return
        if (free < minMib.toLong()) {
            fail(
                "only $free MiB free for $path, below `[worktree] min_free_mib` = $minMib; " +
                    "free disk space or lower it (0 turns the check off)",
            )
        }
    }

    /**
     * Creates the worktree and returns its plan with the [Undo] the caller keeps until its own steps are done. A failed
     * fetch is an error: branching from a stale base is how a finished task gets rebuilt on old code.
     */
    fun run(
        cwd: String,
        wt: rtok.config.Worktree,
        seams: Seams,
        id: Pair<String, String?>,
        who: Pair<String, String?>,
        temp: List<String> = listOf(rtok.sys.tempDir(), "/tmp"),
    ): Pair<AddPlan, Undo> {
        val main = Git.list(cwd).firstOrNull()?.path ?: fail("git lists no worktree")
        val plan = plan(main, wt.root, temp, id, who, today())
        ensureRoom(seams, plan.path, wt.minFreeMib)
        val base = Git.defaultBase(main)
        Git.fetch(main, base)
        if (pathStartsWith(resolved(plan.path), resolved(main))) ensureIgnored(main)
        parent(plan.path)?.let { r -> context({ "cannot create $r" }) { createDirAll(r) } }
        // Another add of the same task may have made it since; the rollback must never remove what this call did not create.
        if (exists(plan.path)) fail("${plan.path} already exists: one worktree per task")
        val undo = Undo()
        // Recorded before the call: git may die with the branch made and the directory half filled.
        if (!Git.hasBranch(main, plan.branch)) undo.branch(main, plan.branch)
        undo.worktree(main, plan.path)
        undo.guard { seams.create(main, plan.path, plan.branch, plan.reason, base) }
        return plan to undo
    }
}
