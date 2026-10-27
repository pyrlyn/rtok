// Port of src/agents/junk_kinds.rs `a_worktree_in_a_hosts_pool_is_an_agent_worktree_and_the_main_checkout_is_not`.
package rtok.agents

import rtok.support.diskConfig
import rtok.sys.ProgressRunner
import rtok.sys.createDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CAP = 1L shl 20

class JunkWorktreesTest {
    private fun git(
        dir: String,
        vararg args: String,
    ) {
        val base = listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false")
        val (c, _) = ProgressRunner("", "git", base + args).quiet().runCapped(CAP)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    /** The worktree is made in Claude Code's own pool (`.claude/worktrees`), so its host is known without a store row (T289). */
    @Test
    fun aWorktreeInAHostsPoolIsAnAgentWorktreeAndTheMainCheckoutIsNot() {
        val (cfg, dir) = diskConfig("kinds-agent-wt")
        val repo = "$dir/repo".also(::createDirAll)
        git(repo, "init", "-q")
        git(repo, "commit", "-q", "--allow-empty", "-m", "m")
        git(repo, "worktree", "add", "-q", "--detach", "$repo/.claude/worktrees/w1")

        val found = JunkWorktrees.agentWorktrees(cfg, repo)

        assertEquals(1, found.size, "$found")
        assertEquals("claude", found[0].first)
        assertTrue(found[0].second.endsWith("/w1"))
        assertTrue(JunkWorktrees.agentWorktrees(cfg, dir).isEmpty(), "not a repository")
    }
}
