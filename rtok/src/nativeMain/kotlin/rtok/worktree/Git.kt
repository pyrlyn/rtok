// Port of the `git worktree list --porcelain -z` half of src/worktree (mod.rs `parse_porcelain`, git.rs `list`).
package rtok.worktree

import rtok.sys.ProgressRunner

/** One `git worktree list --porcelain` record. */
data class WorktreeRecord(
    val path: String, val head: String? = null, val branch: String? = null, val bare: Boolean = false,
    val detached: Boolean = false, val locked: String? = null, val prunable: String? = null,
)

object Git {
    /** NUL-separated attributes; a `worktree <path>` line opens a record, unknown keys are skipped. */
    fun parsePorcelain(out: ByteArray): List<WorktreeRecord> {
        val records = ArrayList<WorktreeRecord>()
        for (attr in out.decodeToString().split('\u0000')) {
            val sp = attr.indexOf(' ')
            val key = if (sp < 0) attr else attr.substring(0, sp)
            val value = if (sp < 0) "" else attr.substring(sp + 1)
            if (key == "worktree") { records += WorktreeRecord(value); continue }
            val r = records.lastOrNull() ?: continue
            records[records.size - 1] = when (key) {
                "HEAD" -> r.copy(head = value)
                "branch" -> r.copy(branch = value.removePrefix("refs/heads/"))
                "bare" -> r.copy(bare = true)
                "detached" -> r.copy(detached = true)
                "locked" -> r.copy(locked = value)
                "prunable" -> r.copy(prunable = value)
                else -> r
            }
        }
        return records
    }

    /** The worktrees of [repo]'s repository; fails when git is missing or [repo] is not a checkout. */
    fun list(repo: String): List<WorktreeRecord> {
        val (out, _) = ProgressRunner("", "git", listOf("-C", repo, "worktree", "list", "--porcelain", "-z")).quiet().runCapped(16L shl 20)
        check(out.exitCode == 0) { "git worktree list: ${out.stderr.decodeToString().trim()}" }
        return parsePorcelain(out.stdout)
    }
}
