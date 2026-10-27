// Port of the src/worktree/gc.rs tests (rstest tables as loops).
package rtok.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val NANOS = 1_000_000_000L
private const val NOW = 1_000L
private const val IDLE = 100
private const val STALE = 500
private const val BUSY = 990L
private const val FOREIGN_FRESH = 600L
private const val STALE_DAYS = 7
private const val ENDED_IDLE = 200L
private const val ENDED_BUSY = 50L
private const val ME = "Claude Code / sonnet"
private const val MINE = "Claude Code / sonnet | t1 | 2026-09-22"
private const val THEIRS = "Cursor / grok | t2 | 2026-09-22"
private const val LIVE = "Cursor / grok | t1 | 2026-09-22 | agent 0193ab12-live"
private const val ENDED = "Claude Code / opus | t1 | 2026-10-20 | agent 0193ab12-gone"
private const val ENDED_LIVE = "Claude Code / opus | t1 | 2026-10-20 | agent 0193ab12-both"
private const val UNSEEN = "Claude Code / opus | t1 | 2026-10-20 | agent 0193ab12-unseen"
private const val THEIRS_RECENT = "Cursor / grok | t2 | 2026-10-18"
private const val THEIRS_UNDATED = "Cursor / grok | t2 | someday"

class GcTest {
    private fun at(secs: Long) = secs * NANOS

    private fun label(v: Verdict): String =
        when (v) {
            Verdict.Remove -> "remove"
            is Verdict.Reclaim -> "reclaim:${v.owner}"
            is Verdict.Finished -> "finished:${v.over}"
            Verdict.DropRecord -> "drop-record"
            is Verdict.Keep -> "keep:${v.why}"
        }

    private fun policy(
        owner: String? = ME,
        live: Set<String> = emptySet(),
        pending: List<String> = emptyList(),
    ) = GcPolicy(owner, IDLE.seconds, STALE.seconds, at(NOW), live, emptySet(), pending)

    private fun entry(
        state: State,
        locked: String?,
        done: Boolean = false,
        path: String = "",
    ) = Entry(WorktreeRecord(path, branch = "t1", locked = locked), state, state == State.Merged, done)

    private data class Case(
        val state: State,
        val locked: String?,
        val current: Boolean,
        val modified: Long,
        val want: String,
    )

    @Test fun verdicts() {
        val cases =
            listOf(
                Case(State.Main, null, false, 0, "keep:main checkout"),
                Case(State.Merged, null, true, 0, "keep:the worktree this command runs from"),
                Case(State.Merged, null, false, 0, "remove"),
                Case(State.Merged, MINE, false, 0, "remove"),
                Case(State.Merged, THEIRS, false, FOREIGN_FRESH, "keep:locked by Cursor / grok"),
                Case(State.Merged, "", false, FOREIGN_FRESH, "keep:locked, owner unknown"),
                Case(State.Merged, THEIRS, false, 0, "reclaim: by Cursor / grok"),
                Case(State.Merged, "", false, 0, "reclaim:, owner unknown"),
                Case(State.Dirty, THEIRS, false, 0, "keep:locked by Cursor / grok"),
                Case(State.Unmerged, THEIRS, false, 0, "keep:locked by Cursor / grok"),
                Case(State.Merged, null, false, BUSY, "keep:modified within the idle window"),
                Case(State.Merged, "Claude Code / sonnet | t1 | 2026-09-22 | agent 0193ab12-live", false, 0, "keep:agent 0193ab12 is live"),
                Case(State.Merged, "Claude Code / sonnet | t1 | 2026-09-22 | agent 0193ab12-gone", false, 0, "remove"),
                Case(State.Dirty, null, false, 0, "keep:uncommitted changes"),
                Case(State.Unmerged, null, false, 0, "keep:not merged into the base; check `gh pr view t1`"),
                Case(State.Stale, null, false, 0, "drop-record"),
                Case(State.Stale, MINE, false, 0, "drop-record"),
                Case(State.Stale, THEIRS, false, 0, "keep:locked by Cursor / grok"),
                Case(State.Stale, "", false, 0, "keep:locked, owner unknown"),
            )
        val p = policy(live = setOf("0193ab12-live"))
        for (c in cases) {
            var asked: Duration? = null
            val got =
                label(
                    Gc.decide(entry(c.state, c.locked), { within ->
                        asked = within
                        at(c.modified)
                    }, c.current, p),
                )
            assertEquals(c.want, got, "$c")
            // The walk runs only where its answer can still change the verdict, told the age that settles it.
            val held = c.want.startsWith("reclaim:") || c.want.startsWith("keep:locked")
            val within =
                when {
                    c.want == "remove" || c.want == "keep:modified within the idle window" -> IDLE.seconds
                    held && c.state == State.Merged -> STALE.seconds
                    else -> null
                }
            assertEquals(within, asked, c.want)
        }
    }

