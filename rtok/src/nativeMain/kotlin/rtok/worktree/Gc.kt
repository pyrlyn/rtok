// Port of src/worktree/gc.rs (T153, T418, T453, T656, T949, T950): `rtok worktree gc` removes finished worktrees and
// their merged branches and drops the records of worktrees whose directory is gone. [Gc.decide] is pure; [Gc.run]
// applies one verdict at a time and never forces anything. Output is the non-terminal form: no progress bar.
package rtok.worktree

import kotlinx.serialization.Serializable
import rtok.Col
import rtok.RemovalPreview
import rtok.sys.canon
import rtok.sys.samePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

sealed interface Verdict {
    /** Merged, clean, idle and not held by anyone else: remove the worktree. */
    data object Remove : Verdict

    /** Merged, clean and idle past `stale_lock` under someone else's lock (T418), or an ended agent's (T949). */
    data class Reclaim(
        val owner: String,
    ) : Verdict

    /** A finished task, idle, that a live agent or someone else's lock would otherwise keep (T453). */
    data class Finished(
        val over: String,
    ) : Verdict

    /** The directory is gone: drop this one record (never a blanket `prune`). */
    data object DropRecord : Verdict

    data class Keep(
        val why: String,
    ) : Verdict

    /** The action `gc` takes and what it says about it before `--yes`. */
    fun plan(): Pair<String, String> =
        when (this) {
            Remove -> "remove" to "merged, clean and idle"
            is Reclaim -> "remove" to "merged, clean, abandoned lock$owner"
            is Finished -> "remove" to "finished task, idle, though $over"
            DropRecord -> "drop-record" to "directory is gone"
            is Keep -> "keep" to why
        }
}

/** gc.rs `Policy`; [now] is nanoseconds since the epoch. */
data class GcPolicy(
    /** Whose locks may be opened; any other lock holds until it goes stale (T418). */
    val owner: String?,
    val idle: Duration,
    /** Untouched this long, a foreign lock on a merged, clean worktree is abandoned. */
    val staleLock: Duration,
    val now: Long,
    /** Live rtok agents (T282): a worktree bound to one is never removed (T285). */
    val live: Set<String> = emptySet(),
    /** Ended rtok agents (T949): their leftover lock holds only through the idle window. */
    val ended: Set<String> = emptySet(),
    /** Worktrees a post-create script adopted before any agent took them (T289.5), held like a live agent's. */
    val pending: List<String> = emptyList(),
) {
    /** How long ago [modified] (nanoseconds) was; a future mtime is age zero. */
    fun age(modified: Long): Duration = (now - modified).coerceAtLeast(0).nanoseconds
}

@Serializable
data class Outcome(
    val path: String,
    val branch: String?,
    /** What a removal frees, counted before it runs; 0 unless the action is `remove`. */
    val bytes: Long,
    val files: Long,
    /** `remove`, `drop-record` or `keep`. */
    val action: String,
    /** Why it was kept, what was done, or git's message when a step failed. */
    val note: String,
    val failed: Boolean,
)

/** A worktree of the repository and the verdict `gc` reaches for it. */
data class Judged(
    val entry: Entry,
    val verdict: Verdict,
)

private const val ID8 = 8
private const val NANOS_PER_SECOND = 1_000_000_000L

object Gc {
    /** `--stale-lock`'s default: one window for gc's foreign locks (T418) and remove/adopt's claims (T527). */
    const val STALE_LOCK_DEFAULT = "7d"

