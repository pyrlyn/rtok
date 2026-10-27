// Port of the src/worktree/finish.rs tests and tests/worktree.rs `finish_commits_pushes_opens_the_pr_and_unlocks_only_the_caller_s_worktree`
// (T952.1), on temp repositories with a scripted gh.
package rtok.worktree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import rtok.config.Worktree
import rtok.store.Store
import rtok.store.agentDetail
import rtok.store.finishedWorktreeClaims
import rtok.store.hostId
import rtok.store.registerAgent
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.ProgressRunner
import rtok.sys.canonicalize
import rtok.sys.removeFile
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CAP = 1L shl 20
private const val URL = "https://example.test/pr/1"

class FinishTest {
    @Test fun theDefaultMessageNamesTheTaskAndTheSlug() {
        assertEquals("T952.1: worktree finish", Finish.messageFor("T952.1", "t952.1-worktree-finish"))
        assertEquals("T7: t7", Finish.messageFor("T7", "t7"))
        assertEquals("T7: other-name", Finish.messageFor("T7", "other-name"))
        assertEquals("fix-typo", Finish.messageFor(null, "fix-typo"))
    }

    private fun git(
        dir: String,
        vararg args: String,
    ): String {
        val (c, _) = ProgressRunner("", "git", listOf("-C", dir) + args).quiet().runCapped(CAP)
        check(c.exitCode == 0) { c.stderr.decodeToString() }
        return c.stdout.decodeToString().trim()
    }

    private fun repo(tag: String): Pair<String, String> {
        val dir = canonicalize(tmpDir(tag))!!
        git(dir, "init", "-q", "--bare", "-b", "main", "origin.git")
        git(dir, "clone", "-q", "$dir/origin.git", "repo")
        val repo = "$dir/repo"
        for ((k, v) in listOf("user.name" to "t", "user.email" to "t@t", "commit.gpgsign" to "false")) git(repo, "config", k, v)
        writeString("$repo/a.txt", "a\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "init")
        git(repo, "push", "-q", "-u", "origin", "HEAD:main")
        git(repo, "remote", "set-head", "origin", "main")
        return dir to repo
    }

    /** gh stand-in: `pr view` finds a PR once `pr create` made one; `pr merge` fails while [refuseAuto]. */
    private class FakeGh : GhRunner {
        val log = mutableListOf<String>()
        var pr = false
        var refuseAuto = false

        override fun run(
            dir: String,
            label: String,
            args: List<String>,
        ): String {
            log += args.joinToString(" ")
            return when (args.take(2).joinToString(" ")) {
                "pr view" -> if (pr) """{"url":"$URL","state":"OPEN"}""" else throw WorktreeException("gh pr view: no PR")
                "pr create" -> "Creating pull request\n$URL".also { pr = true }
                "pr merge" -> if (refuseAuto) throw WorktreeException("gh pr merge: auto-merge is not allowed") else ""
                else -> ""
            }
        }
    }

    @Test fun finishCommitsPushesOpensThePrAndUnlocksOnlyTheCallersWorktree() {
        installDb()
        val (dir, work) = repo("wt-finish")
        val store = Store.openInMemory()
        val host = store.hostId("claude")!!
        val me = store.agentDetail(store.registerAgent(host, "sess-me", null, null, null))!!
        val them = store.agentDetail(store.registerAgent(host, "sess-them", null, null, null))!!
        val gh = FakeGh()
        val wt = Worktree(seed = false)

        fun add(
            who: rtok.store.AgentDetail,
            task: String,
            slug: String,
        ) = AddClaim.add(store, work, wt, task to slug, who to null, false).path

        fun locked(path: String) = Git.list(work).single { canonicalize(it.path) == path }.locked != null

        fun finish(path: String) = Finish.forAgent(store, path, me, null, FinishOptions(), gh)

        fun err(path: String) = assertFailsWith<WorktreeException> { finish(path) }.message!!

        val mine = canonicalize(add(me, "t1", "fin"))!!
        writeString("$mine/b.txt", "b")
        writeString("$mine/.env", "TOKEN=x")
        assertTrue("credential files: .env" in err(mine), err(mine))
        assertTrue(locked(mine) && Git.pendingPaths(mine).size == 2)

        removeFile("$mine/.env")
        val done = finish(mine)
        assertEquals("t1: fin", done.committed)
        assertEquals(URL, done.pr)
        assertTrue(done.autoMerge && done.unlocked, "$done")
        assertFalse(locked(mine))
        assertEquals("t1: fin", git("$dir/origin.git", "log", "-1", "--format=%s", "t1-fin"))
        assertEquals(listOf(mine), store.finishedWorktreeClaims().map { it.first })

        gh.refuseAuto = true
        val again = finish(mine)
        assertNull(again.committed)
        assertFalse(again.autoMerge)
        assertTrue("auto-merge is not allowed" in again.warnings[0], "${again.warnings}")
        val json = Json.parseToJsonElement(Json.encodeToString(Finished.serializer(), again)).jsonObject
        assertFalse("committed" in json)
        assertEquals(1, gh.log.count { it.startsWith("pr create") }, "${gh.log}")
        assertTrue("pr create --head t1-fin --base main --fill-first" in gh.log, "${gh.log}")
        assertTrue("pr merge $URL --auto --squash" in gh.log, "${gh.log}")

        val calls = gh.log.size
        val theirs = canonicalize(add(them, "t2", "x"))!!
        writeString("$theirs/c.txt", "c")
        git(theirs, "add", "-A")
        git(theirs, "commit", "-q", "-m", "c")
        assertTrue("not finished" in err(theirs), err(theirs))
        assertTrue(locked(theirs))

        val fresh = canonicalize(add(me, "t3", "empty"))!!
        assertTrue("nothing to finish" in err(fresh), err(fresh))
        assertTrue("is the main checkout" in err(work), err(work))
        assertEquals(calls, gh.log.size, "refusals call no gh")
    }

    @Test fun textNamesThePrAndEveryWarning() {
        val text = Finished("/w", "t1", "t1: x", URL, false, true, listOf("w1")).toText()
        assertEquals("/w: $URL\ncommitted: t1: x\nauto-merge: off\nunlocked\nwarning: w1\n", text)
    }
}
