// Port of the src/worktree/add.rs tests; the Windows-only ones are dropped (no Windows target).
package rtok.worktree

import rtok.config.Worktree
import rtok.support.tmpDir
import rtok.sys.ProgressRunner
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "Claude Code / sonnet"

class AddTest {
    private fun planIn(
        main: String,
        root: String,
        id: Pair<String, String?>,
        owner: String,
    ) = Add.plan(main, root, listOf("/purged"), id, owner to null, "2026-09-22")

    private fun err(op: () -> Unit) = assertFailsWith<WorktreeException> { op() }.message ?: ""

    @Test fun theRootHoldsTheWorktreeAndTheNamesFollowTheTask() {
        val dir = tmpDir("wt-add")
        val main = "$dir/apps/rtok"
        val root = "$dir/home/.rtok/worktrees"
        createDirAll(main)
        createDirAll("$dir/_worktrees")
        val p = planIn(main, root, "T158" to "Worktree-Add", OWNER)
        assertEquals("$root/rtok-t158", p.path)
        assertEquals("t158-worktree-add", p.branch)
        assertEquals("Claude Code / sonnet | t158 | 2026-09-22", p.reason)
        val parsed = Owner.parse(p.reason)!!
        assertEquals(OWNER to "t158", parsed.owner to parsed.task)
        createDirAll("$root/rtok-t1")
        assertTrue("one worktree per task" in err { planIn(main, root, "t1" to null, OWNER) })
        assertTrue("is empty" in err { planIn(main, "", "t2" to null, OWNER) })
    }

    @Test fun aRelativeRootIsUnderTheMainCheckoutAndMayNotLeaveIt() {
        assertEquals(".rtok/worktrees", Worktree().root)
        val dir = tmpDir("wt-add-relative")
        val main = "$dir/apps/rtok"
        createDirAll(main)

        fun at(root: String) = planIn(main, root, "t1" to null, OWNER)
        assertEquals("$main/.rtok/worktrees/rtok-t1", at(".rtok/worktrees").path)
        assertEquals("$main/wt/rtok-t1", at("wt").path)
        assertEquals("$dir/elsewhere/rtok-t1", at("$dir/elsewhere").path)
        for (escaping in listOf("../x", "a/../../x")) assertTrue("outside the main checkout" in err { at(escaping) }, escaping)
    }

    @Test fun aRootedRootIsNeverInsideTheProject() {
        assertTrue(Add.inProject(".rtok/worktrees") && Add.inProject("../x"))
        assertFalse(Add.inProject("/r/_w"))
        assertEquals("/r/_w", Add.landing("/r/rtok", "/r/_w"))
    }

    @Test fun aKnownAgentIsTheFourthFieldOfTheLock() {
        val reason = Add.plan("/r/rtok", "/r/_w", emptyList(), "t1" to null, OWNER to "an-agent-id", "d").reason
        assertEquals("$OWNER | t1 | d | agent an-agent-id", reason)
        assertEquals("an-agent-id", Owner.parse(reason)?.agent)
    }

    @Test fun badInputIsRefusedBeforeGitRuns() {
        val cases =
            listOf(
                Triple("" to null, OWNER, "task id"),
                Triple("../t1" to null, OWNER, "task id"),
                Triple("-t1" to null, OWNER, "task id"),
                Triple("t1.lock" to null, OWNER, "task id"),
                Triple("t1" to "two words", OWNER, "slug"),
                Triple("t1" to null, "", "--owner"),
                Triple("t1" to null, "a | b", "--owner"),
                Triple("t1" to null, "Клод / sonnet", "--owner"),
            )
        for ((id, owner, names) in cases) assertTrue(names in err { planIn("/r/rtok", "/r/_w", id, owner) }, "$id $owner")
    }

    @Test fun aRootUnderATemporaryDirectoryIsRefusedUnlessTheRepositoryIsThereToo() {
        assertTrue("temporary directory" in err { planIn("/r/rtok", "/purged/x", "t1" to null, OWNER) })
        assertEquals("/purged/session/_worktrees/rtok-t1", planIn("/purged/session/rtok", "/purged/session/_worktrees", "t1" to null, OWNER).path)
    }
}
