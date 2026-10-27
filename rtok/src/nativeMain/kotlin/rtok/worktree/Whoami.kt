// Port of src/worktree/whoami.rs (T411): who the agent is, where new worktrees go, which one it stands in and which it
// holds. No size scans, so it is cheap enough for the start of every task.
package rtok.worktree

import kotlinx.serialization.Serializable
import rtok.store.AgentDetail
import rtok.store.Store
import rtok.store.openWorktreeClaims
import rtok.sys.canon
import rtok.sys.pathStartsWith
import rtok.sys.samePath

@Serializable
data class Whoami(
    val agent: String?,
    val host: String?,
    val root: String,
    val here: String?,
    val worktrees: List<Mine>,
)

@Serializable
data class Mine(
    val path: String,
    val branch: String?,
    val task: String?,
)

private const val SHORT_ID = 8

object WhoamiRun {
    fun run(
        cwd: String,
        root: String,
        agent: AgentDetail?,
        store: Store?,
    ): Whoami {
        // Outside a repository there is still an agent and a root to report.
        val records = runCatching { Git.list(cwd) }.getOrDefault(emptyList())
        val linked = records.drop(1)
        val hereDir = canon(cwd)
        val here = linked.firstOrNull { pathStartsWith(hereDir, canon(it.path)) }?.path
        val claims = store?.let { runCatching { it.openWorktreeClaims() }.getOrNull() }.orEmpty()
        val id = agent?.id

        fun held(r: WorktreeRecord): Boolean {
            id ?: return false
            val byLock = r.owner()?.agent == id
            val dir = canon(r.path)
            val byClaim = r.locked == null && claims.any { (p, a) -> a == id && samePath(canon(p), dir) }
            return byLock || byClaim
        }
        return Whoami(
            agent?.id,
            agent?.host,
            records.firstOrNull()?.let { Add.landing(it.path, root) } ?: root,
            here,
            linked.filter(::held).map { Mine(it.path, it.branch, it.owner()?.task) },
        )
    }

    fun toText(w: Whoami): String =
        buildString {
            val agent = if (w.agent != null && w.host != null) "${w.agent.take(SHORT_ID)} (${w.host})" else "-"
            append("agent: $agent\nroot: ${w.root}\nhere: ${w.here ?: "-"}\n")
            w.worktrees.forEach { append("worktree: ${it.path} (${it.branch ?: "-"})\n") }
        }
}
