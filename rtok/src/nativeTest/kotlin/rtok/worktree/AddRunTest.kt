// Own tests for add.rs `run` (fetch, create, undo), `ensure_ignored`, `ensure_room` and whoami.rs, on temp repositories.
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
private const val CAP = 1L shl 20
private const val MIB = 1L shl 20
private const val LOW_MIB = 10L
private const val MIN_MIB = 2048u

class AddRunTest {
    private fun err(op: () -> Unit) = assertFailsWith<WorktreeException> { op() }.message ?: ""

    private fun git(
        dir: String,
        vararg args: String,
    ): String {
        val base = listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false")
        val (c, _) = ProgressRunner("", "git", base + args).quiet().runCapped(CAP)
        check(c.exitCode == 0) { c.stderr.decodeToString() }
        return c.stdout.decodeToString().trim()
    }

    /** A clone `repo` of a bare `origin` with one commit on main. */
    private fun repo(tag: String): String {
        val dir = canonicalize(tmpDir(tag))!!
        git(dir, "init", "-q", "--bare", "-b", "main", "origin.git")
        git(dir, "clone", "-q", "$dir/origin.git", "repo")
        val repo = "$dir/repo"
        writeString("$repo/a.txt", "a\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "init")
        git(repo, "push", "-q", "origin", "HEAD:main")
        return repo
    }

    private val roomy = Seams({ 1L shl 40 }, Git::addLocked)

    @Test fun runCreatesALockedWorktreeAndIgnoresTheRtokDir() {
        val repo = repo("wt-add-run")
        writeString("$repo/.gitignore", "target")
        val (plan, undo) = Add.run(repo, Worktree(), roomy, "t7" to null, OWNER to null, temp = emptyList())
        undo.keep()
        assertEquals("$repo/.rtok/worktrees/repo-t7", plan.path)
        assertTrue(exists("${plan.path}/a.txt"))
        assertEquals("target\n.rtok/\n", readToString("$repo/.gitignore"))
        val rec = Git.list(repo).single { it.path == plan.path }
        assertEquals("t7", rec.branch)
        assertEquals(plan.reason, rec.locked)
    }

    @Test fun aFailedCreateLeavesNothingBehind() {
        val repo = repo("wt-add-undo")
        val broken =
            Seams({ 1L shl 40 }) { r, path, branch, reason, base ->
                Git.addLocked(r, path, branch, reason, base)
                throw WorktreeException("git died")
            }
        assertEquals("git died", err { Add.run(repo, Worktree(), broken, "t8" to null, OWNER to null, temp = emptyList()) })
        assertFalse(exists("$repo/.rtok/worktrees/repo-t8"))
        assertFalse(Git.hasBranch(repo, "t8"))
        assertEquals(1, Git.list(repo).size)
    }

    @Test fun aNearlyFullVolumeIsRefusedAndAnUnknownOnePasses() {
        val dir = tmpDir("wt-add-room")
        val full = Seams({ LOW_MIB * MIB }, Git::addLocked)
        assertTrue("only 10 MiB free" in err { Add.ensureRoom(full, "$dir/x/y", MIN_MIB) })
        Add.ensureRoom(full, "$dir/x/y", 0u)
        Add.ensureRoom(Seams({ throw rtok.sys.IoException(rtok.sys.IoException.Kind.Other, "no statfs") }, Git::addLocked), "$dir/x", 1u)
    }

    @Test fun aRuleInEffectIsLeftAloneAndNoGitignoreUsesInfoExclude() {
        val repo = repo("wt-add-ignore")
        Add.ensureIgnored(repo)
        assertTrue(readToString("$repo/.git/info/exclude").endsWith(".rtok/\n"))
        val before = readToString("$repo/.git/info/exclude")
        Add.ensureIgnored(repo)
        assertEquals(before, readToString("$repo/.git/info/exclude"))
    }

    @Test fun whoamiNamesTheRootHereAndHeldWorktrees() {
        val repo = repo("wt-whoami")
        val (plan, undo) = Add.run(repo, Worktree(), roomy, "t9" to null, OWNER to "agent-1234567890", temp = emptyList())
        undo.keep()
        val outside = WhoamiRun.run(repo, ".rtok/worktrees", null, null)
        assertEquals(Whoami(null, null, "$repo/.rtok/worktrees", null, emptyList()), outside)
        assertEquals("agent: -\nroot: $repo/.rtok/worktrees\nhere: -\n", WhoamiRun.toText(outside))
        val agent = rtok.store.AgentDetail("agent-1234567890", "agent-12", "claude", "s", null, null, 0, 0, null, null, null)
        val inside = WhoamiRun.run(plan.path, ".rtok/worktrees", agent, null)
        assertEquals(plan.path, inside.here)
        assertEquals(listOf(Mine(plan.path, "t9", "t9")), inside.worktrees)
        assertEquals(
            "agent: agent-12 (claude)\nroot: $repo/.rtok/worktrees\nhere: ${plan.path}\nworktree: ${plan.path} (t9)\n",
            WhoamiRun.toText(inside),
        )
        assertNull(WhoamiRun.run(tmpDir("wt-whoami-out"), "/w", null, null).here)
    }
}
