// Port of the src/worktree/claim.rs tests and lock_tests (T527, T934, T973).
package rtok.worktree

import rtok.store.LockWait
import rtok.store.Store
import rtok.store.claimWorktree
import rtok.store.endAgent
import rtok.store.hostId
import rtok.store.openWorktreeClaims
import rtok.store.registerAgent
import rtok.store.setAgentLastSeen
import rtok.support.holdWriteLock
import rtok.support.installDb
import rtok.support.tmpDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private const val MINUTE = 60L
private const val QUICK_BUSY = 20L
private const val SHORT_HOLD = 300L
private const val LONG_HOLD = 600L
private const val MANY = 100
private const val PAUSE = 20L
private const val FEW = 2
private const val BRIEF = 5L
private const val WAITED_MS = 100L

class ClaimTest {
    private fun storeWith(
        lastSeen: Long,
        ended: Boolean,
    ): Pair<Store, String> {
        installDb()
        val store = Store.openInMemory()
        val id = store.registerAgent(store.hostId("claude")!!, "s-held", null, null, null)
        store.setAgentLastSeen(id, lastSeen)
        if (ended) store.endAgent(id, lastSeen)
        return store to id
    }

    private fun locked(agent: String) = WorktreeRecord("", locked = "claude | t1 | 2026-10-01 | agent $agent")

    @Test fun anAgentFlagHoldingAProviderAndModelSaysItWantsAnId() {
        val err = assertFailsWith<WorktreeException> { Claim.caller(null, "OpenAI / GPT-6") { null } }.message!!
        assertTrue(err.startsWith("--agent OpenAI / GPT-6: not an rtok agent id") && "rtok agents whoami" in err, err)
    }

    @Test fun anEndedHolderDoesNotBlockAdopt() {
        val (store, id) = storeWith(rtok.log.now(), true)
        assertFalse(Claim.heldAgainst(locked(id), "other", "other-agent", store)!!.holds)
    }

    @Test fun aHolderIdlePastStaleLockDoesNotBlockAdopt() {
        val (store, id) = storeWith(0, false)
        assertFalse(Claim.heldAgainst(locked(id), "other", "other-agent", store)!!.holds)
    }

    @Test fun aHolderSeenTodayRefusesAndNamesWhenTheyWereSeen() {
        val (store, id) = storeWith(rtok.log.now() - MINUTE, false)
        val held = Claim.heldAgainst(locked(id), "other", "other-agent", store)!!
        assertTrue(held.holds, "$held")
        val text = held.locked()
        assertTrue(text.startsWith("locked by claude | t1 | 2026-10-01 | agent ") && "holder seen" in text && text.endsWith(" ago"), text)
    }

    @Test fun aLockTheStoreCannotJudgeKeepsTheOldRefusal() {
        val record = WorktreeRecord("", locked = "Cursor / grok | t3 | 2026-09-22")
        val held = Claim.heldAgainst(record, "other", "other-agent", null)!!
        assertTrue(held.holds)
        assertNull(held.seenAgo)
        assertEquals("locked by Cursor / grok | t3 | 2026-09-22", held.locked())
    }

    private fun quickStore(tag: String): Triple<Store, String, String> {
        val db = "${tmpDir(tag)}/rtok.db"
        val store = Store.openWith(db, LockWait.STEADY.copy(busyMs = QUICK_BUSY))
        val agent = store.registerAgent(store.hostId("claude")!!, "s-lock", null, null, null)
        return Triple(store, db, agent)
    }

    @Test fun aClaimWaitsOutALockThatEndsInsideTheRetries() {
        val (store, db, agent) = quickStore("claim-lock-ends")
        val holder = holdWriteLock(db, SHORT_HOLD)
        ClaimStore.rememberWith(store, "/w/t934", agent, "t934", MANY, PAUSE)
        holder.join()
        assertEquals(1, store.openWorktreeClaims().size)
    }

    @Test fun aClaimThatOutlastsTheRetriesFailsTheCommand() {
        val (store, db, agent) = quickStore("claim-lock-stays")
        val holder = holdWriteLock(db, LONG_HOLD)
        val text = assertFailsWith<WorktreeException> { ClaimStore.rememberWith(store, "/w/t934", agent, "t934", FEW, BRIEF) }.message!!
        assertTrue("claim not stored" in text && "database is locked" in text, text)
        holder.join()
        assertTrue(store.openWorktreeClaims().isEmpty())
    }

    @Test fun aReleaseWaitsOutALockToo() {
        val (store, db, agent) = quickStore("release-lock-ends")
        store.claimWorktree("/w/t934", agent, "t934")
        val holder = holdWriteLock(db, SHORT_HOLD)
        val started = TimeSource.Monotonic.markNow()
        ClaimStore.release(store, "/w/t934")
        assertTrue(started.elapsedNow().inWholeMilliseconds >= WAITED_MS)
        holder.join()
        assertTrue(store.openWorktreeClaims().isEmpty())
    }
}
