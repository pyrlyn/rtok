// Port of src/mcp/paths.rs (T923): which directory the relative paths of `read`, `search`, `tree` and
// `outline` resolve against, and the note an answer carries when a wrong root could show.
package rtok.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugins.read.Roots as ReadRoots

object Paths {
    private val ROOTED = setOf("read", "search", "tree", "outline")

    /**
     * mcp.rs `invoke_rooted`: `whoami` and the agent tools answer against the linked agent; `read`/`search`/
     * `tree`/`outline` resolve relative paths against the caller's root (T923); the rest go to [Tools].
     */
    fun invoke(
        cx: rtok.plugin.Runtime,
        agents: AgentSession,
        name: String,
        args: JsonObject,
    ): Pair<String, Boolean> =
        when {
            AgentTools.handles(name) -> runCatching { AgentTools.call(cx, agents, name, args) }.fold({ it to true }) { (it.message ?: it.toString()) to false }
            !isRooted(name) -> Tools.invokeText(cx, name, args)
            else -> rooted(cx, agents, name, args)
        }

    private fun rooted(
        cx: rtok.plugin.Runtime,
        agents: AgentSession,
        name: String,
        args: JsonObject,
    ): Pair<String, Boolean> {
        val guard = agents.callerDir()?.let { ReadRoots.enterCaller(rtok.sys.cwd(), it) }
        try {
            val anchored = anchorOutline(name, args)
            val (text, ok) = Tools.invokeText(cx, name, anchored)
            return annotate(name, anchored, text, ok, guard != null) to ok
        } finally {
            guard?.close()
        }
    }

    /** Whether [name] takes a path relative to the caller's root. */
    fun isRooted(name: String) = name in ROOTED

    /** A relative (or absent) `path` resolves against a root; an absolute one does not. */
    private fun isRelative(args: JsonObject) = Protocol.str(args, "path")?.startsWith("/") != true

    /** `outline` resolves against the server's cwd inside the graph plugin, so a caller root makes its path absolute. */
    fun anchorOutline(
        name: String,
        args: JsonObject,
    ): JsonObject {
        val p = Protocol.str(args, "path")?.takeIf { name == "outline" && isRelative(args) }
        val root = p?.let { ReadRoots.callerRoot() }
        return if (p == null || root == null) args else JsonObject(args + ("path" to JsonPrimitive("${root.trimEnd('/')}/$p")))
    }

    /**
     * [text] plus the root it resolved against: always on a failure or an empty search, and on a success only
     * when the caller's root is unknown and the server's repository has other worktrees.
     */
    fun annotate(
        name: String,
        args: JsonObject,
        text: String,
        ok: Boolean,
        callerKnown: Boolean,
    ): String {
        val root = if (isRooted(name) && isRelative(args)) runCatching { ReadRoots.cwd() }.getOrNull() else null
        val empty = name == "search" && ok && text.isBlank()
        val note = "relative paths resolved against $root"
        return when {
            root == null -> text
            empty -> "no matches ($note)"
            !ok -> "$text ($note)"
            callerKnown || !ReadRoots.hasOtherWorktrees(root) -> text
            else -> "$text\n[$note]"
        }
    }
}
