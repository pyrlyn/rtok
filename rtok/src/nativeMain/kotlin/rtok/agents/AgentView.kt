// Port of src/agent_view.rs: agent and session rows shared by the model and the renderers.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import rtok.store.AgentDetail
import rtok.store.SessionTotals

/** `live`: seen within `[agents] idle`; `idle`: not ended, but quiet longer; `ended`. */
enum class AgentState(
    val label: String,
) {
    Live("live"),
    Idle("idle"),
    Ended("ended"),
    ;

    companion object {
        fun of(
            endedAt: Long?,
            lastSeen: Long,
            now: Long,
            idle: Long,
        ) = when {
            endedAt != null -> Ended
            now - lastSeen > idle -> Idle
            else -> Live
        }
    }
}

/** One agent with its sub-agents nested (`--json` of `agents show`, and each `agents sessions` row's `agent`). */
data class AgentView(
    val detail: AgentDetail,
    /** The session's newest model; null for a sub-agent or when no call recorded one. */
    val model: String?,
    /** The claimed worktree's directory name (T285), else `cwd` relative to its checkout. */
    val worktree: String?,
    /** Every worktree path this agent holds an open claim on (T285). */
    val worktrees: List<String>,
    /** Messages sent to this agent that it has not read (T287). */
    val unread: Long,
    val state: AgentState,
    val subAgents: List<AgentView>,
) {
    /** serde's `#[serde(flatten)] detail` followed by the view's own fields. */
    fun toJson(): JsonObject =
        JsonObject(
            Json.encodeToJsonElement(detail).jsonObject +
                mapOf(
                    "model" to str(model),
                    "worktree" to str(worktree),
                    "worktrees" to JsonArray(worktrees.map(::JsonPrimitive)),
                    "unread" to JsonPrimitive(unread),
                    "state" to JsonPrimitive(state.label),
                    "sub_agents" to JsonArray(subAgents.map { it.toJson() }),
                ),
        )
}

/** One `rtok agents sessions` row: the session's totals plus its agent, if registered. */
data class SessionView(
    val session: SessionTotals,
    val state: AgentState,
    val agent: AgentView?,
) {
    fun toJson(): JsonObject =
        JsonObject(
            Json.encodeToJsonElement(session).jsonObject +
                mapOf("state" to JsonPrimitive(state.label), "agent" to (agent?.toJson() ?: JsonNull)),
        )
}

private fun str(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull
