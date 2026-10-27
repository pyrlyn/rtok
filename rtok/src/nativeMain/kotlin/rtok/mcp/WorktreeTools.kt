// Port of src/mcp/worktrees.rs (T285, D34): MCP `worktree_add`, `worktree_list`, `worktree_remove`, `worktree_finish`
// (T952.1) and `worktree_adopt` (T289.2), the agent-facing side of `rtok worktree add|list|remove|finish|adopt`. They
// call the same functions as the CLI; the only difference is who the agent is: the MCP session's link (T283.1). The
// optional `agent` argument (T924) only fills a link that is missing or a guess. The optional `repo` says which
// repository to act in when the server runs elsewhere (for `worktree_finish` it is the GitHub `owner/name`).
package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import rtok.agents.TaskList
import rtok.config.ProjectRoot
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.plugin.sdk.ToolDef
import rtok.store.AgentDetail
import rtok.sys.join
import rtok.sys.samePath
import rtok.worktree.AddClaim
import rtok.worktree.Claim
import rtok.worktree.Finish
import rtok.worktree.FinishOptions
import rtok.worktree.WorktreeList
import rtok.worktree.WorktreeRemove

@Suppress("TooManyFunctions")
object WorktreeTools {
    /** serde_json `to_string`: every field, nulls included, unless the type skips it. */
    private val JSON =
        Json {
            encodeDefaults = true
        }

    private fun schema(s: String) = StrictJson.parse(s).jsonObject

    /** mcp.rs `with_repo`. */
    fun withRepo(schema: JsonObject): JsonObject {
        val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val repo = schema("""{"type":"string","description":"path of your repository, when the server runs elsewhere"}""")
        return JsonObject(schema + ("properties" to JsonObject(props + ("repo" to repo))))
    }

    val ADD =
        ToolDef(
            "worktree_add",
            "Create a git worktree for a task, locked and bound to this session's agent. Returns {path, branch, task, agent, note}; work only inside path.",
            withRepo(
                AgentTools.withAgent(
                    schema(
                        """{"type":"object","properties":{"task":{"type":"string","description":"task id, e.g. T12"},"slug":{"type":"string","description":"optional branch suffix"}},"required":["task"]}""",
                    ),
                ),
            ),
        )

    val LIST =
        ToolDef(
            "worktree_list",
            "Every worktree of this repository: path, branch, state, size, and the agent bound to it with its host and live/idle/ended state.",
            withRepo(schema("""{"type":"object","properties":{}}""")),
        )

    val REMOVE =
        ToolDef(
            "worktree_remove",
            "Remove this agent's own finished worktree by path or task id, with its branch when merged. Refuses a dirty or unmerged worktree " +
                "(keep_branch removes an unmerged clean one and keeps the branch), another agent's, and the cwd. Never forces.",
            withRepo(
                AgentTools.withAgent(
                    schema("""{"type":"object","properties":{"path":{"type":"string"},"task":{"type":"string"},"keep_branch":{"type":"boolean"}}}"""),
                ),
            ),
        )

    val FINISH =
        ToolDef(
            "worktree_finish",
            "Send this agent's worktree at path to review: commit leftovers (no credential files), push, open or reuse the PR, squash " +
                "auto-merge unless no_auto_merge, unlock. Never closes the task.",
            schema(
                """{"type":"object","properties":{"path":{"type":"string","description":"the worktree or a directory inside it"},""" +
                    """"message":{"type":"string","description":"commit message for uncommitted work"},"repo":{"type":"string","description":"owner/name for gh"},""" +
                    """"no_auto_merge":{"type":"boolean"}},"required":["path"]}""",
            ),
        )

    val ADOPT =
        ToolDef(
            "worktree_adopt",
            "Bind a worktree your host made (not worktree_add) to this session's agent: the one holding path (default: the server's cwd). " +
                "Returns {path, task, origin, locked}. task names it when the branch cannot (a detached HEAD). Never takes another agent's.",
            AgentTools.withAgent(
                schema("""{"type":"object","properties":{"path":{"type":"string","description":"the worktree or a directory inside it"},"task":{"type":"string"}}}"""),
            ),
        )

    /** In mcp.rs order, right after `whoami`. */
    val DEFS = listOf(ADD, LIST, REMOVE, FINISH, ADOPT)

    fun handles(name: String) = DEFS.any { it.name == name }

