// Port of src/tasks/mod.rs: the task domain types every adapter, the CLI and the MCP tools share.
package rtok.tasks

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import rtok.plugin.sdk.debugStr
import rtok.store.TaskId
import rtok.store.checkPrefix

/** Priority when a task names none. `0` is the highest, `4` the lowest. */
const val DEFAULT_PRIORITY = 2

/** Highest priority number accepted. */
const val PRIORITY_MAX = 4

/** A claim whose agent was last seen longer ago than this can be taken. */
const val STALE_SECS = 30L * 60

class TaskException(
    message: String,
) : Exception(message)

internal fun bail(message: String): Nothing = throw TaskException(message)

/** The first ASCII letter of [project], upper-cased (`rtok` → `R`); null when it has none. */
fun defaultPrefix(project: String): String? = project.firstOrNull { it in 'a'..'z' || it in 'A'..'Z' }?.uppercaseChar()?.toString()

/** `[tasks] prefix` when set, else [defaultPrefix] of [project]. */
fun resolvePrefix(
    configured: String,
    project: String?,
): String {
    if (configured.isNotEmpty()) {
        checkPrefix(configured)
        return configured.uppercase()
    }
    return project?.let(::defaultPrefix)
        ?: bail("no task prefix: set [tasks] prefix, since the project name has no ASCII letter")
}

/** Where a task stands. `Done` and `Closed` both leave the plan; `Closed` is "won't do". */
@Serializable(with = Status.Ser::class)
enum class Status(
    val str: String,
) {
    Open("open"),
    InProgress("in-progress"),
    Done("done"),
    Closed("closed"),
    ;

    /** Still in the plan: listed by default and blocking a parent from closing. */
    val isActive get() = this == Open || this == InProgress

    override fun toString() = str

    companion object {
        /** Also takes the checklist words Claude Code and Codex use (`pending`, `in_progress`, `completed`). */
        fun parse(s: String): Status =
            when (val w = s.trim().lowercase().replace('_', '-')) {
                "open", "todo", "pending" -> Open
                "in-progress" -> InProgress
                "done", "completed" -> Done
                "closed", "wontdo", "won't-do", "not-planned" -> Closed
                else -> bail("unknown task status ${debugStr(w)}: use open, in-progress, done or closed")
            }
    }

    object Ser : KSerializer<Status> {
        override val descriptor = PrimitiveSerialDescriptor("Status", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Status,
        ) = encoder.encodeString(value.str)

        override fun deserialize(decoder: Decoder): Status {
            val s = decoder.decodeString()
            return entries.firstOrNull { it.str == s } ?: throw kotlinx.serialization.SerializationException("unknown variant ${debugStr(s)}")
        }
    }
}

/** The issue a task lives in on GitHub or GitLab. The rtok id is ours; the number is theirs. */
@Serializable
data class ExternalRef(
    val adapter: String,
    val number: Long,
    val url: String,
    @SerialName("node_id") val nodeId: String? = null,
)

/** What a caller supplies to create a task; the allocator adds the id. */
@Serializable
data class NewTask(
    val title: String,
    val description: String = "",
    val parent: TaskId? = null,
)

/** One task as every adapter returns it, and the JSON shape of the CLI and MCP tools. */
@Serializable
data class Task(
    val id: TaskId,
    val title: String,
    val description: String = "",
    val status: Status,
    val parent: TaskId? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val external: ExternalRef? = null,
    val priority: Int = DEFAULT_PRIORITY,
    val assignee: String? = null,
    @SerialName("blocked_by") val blockedBy: List<TaskId> = emptyList(),
    @SerialName("conflicts_with") val conflictsWith: List<TaskId> = emptyList(),
    val parallel: Boolean = false,
) {
    companion object {
        /** An open task with default priority and nobody assigned. `parent` follows the id. */
        fun open(
            id: TaskId,
            title: String,
            description: String,
            now: Long,
        ) = Task(id = id, title = title, description = description, status = Status.Open, parent = id.parent(), createdAt = now, updatedAt = now)
    }
}

/** The JSON shape of tasks: optionals and defaults left out, like serde's skips. */
val TASK_JSON = Json { ignoreUnknownKeys = true }

private fun flattened(
    task: Task,
    extra: Map<String, kotlinx.serialization.json.JsonElement>,
) = JsonObject(TASK_JSON.encodeToJsonElement(task).jsonObject + extra)

/** One ready task. `stale` means the assignee's `agents.last_seen` is old enough to take. */
data class ReadyItem(
    val task: Task,
    val stale: Boolean,
    /** Conflict partners in progress right now: a warning to the claimer, not a refusal. */
    val conflictsInProgress: List<TaskId> = emptyList(),
) {
    fun toJson(): JsonObject {
        val extra = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("stale" to JsonPrimitive(stale))
        if (conflictsInProgress.isNotEmpty()) extra["conflicts_in_progress"] = JsonArray(conflictsInProgress.map { JsonPrimitive(it.toString()) })
        return flattened(task, extra)
    }
}

/** What `claim` did. `changed` is false when this agent already holds the task. */
data class ClaimOutcome(
    val task: Task,
    val changed: Boolean,
) {
    fun toJson() = flattened(task, mapOf("changed" to JsonPrimitive(changed)))
}

/** Why `claim` refused a task. Callers that try the next ready task match on this. */
sealed class ClaimConflict(
    message: String,
) : Exception(message) {
    class Already(
        val id: TaskId,
        val assignee: String,
    ) : ClaimConflict("$id is claimed by $assignee")

    class NotClaimable(
        val id: TaskId,
        val status: Status,
    ) : ClaimConflict("$id is $status and cannot be claimed")

    class Blocked(
        val id: TaskId,
        val by: TaskId,
    ) : ClaimConflict("$id is blocked by $by")
}
