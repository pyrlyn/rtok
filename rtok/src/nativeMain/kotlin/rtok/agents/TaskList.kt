// Port of src/agents/task_list.rs (T441.13.3, F19): a new worktree's Claude Code session shares one task list, named
// `<project>-<task>`, through `env.CLAUDE_CODE_TASK_LIST_ID` in the worktree's `.claude/settings.local.json`. Only our
// key is written, through the byte-preserving JSONC editor; a file we cannot edit safely is warned about and left alone.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.write
import rtok.mcp.config.Jsonc
import rtok.mcp.config.JsoncEdit
import rtok.projectName
import rtok.sys.eprint
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.worktree.Git

object TaskList {
    const val FILE = ".claude/settings.local.json"
    const val KEY = "CLAUDE_CODE_TASK_LIST_ID"

    /** Writes the task list id for the worktree at [dest]; [cwd] names the project. Fail open: only warnings. */
    fun writeFor(
        cwd: String,
        dest: String,
        task: String,
    ) {
        val project = projectName(cwd) ?: return
        // An untracked file would make `worktree remove` refuse the worktree as dirty, and a host config must never
        // reach a commit, so a repository that does not ignore it gets nothing.
        if (!Git.isIgnored(dest, FILE)) {
            eprint("warning: task list id not set: $FILE is not git-ignored in $dest\n")
            return
        }
        runCatching { set(join(dest, FILE), "$project-$task") }
            .onFailure { eprint("warning: task list id not set: ${it.message}\n") }
    }

    /** Sets our key in [path]. @throws IllegalStateException when the file must be left untouched. */
    internal fun set(
        path: String,
        id: String,
    ) {
        val raw = if (exists(path)) readToString(path) else ""
        refusal(raw, path, id)?.let { error("$path $it; left untouched") }
        val text = JsoncEdit.set(path, raw, listOf("env"), KEY, JsonPrimitive(id))
        if (text == raw) return
        write(Apply(yes = true), path, text, "env.$KEY: $id")
    }

    /** Why [raw] must not be edited: not an object, an `env` of another shape, or another id the user set. */
    private fun refusal(
        raw: String,
        path: String,
        id: String,
    ): String? {
        if (Jsonc.stripComments(raw).isBlank()) return null
        val root = runCatching { Jsonc.parse(raw) }.getOrElse { throw IllegalStateException("$path: ${it.message}", it) }
        val env = (root as? JsonObject)?.get("env")
        return when {
            root !is JsonObject -> "is not a JSON object"
            env == null -> null
            env !is JsonObject -> "has an `env` that is not an object"
            env[KEY]?.let { it != JsonPrimitive(id) } == true -> "already sets another task list id"
            else -> null
        }
    }
}
