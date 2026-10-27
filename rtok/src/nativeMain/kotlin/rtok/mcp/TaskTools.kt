// Port of src/mcp/tasks.rs (T441.6): `task_create`, `task_list`, `task_get`, `task_status`, `task_next` and the
// claim/edge tools, the agent-facing side of `rtok task …`. Same `TaskProject` calls as the CLI, answered with the
// JSON its `--json` prints.
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.plugin.sdk.ToolDef
import rtok.store.TaskId
import rtok.tasks.NewTask
import rtok.tasks.Status
import rtok.tasks.TASK_JSON
import rtok.tasks.Task
import rtok.tasks.TaskException
import rtok.tasks.TaskProject
import rtok.tasks.block
import rtok.tasks.claim
import rtok.tasks.conflict
import rtok.tasks.release
import rtok.tasks.requireAgent
import rtok.tasks.setParallel
import rtok.tasks.setPriority
import rtok.tasks.taskFilter

object TaskTools {
    private const val MAX_LEVEL = 255L

    private fun def(
        name: String,
        description: String,
        schema: String,
    ) = ToolDef(name, description, StrictJson.parse(schema).jsonObject)

    val DEFS =
        listOf(
            def(
                "task_create",
                "Add a task to this project's plan under the next free id (A12, or A12.3 under parent). Returns the task.",
                """{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"parent":{"type":"string","description":"parent task id, e.g. A12"}},"required":["title"]}""",
            ),
            def(
                "task_list",
                "This project's tasks: active ones by default, subtasks after their parent. status narrows to those statuses (open, in-progress, done, closed); all adds finished ones.",
                """{"type":"object","properties":{"status":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"},"parent":{"type":"string"}}}""",
            ),
            def(
                "task_get",
                "One task with its description and the ids of its subtasks.",
                """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
            ),
            def(
                "task_status",
                "Read a task's status, or set it to open, in-progress, done or closed. Finishing a task with active subtasks is refused unless force. report is the closing report of a task set to done or closed (plan/ folders keep it in done.md).",
                """{"type":"object","properties":{"id":{"type":"string"},"status":{"type":"string"},"force":{"type":"boolean"},"report":{"type":"string"}},"required":["id"]}""",
            ),
            def(
                "task_next",
                "The first ready task: free or stale, not blocked by an active task. A leaf when the plan has no blockers. null when nothing is ready.",
                """{"type":"object","properties":{}}""",
            ),
            def(
                "task_ready",
                "Tasks that can be claimed, highest priority first (0 before 2). stale is true when the assignee was last seen more than 30 minutes ago.",
                """{"type":"object","properties":{}}""",
            ),
            def(
                "task_claim",
                "Claim a task for this agent (in-progress). Omit id to take the first ready task. changed is false when you already hold it. On GitHub and GitLab the assignee write is last-write-wins, not compare-and-set.",
                """{"type":"object","properties":{"id":{"type":"string"},"agent":{"type":"string","description":"rtok agent id; defaults to RTOK_AGENT_ID"}}}""",
            ),
            def(
                "task_release",
                "Clear the assignee and set the task open. Only the holder, unless force.",
                """{"type":"object","properties":{"id":{"type":"string"},"agent":{"type":"string"},"force":{"type":"boolean"}},"required":["id"]}""",
            ),
            def(
                "task_dep",
                "Record that id waits on blocker. A cycle is refused and nothing is written.",
                """{"type":"object","properties":{"id":{"type":"string"},"blocker":{"type":"string"}},"required":["id","blocker"]}""",
            ),
            def(
                "task_priority",
                "Set priority from 0 (highest) to 4. 2 is the default and is not stored.",
                """{"type":"object","properties":{"id":{"type":"string"},"level":{"type":"integer"}},"required":["id","level"]}""",
            ),
            def(
                "task_conflict",
                "Record that id and other touch the same files, so they should not run at once (symmetric; remove takes it off). task_ready flags a task whose conflict partner is in progress.",
                """{"type":"object","properties":{"id":{"type":"string"},"other":{"type":"string"},"remove":{"type":"boolean"}},"required":["id","other"]}""",
            ),
            def(
                "task_parallel",
                "Mark a task safe to run beside any other task; on false takes the mark off.",
                """{"type":"object","properties":{"id":{"type":"string"},"on":{"type":"boolean","description":"default true"}},"required":["id"]}""",
            ),
        )

    fun handles(name: String) = name.startsWith("task_")

    private fun arg(
        args: JsonObject,
        key: String,
    ) = Protocol.str(args, key)?.takeIf { it.isNotEmpty() }

    private fun flag(
        args: JsonObject,
        key: String,
        default: Boolean = false,
    ) = Protocol.bool(args, key) ?: default

    private fun id(args: JsonObject) = TaskId.parse(arg(args, "id") ?: fail("`id` is required, e.g. A12"))

    private fun need(
        args: JsonObject,
        key: String,
    ) = arg(args, key) ?: fail("`$key` is required")

    private fun fail(message: String): Nothing = throw TaskException(message)

    private fun Task.json() = TASK_JSON.encodeToJsonElement(this)

    private fun statuses(v: JsonElement?): List<String> =
        when {
            v is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            v is JsonPrimitive && v.isString -> v.content.split(',').map { it.trim() }
            else -> emptyList()
        }

    /** The answer of the task tool [name]. The config is read for the cwd now: `roots/list` can move it after start. */
    fun call(
        cx: Runtime,
        name: String,
        args: JsonObject,
    ): String {
        val cfg = Config.loadWith(null, null)
        val project = TaskProject.open(cfg.tasks, rtok.sys.cwd())
        val store = cx.store
        val out: JsonElement =
            when (name) {
                "task_create" -> {
                    val title = need(args, "title")
                    project.create(store, NewTask(title, arg(args, "description").orEmpty(), arg(args, "parent")?.let(TaskId::parse))).json()
                }

                "task_list" -> {
                    JsonArray(project.adapter.list(taskFilter(statuses(args["status"]), flag(args, "all"), arg(args, "parent"))).map { it.json() })
                }

                "task_get" -> {
                    project.show(id(args)).toJson()
                }

                "task_status" -> {
                    project.status(store, id(args), arg(args, "status")?.let(Status::parse), flag(args, "force"), arg(args, "report")).json()
                }

                "task_next" -> {
                    project.next(store)?.json() ?: JsonNull
                }

                "task_ready" -> {
                    JsonArray(project.ready(store).map { it.toJson() })
                }

                "task_claim" -> {
                    val agent = requireAgent(store, arg(args, "agent"))
                    project.claim(store, arg(args, "id")?.let(TaskId::parse), agent).toJson()
                }

                "task_release" -> {
                    val agent = requireAgent(store, arg(args, "agent"))
                    project.release(store, id(args), agent, flag(args, "force")).json()
                }

                else -> {
                    edit(project, name, args)
                }
            }
        return out.toString()
    }

    /** The plan edits that need no store: blockers, conflicts, the parallel mark and priority. */
    private fun edit(
        project: TaskProject,
        name: String,
        args: JsonObject,
    ): JsonElement =
        when (name) {
            "task_dep" -> {
                project.block(id(args), TaskId.parse(need(args, "blocker"))).json()
            }

            "task_conflict" -> {
                project.conflict(id(args), TaskId.parse(need(args, "other")), flag(args, "remove")).json()
            }

            "task_parallel" -> {
                project.setParallel(id(args), flag(args, "on", default = true)).json()
            }

            "task_priority" -> {
                val level = Protocol.long(args, "level")?.takeIf { it >= 0 } ?: fail("level is required, 0 to 4")
                if (level > MAX_LEVEL) fail("level is outside 0–4")
                project.setPriority(id(args), level.toInt()).json()
            }

            else -> {
                fail("unknown tool $name")
            }
        }
}