    @Test fun withoutAnOwnerEveryLockIsAHardStop() {
        val e = Entry(WorktreeRecord("", locked = MINE), State.Merged, true, false)
        val p = GcPolicy(null, Duration.ZERO, Duration.ZERO, at(NOW))
        assertEquals(Verdict.Keep("locked by $ME"), Gc.decide(e, { null }, false, p))
    }

    /** T656: a merged, unlocked worktree whose newest mtime is unknown stays; with an old one it goes. */
    @Test fun anUnknownAgeKeepsAMergedWorktree() {
        val cases =
            listOf(
                null to "keep:age unknown: a file or its mtime could not be read",
                0L to "remove",
                BUSY to "keep:modified within the idle window",
            )
        for ((modified, want) in cases) {
            assertEquals(want, label(Gc.decide(entry(State.Merged, null), { modified?.let(::at) }, false, policy())), want)
        }
    }

    /** T453: a finished task goes past a live agent or a foreign lock once idle. */
    @Test fun aFinishedTaskIsKeptByNoLockAndNoLiveAgent() {
        val cases =
            listOf(
                Triple(LIVE, 0L, "finished:agent 0193ab12 is live"),
                Triple(LIVE, BUSY, "keep:agent 0193ab12 is live"),
                Triple(THEIRS, 0L, "finished:locked by Cursor / grok"),
                Triple(THEIRS, BUSY, "keep:locked by Cursor / grok"),
                Triple(THEIRS, null, "keep:locked by Cursor / grok"),
                Triple("", 0L, "finished:locked, owner unknown"),
                Triple(MINE, 0L, "remove"),
                Triple(null, 0L, "remove"),
            )
        val p = policy(live = setOf("0193ab12-live"))
        for ((locked, modified, want) in cases) {
            assertEquals(want, label(Gc.decide(entry(State.Merged, locked, done = true), { modified?.let(::at) }, false, p)), want)
        }
    }

    /** T289.5: a parked worktree is kept, and goes only once its finished task has been idle. */
    @Test fun aPendingClaimIsNotAnOrphan() {
        val cases =
            listOf(
                Triple(false, 0L, "keep:pending claim, no agent has taken it"),
                Triple(true, BUSY, "keep:pending claim, no agent has taken it"),
                Triple(true, 0L, "finished:pending claim, no agent has taken it"),
            )
        val p = policy(pending = listOf("/w/parked"))
        for ((done, modified, want) in cases) {
            val e = entry(State.Merged, null, done, "/w/parked")
            assertEquals(want, label(Gc.decide(e, { at(modified) }, false, p)), want)
        }
    }

    /** T949/T950: an ended agent's lock holds only through the idle window; `now` is 2026-10-20 12:00 UTC. */
    @OptIn(ExperimentalTime::class)
    @Test
    fun anEndedAgentsLockHoldsOnlyThroughTheIdleWindow() {
        val cases =
            listOf(
                Triple(State.Merged, ENDED, ENDED_IDLE) to "reclaim: by Claude Code / opus, agent 0193ab12 ended",
                Triple(State.Merged, ENDED, ENDED_BUSY) to "keep:locked by Claude Code / opus",
                Triple(State.Merged, ENDED, null) to "keep:locked by Claude Code / opus",
                Triple(State.Merged, UNSEEN, ENDED_IDLE) to "keep:locked by Claude Code / opus",
                Triple(State.Merged, ENDED_LIVE, ENDED_IDLE) to "keep:agent 0193ab12 is live",
                Triple(State.Dirty, ENDED, ENDED_IDLE) to "keep:locked by Claude Code / opus",
                Triple(State.Unmerged, ENDED, ENDED_IDLE) to "keep:locked by Claude Code / opus",
                Triple(State.Stale, ENDED, null) to "drop-record",
                Triple(State.Stale, THEIRS, null) to "drop-record",
                Triple(State.Stale, THEIRS_RECENT, null) to "keep:locked by Cursor / grok",
                Triple(State.Stale, THEIRS_UNDATED, null) to "keep:locked by Cursor / grok",
                Triple(State.Stale, "", null) to "keep:locked, owner unknown",
                Triple(State.Stale, UNSEEN, null) to "keep:locked by Claude Code / opus",
            )
        val now = Instant.parse("2026-10-20T12:00:00Z").epochSeconds * NANOS
        val p =
            GcPolicy(
                ME,
                IDLE.seconds,
                STALE_DAYS.days,
                now,
                setOf("0193ab12-live", "0193ab12-both"),
                setOf("0193ab12-gone", "0193ab12-both"),
            )
        for ((c, want) in cases) {
            val (state, locked, ago) = c
            val modified = ago?.let { now - it * NANOS }
            assertEquals(want, label(Gc.decide(entry(state, locked), { modified }, false, p)), "$c")
        }
    }
}