    fun call(
        cx: Runtime,
        session: AgentSession,
        name: String,
        args: JsonObject,
    ): String =
        when (name) {
            "worktree_add" -> add(cx, session.agentFor(args).first, args)
            "worktree_remove" -> remove(cx, session.agentFor(args).first, args)
            "worktree_finish" -> finish(cx, session.agent().first, args)
            "worktree_adopt" -> adopt(cx, session.agentFor(args).first, args)
            else -> list(cx, args)
        }

    private fun arg(
        args: JsonObject,
        key: String,
    ): String? = Protocol.str(args, key)?.takeIf { it.isNotEmpty() }

    /** The directory a call acts in: `repo` (relative to the server's cwd), else the cwd itself. */
    private fun baseDir(args: JsonObject): String {
        val cwd = rtok.sys.cwd()
        return arg(args, "repo")?.let { join(cwd, it) } ?: cwd
    }

    /**
     * A shared server runs in one checkout while its callers work in others, so a worktree made "here" can land in a
     * stranger's repository (T924). The caller's repository is the one its hooks recorded; an agent with none recorded
     * cannot be checked and is not refused.
     */
    @Suppress("ReturnCount")
    internal fun ensureCallersRepo(
        base: String,
        agent: AgentDetail,
    ) {
        val mine = agent.cwd?.let { ProjectRoot.find(it) } ?: return
        val target = ProjectRoot.find(base) ?: return
        if (samePath(mine.main, target.main)) return
        error("$base is in repository ${target.main}, not yours (${mine.main}); pass `repo` with your repository's path")
    }

    fun add(
        cx: Runtime,
        agent: AgentDetail,
        args: JsonObject,
    ): String {
        val task = arg(args, "task") ?: error("`task` is required, e.g. T12")
        val cwd = baseDir(args)
        ensureCallersRepo(cwd, agent)
        val plan = AddClaim.add(cx.store, cwd, cx.config.worktree, task to arg(args, "slug"), agent to null, cx.config.plugins.graph.autoAddProjects)
        TaskList.writeFor(cwd, plan.path, plan.task)
        return buildJsonObject {
            put("path", plan.path)
            put("branch", plan.branch)
            put("task", plan.task)
            put("agent", agent.id)
            put("note", "work only inside `path`; remove it with `worktree_remove` when merged")
        }.toString()
    }

    fun adopt(
        cx: Runtime,
        agent: AgentDetail,
        args: JsonObject,
    ): String {
        val cwd = rtok.sys.cwd()
        val path = arg(args, "path")?.let { join(cwd, it) } ?: cwd
        val done = Claim.bind(cx.store, path, agent, null, arg(args, "task"), true, cx.config.plugins.graph.autoAddProjects)
        return JSON.encodeToString(rtok.worktree.Adopted.serializer(), done)
    }

    fun remove(
        cx: Runtime,
        agent: AgentDetail,
        args: JsonObject,
    ): String {
        val target = arg(args, "path") ?: arg(args, "task") ?: error("`path` or `task` is required")
        val keep = Protocol.bool(args, "keep_branch") ?: false
        val done = WorktreeRemove.forAgent(cx.store, baseDir(args), target, agent, null, keep)
        return JSON.encodeToString(rtok.worktree.Removed.serializer(), done)
    }

    fun finish(
        cx: Runtime,
        agent: AgentDetail,
        args: JsonObject,
    ): String {
        val path = arg(args, "path") ?: error("`path` is required: the task worktree")
        val opts = FinishOptions(arg(args, "message"), arg(args, "repo"), Protocol.bool(args, "no_auto_merge") ?: false)
        val done = Finish.forAgent(cx.store, join(rtok.sys.cwd(), path), agent, null, opts)
        return JSON.encodeToString(rtok.worktree.Finished.serializer(), done)
    }

    fun list(
        cx: Runtime,
        args: JsonObject,
    ): String {
        // A server launched in `$HOME` has no repository of its own; list the worktrees of the client root that lies in
        // one. Without a usable root the error names what to do, never git's `fatal: not a git repository`.
        val dir =
            rtok.projectDir(
                baseDir(args),
                rtok.plugins.read.Roots
                    .clientRoots(),
            )
        val rows = WorktreeList.rows(dir)
        WorktreeList.attributeWithStore(rows, cx.store, cx.config.agents.idle)
        return JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(rtok.worktree.Row.serializer()), rows)
    }
}