    /**
     * [modified] yields the newest mtime (nanoseconds) under the worktree; it is a walk, asked only once no cheaper rule
     * decided, told the age that settles the question. [current] says the command runs from inside the worktree.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun decide(
        entry: Entry,
        modified: (Duration) -> Long?,
        current: Boolean,
        p: GcPolicy,
    ): Verdict {
        val record = entry.record
        if (entry.state == State.Main) return Verdict.Keep("main checkout")
        if (current) return Verdict.Keep("the worktree this command runs from")
        val lock = record.owner()
        val agent = lock?.agent
        val short = { a: String -> a.take(ID8) }
        val pending = { canon(record.path).let { dir -> p.pending.any { samePath(dir, it) } } }
        val live =
            agent?.takeIf { it in p.live }?.let { "agent ${short(it)} is live" }
                ?: if (pending()) "pending claim, no agent has taken it" else null
        val ended = agent?.takeIf { it in p.ended }?.let(short)
        val held = record.heldAgainst(p.owner)
        val owner = { record.owner()?.owner?.let { " by $it" } ?: ", owner unknown" }
        // An unreadable mtime is no evidence of age.
        val age = { within: Duration -> modified(within)?.let(p::age) }
        // T453: every commit of a finished task is in the base, so neither its lock nor its live agent keeps it.
        if (entry.done && (live != null || held)) {
            val why = live ?: "locked${owner()}"
            return if (age(p.idle)?.let { it >= p.idle } == true) Verdict.Finished(why) else Verdict.Keep(why)
        }
        if (live != null) return Verdict.Keep(live)
        if (held) return decideHeld(entry, lock, ended, owner(), age, p)
        return when (entry.state) {
            State.Stale -> {
                Verdict.DropRecord
            }

            State.Dirty -> {
                Verdict.Keep("uncommitted changes")
            }

            State.Unmerged -> {
                record.branch?.let { Verdict.Keep("not merged into the base; check `gh pr view $it`") }
                    ?: Verdict.Keep("detached HEAD not merged into the base")
            }

            else -> {
                val a = age(p.idle)
                when {
                    a == null -> Verdict.Keep(AGE_UNKNOWN)

                    // T656: no evidence of age is no evidence of idleness.
                    a < p.idle -> Verdict.Keep("modified within the idle window")

                    else -> Verdict.Remove
                }
            }
        }
    }

    /** Someone else's lock: T950 for a vanished directory, T418/T949 for a merged, clean one; anything else holds. */
    @OptIn(ExperimentalTime::class)
    @Suppress("LongParameterList")
    private fun decideHeld(
        entry: Entry,
        lock: Owner?,
        ended: String?,
        owner: String,
        age: (Duration) -> Duration?,
        p: GcPolicy,
    ): Verdict {
        if (entry.state == State.Stale) {
            // The record has no mtime, so the lock's own date is its age.
            val dated = lock?.let { runCatching { Instant.parse("${it.date}T00:00:00Z").epochSeconds }.getOrNull() }
            val abandoned = dated != null && p.age(dated * NANOS_PER_SECOND) >= p.staleLock
            return if (ended != null || abandoned) Verdict.DropRecord else Verdict.Keep("locked$owner")
        }
        // Only a merged, clean worktree; an ended agent's lock is a leftover that holds only through the idle window.
        val fresh = if (ended != null) p.idle else maxOf(p.idle, p.staleLock)
        return if (entry.state == State.Merged && age(fresh)?.let { it >= fresh } == true) {
            Verdict.Reclaim(if (ended != null) "$owner, agent $ended ended" else owner)
        } else {
            Verdict.Keep("locked$owner")
        }
    }

    /**
     * Every worktree of the repository [cwd] is in, judged by [decide], and the main checkout's path (where removals run
     * from). `agents junk` reads the same verdicts.
     */
    fun judge(
        cwd: String,
        policy: GcPolicy,
    ): Pair<String, List<Judged>> {
        val here = canon(cwd)
        val entries = Worktrees.inventory(cwd)
        val repo = entries.firstOrNull()?.record?.path ?: cwd
        val currentAt = Worktrees.deepest(here, entries.indices) { entries[it].record.path }
        // The walks are the expensive part: side by side, and stopped at the first recent file.
        val verdicts =
            Worktrees.parMap(entries) { i, entry ->
                val modified = { within: Duration -> newestUntil(entry.record.path) { m -> policy.age(m) < within } }
                decide(entry, modified, currentAt == i, policy)
            }
        return repo to entries.zip(verdicts).map { (e, v) -> Judged(e, v) }
    }

    fun run(
        cwd: String,
        policy: GcPolicy,
        yes: Boolean,
    ): List<Outcome> {
        val (repo, judged) = judge(cwd, policy)
        // Removals stay one at a time: each takes the repository's locks.
        return judged.map { (entry, verdict) ->
            val (action, planned) = verdict.plan()
            val (bytes, files) = if (action == "remove") RemovalPreview.sizeOf(entry.record.path) else 0L to 0L
            val acting = yes && action != "keep"
            val done = if (acting) runCatching { WorktreeRemove.detach(repo, entry.record, entry.merged) } else null
            Outcome(
                path = entry.record.path,
                branch = entry.record.branch,
                bytes = bytes,
                files = files,
                action = action,
                note = done?.fold({ it }, { "failed, kept: ${it.message}" }) ?: planned,
                failed = done?.isFailure == true,
            )
        }
    }

    fun toTable(
        outcomes: List<Outcome>,
        yes: Boolean,
    ): String {
        val lines = listOf(listOf("action", "path", "branch")) + outcomes.map { listOf(it.action, it.path, it.branch ?: "-") }
        val cols = listOf(Col.left(0), Col.left(0), Col.right(0))
        val out = StringBuilder(notedTable(cols, lines, outcomes.map { it.note }))
        val planned = outcomes.count { it.action != "keep" }
        if (!yes && planned > 0) {
            val preview = RemovalPreview.render(outcomes.filter { it.action == "remove" }.map { Triple(it.path, it.bytes, it.files) })
            if (preview.isNotEmpty()) out.append('\n').append(preview)
            out.append("\ndry run: $planned to act on, nothing changed; rerun with --yes\n")
        }
        return out.toString()
    }
}
