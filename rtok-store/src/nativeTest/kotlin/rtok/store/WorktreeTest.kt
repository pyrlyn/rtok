package rtok.store

import kotlin.test.*
import kotlin.time.Duration.Companion.days

/** Port of worktree_claims.rs and worktree_pending.rs tests. */
class WorktreeTest {
    private val STALE_LOCK = 7.days
    private val NOW = 1_800_000_000L
    private val s = Store.openInMemory()
    private val claude = s.hostId("claude")!!

    private fun agent(session: String) = s.registerAgent(claude, session, null, null, null)

    @Test fun anEndedHoldersClaimDoesNotHold() {
        val a = agent("s-a")
        s.claimWorktree("/w/x", a, "t1")
        s.endAgent(a, NOW)
        val h = s.worktreeClaimHolder(a)!!
        assertFalse(h.holds(NOW, STALE_LOCK), "$h")
    }

    @Test fun aHolderIdlePastStaleLockDoesNotHold() {
        val a = agent("s-a")
        s.claimWorktree("/w/x", a, "t1")
        s.setAgentLastSeen(a, NOW - STALE_LOCK.inWholeSeconds - 1)
        assertFalse(s.worktreeClaimHolder(a)!!.holds(NOW, STALE_LOCK))
    }

    @Test fun aHolderSeenTodayHoldsAndNamesTheirLastSeen() {
        val a = agent("s-a")
        s.claimWorktree("/w/x", a, "t1")
        s.setAgentLastSeen(a, NOW - 60)
        val h = s.worktreeClaimHolder(a)!!
        assertTrue(h.holds(NOW, STALE_LOCK))
        assertEquals(60L, h.seenAgo(NOW))
    }

    @Test fun aClaimUpsertsPerPath() {
        val a = agent("s-a")
        val b = agent("s-b")
        s.claimWorktree("/w/x", a, "t1")
        s.claimWorktree("/w/y", a, "t2")
        s.claimWorktree("/w/x", b, "t1")
        assertEquals(listOf("/w/x" to b, "/w/y" to a), s.openWorktreeClaims().sortedBy { it.first })
        s.releaseWorktreeClaim("/w/x")
        assertEquals(listOf("/w/y" to a), s.openWorktreeClaims())
        assertNull(s.sessionModel("s-a"))
    }

    @Test fun aFinishedClaimIsListedUntilReleasedOrClaimedAgain() {
        val a = agent("s-a")
        s.claimWorktree("/w/x", a, "t1")
        s.claimWorktree("/w/y", a, "t2")
        assertTrue(s.finishWorktreeClaim("/w/x"))
        assertFalse(s.finishWorktreeClaim("/w/z"))
        assertEquals(listOf("/w/x" to "t1"), s.finishedWorktreeClaims())
        s.claimWorktree("/w/x", a, "t1")
        assertTrue(s.finishedWorktreeClaims().isEmpty())
        assertTrue(s.finishWorktreeClaim("/w/y"))
        s.releaseWorktreeClaim("/w/y")
        assertTrue(s.finishedWorktreeClaims().isEmpty())
    }

    @Test fun movingAWorktreeMovesItsClaimAndParkedRows() {
        val a = agent("s-a")
        s.claimWorktree("/w/x", a, "t1")
        s.claimWorktree("/r/x", a, "old")
        s.releaseWorktreeClaim("/r/x")
        s.claimWorktree("/w/y", a, "t2")
        s.addPendingWorktree("/w/p", "t3")
        s.moveWorktreeRows("/w/x", "/r/x")
        s.moveWorktreeRows("/w/p", "/r/p")
        assertEquals(listOf("/r/x" to a, "/w/y" to a), s.openWorktreeClaims().sortedBy { it.first })
        assertEquals(listOf("/r/p" to "t3"), s.pendingWorktrees())
    }

    @Test fun aParkedWorktreeIsTakenOnce() {
        s.addPendingWorktree("/w/x", "t1")
        s.addPendingWorktree("/w/x", "t2")
        s.addPendingWorktree("/w/y", "t3")
        assertEquals(listOf("/w/x" to "t2", "/w/y" to "t3"), s.pendingWorktrees().sortedBy { it.first })
        assertTrue(s.takePendingWorktree("/w/x"))
        assertFalse(s.takePendingWorktree("/w/x"))
        assertEquals(1, s.pendingWorktrees().size)
    }

    @Test fun releasingAWorktreeDropsItsParkedRow() {
        s.addPendingWorktree("/w/x", "t1")
        s.releaseWorktreeClaim("/w/x")
        assertTrue(s.pendingWorktrees().isEmpty())
    }

    @Test fun samePathIgnoresRedundantSeparators() {
        assertTrue(
            s.run {
                addPendingWorktree("/w//x/", "t")
                takePendingWorktree("/w/x")
            },
        )
    }
}
