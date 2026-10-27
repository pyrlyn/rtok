// Port of src/worktree/origin.rs (T289): which tool created a worktree, read from where it lives. The pools are the
// ones the hosts document (`research.md` §18.3, §26); a path under none of them is `other`.
package rtok.worktree

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import rtok.sys.components
import rtok.sys.join
import rtok.sys.pathStartsWith

object Origin {
    /** `(parent, child)` directory names, consecutive in the path, and the origin they name. */
    private val POOLS =
        listOf(
            Triple(".cursor", "worktrees", "cursor"),
            Triple(".windsurf", "worktrees", "windsurf"),
            Triple(".codex", "worktrees", "codex"),
            Triple(".claude", "worktrees", "claude"),
            Triple(".kilo", "worktrees", "kilo"),
            Triple(".rtok", "worktrees", "rtok"),
            Triple("conductor", "workspaces", "conductor"),
        )

    /**
     * Hosts that delete their own worktrees to stay under a cap (Cursor 25, Codex 15, Windsurf and Devin about 20). A
     * git lock there is untested against that eviction, so a claim stays in the store only (T289 Execution).
     */
    fun evicts(origin: String): Boolean = origin == "cursor" || origin == "windsurf" || origin == "codex"

    /** The rtok hosts whose agents work in a pool's worktrees and whose post-create script (T289.3) adopts them. */
    fun hosts(origin: String): List<String> =
        when (origin) {
            "cursor" -> listOf("cursor")
            "windsurf" -> listOf("windsurf", "devin")
            "kilo" -> listOf("kilo")
            else -> emptyList()
        }

    @OptIn(ExperimentalForeignApi::class)
    fun of(path: String): String = ofWith(path, platform.posix.getenv("CODEX_HOME")?.toKString())

    /** [codexHome] is `$CODEX_HOME`, which moves Codex's pool away from `~/.codex`. */
    fun ofWith(
        path: String,
        codexHome: String?,
    ): String {
        if (!codexHome.isNullOrEmpty() && pathStartsWith(path, join(codexHome, "worktrees"))) return "codex"
        val names = components(path).filter { it != "/" && it != ".." }
        return names
            .zipWithNext()
            .firstNotNullOfOrNull { (a, b) -> POOLS.firstOrNull { it.first == a && it.second == b }?.third }
            ?: "other"
    }
}
