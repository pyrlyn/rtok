// Port of src/worktree/mod.rs (T150): every worktree of a repository, who holds it and what state it is in. Parsing and
// classification are pure; Git.kt is the only place that spawns git.
package rtok.worktree

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import rtok.sys.canon
import rtok.sys.components
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.readToString

/**
 * Who holds a worktree, from a lock reason `<owner> | <task-id> | <date>` or, since T285,
 * `<owner> | <task-id> | <date> | agent <uuid>`. A 3-field lock stays valid and has no agent.
 */
data class Owner(
    val owner: String,
    val task: String,
    val date: String,
    /** The rtok agent id (T282) the worktree is bound to. */
    val agent: String? = null,
) {
    /** The lock reason [parse] reads back: v2 when an agent is known. */
    fun reason(): String = "$owner | $task | $date" + (agent?.let { " | agent $it" } ?: "")

    companion object {
        private const val V1_FIELDS = 3
        private const val V2_FIELDS = 4

        fun parse(reason: String): Owner? {
            val parts = reason.split(" | ").map(String::trim)
            val (owner, task, date) = parts.takeIf { it.size == V1_FIELDS || it.size == V2_FIELDS } ?: return null
            val field = parts.getOrNull(V1_FIELDS)
            val agent = field?.takeIf { it.startsWith("agent ") }?.removePrefix("agent ")?.trim()
            val wellFormed = if (field == null) true else agent != null && agent.isNotEmpty() && ' ' !in agent
            val filled = listOf(owner, task, date).none(String::isEmpty)
            return if (filled && wellFormed) Owner(owner, task, date, agent) else null
        }
    }
}

enum class State(
    val label: String,
) {
    /** The main checkout (or a bare repository): never a removal candidate. */
    Main("main"),

    /** The record exists, the directory does not. */
    Stale("stale"),
    Dirty("dirty"),

    /** Merging the branch into the base would change nothing — squash merges included. */
    Merged("merged"),
    Unmerged("unmerged"),
}

data class Facts(
    val exists: Boolean,
    val dirty: Boolean,
    val merged: Boolean,
)

data class Entry(
    val record: WorktreeRecord,
    val state: State,
    /** Kept beside [state]: a worktree whose directory is gone still needs to know whether its branch may go. */
    val merged: Boolean,
    /** [State.Merged] and its branch did work of its own (T453): a finished task. */
    val done: Boolean,
)

object Worktrees {
    /** What every `rtok worktree …` command answers while `[worktree] enabled = false` (T410). */
    const val DISABLED = "worktrees are not enabled; set `[worktree] enabled = true` in ~/.rtok/config.toml"

    /** A missing directory wins over everything, uncommitted work wins over "merged". */
    fun classify(facts: Facts): State =
        when {
            !facts.exists -> State.Stale
            facts.dirty -> State.Dirty
            facts.merged -> State.Merged
            else -> State.Unmerged
        }

    /** The item whose directory holds [here] (canonical), the deepest when several do: worktrees nest. */
    fun <T> deepest(
        here: String,
        items: Iterable<T>,
        dir: (T) -> String,
    ): T? =
        items
            .mapNotNull { item -> canon(dir(item)).takeIf { pathStartsWith(here, it) }?.let { components(it).size to item } }
            .maxByOrNull { it.first }
            ?.second

    /** The worktree root that contains [cwd], and its git admin directory; a relative `gitdir:` resolves against it. */
    @Suppress("ReturnCount")
    fun checkoutOf(cwd: String): Pair<String, String>? {
        var dir: String? = cwd
        while (dir != null) {
            val dot = join(dir, ".git")
            if (isDir(dot)) return dir to dot
            val admin =
                runCatching { readToString(dot) }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")
                    ?.trim()
            if (admin != null) return dir to (if (admin.startsWith("/")) admin else join(dir, admin))
            dir = parent(dir)
        }
        return null
    }

    /** The worktree root whose lock names [agent]; any other lock, or none, is null. */
    fun lockedBy(
        cwd: String,
        agent: String,
    ): String? {
        val (root, admin) = checkoutOf(cwd) ?: return null
        val owner = runCatching { readToString(join(admin, "locked")) }.getOrNull()?.let { Owner.parse(it.trim()) }
        return root.takeIf { owner?.agent == agent }
    }

    /** [f] over [items] side by side (git and disk waits), results in [items] order. */
    fun <T, R> parMap(
        items: List<T>,
        f: (Int, T) -> R,
    ): List<R> = runBlocking(Dispatchers.Default) { items.mapIndexed { i, x -> async { f(i, x) } }.awaitAll() }

    /** Every worktree of the repository at [repo], classified against its default base. */
    fun inventory(repo: String): List<Entry> = inventoryWith(repo) { }.map { it.first }

    /**
     * [inventory] plus [then] of each entry, right after that entry's git checks. A worktree git cannot read counts as
     * dirty and an unanswerable merge check as unmerged: both are the side on which nothing gets removed.
     */
    fun <X> inventoryWith(
        repo: String,
        then: (Entry) -> X,
    ): List<Pair<Entry, X>> {
        val base = Git.defaultBase(repo)
        val baseTree = runCatching { Git.tree(repo, base) }.getOrNull()
        val records = Git.list(repo)
        return parMap(records) { i, record ->
            val main = i == 0 || record.bare
            val rev = record.branch ?: record.head
            val merged = !main && rev != null && runCatching { Git.isMerged(repo, base, baseTree, rev) }.getOrDefault(false)
            val state =
                if (main) {
                    State.Main
                } else {
                    val exists = isDir(record.path)
                    classify(Facts(exists, exists && runCatching { Git.isDirty(record.path) }.getOrDefault(true), merged))
                }
            val done = state == State.Merged && rev != null && runCatching { Git.hasOwnCommits(repo, base, rev) }.getOrDefault(false)
            val entry = Entry(record, state, merged, done)
            entry to then(entry)
        }
    }
}
