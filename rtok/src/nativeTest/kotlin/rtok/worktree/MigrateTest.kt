// Port of the tests/worktree.rs migrate tests (T660.5) on temp repositories and an in-memory store.
package rtok.worktree

import rtok.store.Store
import rtok.store.claimWorktree
import rtok.store.hostId
import rtok.store.openWorktreeClaims
import rtok.store.registerAgent
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.ProgressRunner
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CAP = 1L shl 20
private const val ME = "Claude Code / sonnet"
private const val REL = ".rtok/worktrees"

class MigrateTest {
    private fun git(
        dir: String,
        vararg args: String,
    ): String {
        val base = listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main")
        val (c, _) = ProgressRunner("", "git", base + args).quiet().runCapped(CAP)
        check(c.exitCode == 0) { c.stderr.decodeToString() }
        return c.stdout.decodeToString().trim()
    }

    /** `<tmp>/rtok` with one commit. */
    private fun repo(tag: String): Pair<String, String> {
        val tmp = canonicalize(tmpDir(tag))!!
        git(tmp, "init", "-q", "rtok")
        val work = "$tmp/rtok"
        writeString("$work/a.txt", "a\n")
        git(work, "add", "-A")
        git(work, "commit", "-q", "-m", "init")
        return tmp to work
    }

    private fun add(
        work: String,
        name: String,
        lock: String?,
    ) {
        val lockArgs = lock?.let { listOf("--lock", "--reason", it) } ?: emptyList()
        git(work, *(listOf("worktree", "add", "-q") + lockArgs + listOf("-b", "t-$name", "../wt-$name")).toTypedArray())
    }

    private fun action(
        rows: List<MigrateOutcome>,
        n: String,
    ) = rows.first { it.path.endsWith(n) }.let { it.action to it.note }

    @Test fun migrateMovesFreeWorktreesIntoTheRootAndSkipsHeldOnes() {
        installDb()
        val (tmp, work) = repo("wt-migrate")
        val store = Store.openInMemory()
        val claude = store.hostId("claude")!!
        val agent = store.registerAgent(claude, "s", null, null, null)
        val other = store.registerAgent(claude, "o", null, null, null)
        val mine = "$ME | t1 | 2026-10-14 | agent $agent"
        add(work, "mine", mine)
        add(work, "free", null)
        add(work, "theirs", "Other / model | t3 | 2026-10-14 | agent agent-other")
        for (n in listOf("claimed", "live", "subs", "here", "taken")) add(work, n, null)
        writeString("$tmp/wt-subs/.gitmodules", "")
        val root = "$work/$REL"
        createDirAll("$root/wt-taken")

        fun wt(n: String) = canonicalize("$tmp/wt-$n")!!
        store.claimWorktree(wt("mine"), agent, "t1")
        store.claimWorktree(wt("claimed"), other, "t4")
        val held = MigrateHeld(store.openWorktreeClaims(), emptyList(), listOf("agent-live" to "${wt("live")}/src"))
        val who = MigrateMe(agent, null)

        val dry = Migrate.run(wt("here"), REL, who, held, store, false)
        assertEquals("move", action(dry, "wt-mine").first)
        assertTrue(exists(wt("mine")) && !exists("$root/rtok-t1"))

        val done = Migrate.run(wt("here"), REL, who, held, store, true)
        assertEquals("main checkout", action(done, "rtok").second)
        assertEquals("move" to "moved", action(done, "wt-mine"))
        assertEquals("move" to "moved", action(done, "wt-free"))
        assertEquals("locked by Other / model", action(done, "wt-theirs").second)
        assertTrue(action(done, "wt-claimed").second.startsWith("claimed by agent"))
        assertTrue(action(done, "wt-live").second.startsWith("live session"))
        assertTrue("submodules" in action(done, "wt-subs").second)
        assertEquals("the worktree this command runs from", action(done, "wt-here").second)
        assertTrue(action(done, "wt-taken").second.endsWith("already exists"))
        assertTrue(done.none { it.failed })

        // Named by its lock's task; without a lock it keeps its directory name.
        val moved = canonicalize("$root/rtok-t1")!!
        assertTrue(isDir("$root/wt-free") && !exists("$tmp/wt-free"))
        assertEquals(mine, Git.list(work).single { canonicalize(it.path) == moved }.locked)
        assertTrue(store.openWorktreeClaims().any { (p, a) -> p == moved && a == agent }, "${store.openWorktreeClaims()}")
        assertEquals("", git(work, "status", "--porcelain"))

        val again = Migrate.run(work, REL, who, held, store, true)
        assertEquals("already in the root", action(again, "rtok-t1").second)
    }

    @Test fun migrateGivesOneTargetToOneWorktree() {
        val (_, work) = repo("wt-migrate-twin")
        for ((branch, dir) in listOf("a" to "../a/wt", "b" to "../b/wt")) git(work, "worktree", "add", "-q", "-b", branch, dir)
        val plan = Migrate.run(work, REL, MigrateMe(), MigrateHeld(), null, false)
        val notes = plan.drop(1).map { it.note }.sorted()
        assertEquals(2, notes.size, "$plan")
        assertTrue(notes[0].endsWith("is another worktree's target"), "$plan")
        assertEquals("outside the root", notes[1])
    }

    @Test fun theTableCountsOnlyTheMoves() {
        val rows = listOf(MigrateOutcome("/a", "/r/a", "move", "outside the root", false), MigrateOutcome("/b", null, "keep", "main checkout", false))
        assertTrue(Migrate.toTable(rows, false).endsWith("\ndry run: 1 to move, nothing changed; rerun with --yes\n"))
        assertTrue("dry run" !in Migrate.toTable(rows, true))
    }
}
