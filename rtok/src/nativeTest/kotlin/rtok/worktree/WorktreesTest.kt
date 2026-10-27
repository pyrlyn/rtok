@file:Suppress("MagicNumber")

package rtok.worktree

import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of the tests in src/worktree/mod.rs and src/worktree/git.rs.
class WorktreesTest {
    @Test fun lockedByMatchesOnlyTheAgentNamedOnTheLock() {
        val dir = tmpDir("t958-lock")
        val (_, wt) = worktreeLayout(dir)
        val admin =
            rtok.sys
                .readToString("$wt/.git")
                .trim()
                .removePrefix("gitdir:")
                .trim()
        writeString("$admin/locked", "Grok / grok-4.7 | t958 | 2026-10-22 | agent aaaa-1\n")
        assertEquals(wt, Worktrees.lockedBy(wt, "aaaa-1"))
        assertNull(Worktrees.lockedBy(wt, "bbbb-2"))
        createDirAll("$wt/src")
        assertEquals(wt, Worktrees.lockedBy("$wt/src", "aaaa-1"))
        writeString("$admin/locked", "Grok / grok-4.7 | t958 | 2026-10-22\n")
        assertNull(Worktrees.lockedBy(wt, "aaaa-1"))
        removeDirAll(dir)
    }

    @Test fun porcelainZKeepsRawPathsReasonsAndEveryFlag() {
        val out =
            (
                "worktree /r\u0000HEAD aa\u0000branch refs/heads/main\u0000\u0000" +
                    "worktree /w/a b\nc\u0000HEAD bb\u0000branch refs/heads/t1-x\u0000locked Cursor / grok | t1 | 2026-09-22\u0000\u0000" +
                    "worktree /w/gone\u0000HEAD cc\u0000detached\u0000locked\u0000prunable gitdir file points to non-existent location\u0000\u0000" +
                    "worktree /bare\u0000bare\u0000"
            ).encodeToByteArray()
        val r = Git.parsePorcelain(out)
        assertEquals(4, r.size)
        assertEquals("main" to null, r[0].branch to r[0].locked)
        assertEquals("/w/a b\nc", r[1].path)
        assertEquals("Cursor / grok" to "t1", r[1].owner()?.let { it.owner to it.task })
        assertTrue(r[2].detached && r[2].branch == null && r[2].prunable != null)
        assertEquals("", r[2].locked)
        assertTrue(r[3].bare && r[3].head == null)
        assertTrue(Git.parsePorcelain(ByteArray(0)).isEmpty())
    }

    @Test fun parMapKeepsTheInputOrder() {
        val items = (0L until 200L).toList()
        val want = items.map { it.toInt() to it * 2 }
        assertEquals(
            want,
            Worktrees.parMap(items) { i, n ->
                platform.posix.usleep((200 - n).coerceAtLeast(0).toUInt())
                i to n * 2
            },
        )
        assertTrue(Worktrees.parMap(emptyList<Long>()) { _, n -> n }.isEmpty())
    }

    @Test fun aLockHoldsAgainstEveryoneButItsNamedOwner() {
        for ((locked, me, held) in listOf(
            Triple(null, "me", false),
            Triple("me | t1 | 2026-09-22", "me", false),
            Triple("me | t1 | 2026-09-22", "you", true),
            Triple("me | t1 | 2026-09-22", null, true),
            Triple("", "me", true),
            Triple("me | t1", "me", true),
            Triple("me |  | 2026-09-22", "me", true),
        )) {
            assertEquals(held, WorktreeRecord("", locked = locked).heldAgainst(me), "$locked / $me")
        }
    }

    @Test fun lockReasonV1AndV2() {
        for ((reason, agent) in listOf(
            "me | t1 | 2026-09-22" to listOf(null),
            "me | t1 | 2026-09-22 | agent 0193ab12-7" to listOf("0193ab12-7"),
            "me | t1 | 2026-09-22 | 0193ab12" to null,
            "me | t1 | 2026-09-22 | agent " to null,
            "me | t1 | 2026-09-22 | agent a | x" to null,
        )) {
            val parsed = Owner.parse(reason)
            assertEquals(agent, parsed?.let { listOf(it.agent) }, reason)
            parsed?.let { assertEquals(reason, it.reason()) }
        }
    }

    @Test fun aClaimTakesNoLockOrItsOwn() {
        for ((locked, ok) in listOf(
            null to true,
            "me | t1 | 2026-09-22" to true,
            "you | t1 | 2026-09-22" to false,
            "me | t1 | 2026-09-22 | agent A1" to true,
            "you | t1 | 2026-09-22 | agent A1" to true,
            "me | t1 | 2026-09-22 | agent B2" to false,
            "" to false,
        )) {
            assertEquals(ok, WorktreeRecord("", locked = locked).claimableBy("me", "A1"), "$locked")
        }
    }

    @Test fun classification() {
        for ((facts, state) in listOf(
            Facts(false, true, true) to State.Stale,
            Facts(false, false, false) to State.Stale,
            Facts(true, true, true) to State.Dirty,
            Facts(true, true, false) to State.Dirty,
            Facts(true, false, true) to State.Merged,
            Facts(true, false, false) to State.Unmerged,
        )) {
            assertEquals(state, Worktrees.classify(facts), "$facts")
        }
    }

    @Test fun aFailingCallCarriesGitsOwnMessage() {
        val dir = tmpDir("wt-git-fail")
        val err = assertFailsWith<GitError> { Git.gitOk(dir, "rev-parse", "--git-dir") }
        assertTrue(err.message.orEmpty().startsWith("git rev-parse --git-dir: "), err.message)
        assertFalse(runCatching { Git.isDirty(dir) }.getOrDefault(false))
    }

    @Test fun aLabelledCallReturnsTheSameOutputOffATerminal() {
        val dir = tmpDir("wt-git-slow")
        val quiet = Git.gitOk(dir, "--version")
        val slow = Git.gitSlow(dir, "checking git…", "--version")
        assertEquals(quiet.stdout.decodeToString() to quiet.stderr.decodeToString(), slow.stdout.decodeToString() to slow.stderr.decodeToString())
    }
}
