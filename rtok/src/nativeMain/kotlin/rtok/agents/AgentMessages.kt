// Port of src/agents/command.rs `whoami`, `status_caller`, `caller_agent`, `send` and `inbox` (T283, T287): the
// caller's identity and the agent message queue from a shell.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import rtok.agentMessageFrame
import rtok.projectName
import rtok.store.AgentDetail
import rtok.store.Store
import rtok.store.agentDetail
import rtok.store.inboxLimited
import rtok.store.liveAgents
import rtok.store.resolveAgent
import rtok.store.sendMessageOn
import rtok.store.shortAgentId

/** What a shell command acts for: [env] reads `RTOK_AGENT_ID` and the host session variables. */
class AgentMessages(
    private val store: Store,
    private val env: (String) -> String?,
    private val cwd: () -> String?,
) {
    private fun raw() = AgentLink.shellAgent(store, env)

    /** The caller's id; null for the user at a terminal. A set but unresolvable id is an error, never "user". */
    fun caller(): String? = raw()?.let { r -> runCatching { store.resolveAgent(r) }.getOrElse { throw AgentsFailure("RTOK_AGENT_ID $r: ${it.message}") } }

    /** `rtok agents whoami`. */
    fun whoami(): AgentDetail =
        raw()?.let { r -> runCatching { store.resolveAgent(r) }.getOrNull() }?.let(store::agentDetail)
            ?: throw AgentsFailure("not inside an agent session")

    /** `rtok agents status`: the caller's raw id, unresolved (the model resolves and refuses). */
    fun statusCaller(): String? = raw()

    private fun resolve(prefix: String) = runCatching { store.resolveAgent(prefix) }.getOrElse { throw AgentsFailure("agent $prefix: ${it.message}") }

    /** `rtok agents send`: one recipient, or every other live agent of the caller's project; one `sent` line each. */
    fun send(
        to: String?,
        text: String,
        allLive: Boolean,
        task: String?,
        idle: String,
    ): String {
        val from = caller()
        val targets = if (allLive) liveInProject(from, idle) else listOf(resolve(to.orEmpty()))
        return targets.joinToString("") { id -> "sent #${store.sendMessageOn(from, id, text, task)} to ${shortAgentId(id)}\n" }
    }

    private fun liveInProject(
        from: String?,
        idle: String,
    ): List<String> {
        fun key(p: String) = projectName(p) ?: p
        val here = (if (from != null) store.agentDetail(from)?.cwd else cwd())?.let(::key) ?: throw AgentsFailure("--all-live: the caller has no cwd to match a project by")
        return store
            .liveAgents(idle)
            .filter { it.id != from && it.cwd?.let(::key) == here }
            .map { it.id }
            .ifEmpty { throw AgentsFailure("no other live agent in this project") }
    }

    /** `rtok agents inbox`: the caller's own queue (marked read), or with [id] that agent's (marks nothing). */
    fun inbox(
        id: String?,
        unread: Boolean,
        task: String?,
        json: Boolean,
    ): String {
        val (to, mark) = id?.let { resolve(it) to false } ?: ((caller() ?: throw AgentsFailure("not inside an agent session; pass an agent id")) to true)
        val rows = store.inboxLimited(to, unread, mark, null, task)
        return when {
            json -> {
                val framed = rows.map { m -> kotlinx.serialization.json.JsonObject(Json.encodeToJsonElement(m).jsonObject + ("framed" to JsonPrimitive(agentMessageFrame(m)))) }
                AgentsJson.PRETTY.encodeToString(JsonArray.serializer(), JsonArray(framed)) + "\n"
            }

            rows.isEmpty() -> {
                "no messages\n"
            }

            else -> {
                rows.joinToString("\n") { agentMessageFrame(it) }
            }
        }
    }

    companion object {
        const val SEND_USAGE = "usage: rtok agents send <id-prefix> <text|->, or --all-live <text|->"

        /** `(to, text)` from the positionals: `<to> <text>`, or `--all-live <text>`. */
        fun sendArgs(
            allLive: Boolean,
            first: String?,
            second: String?,
        ): Pair<String?, String> =
            when {
                !allLive && first != null && second != null -> first to second
                allLive && first != null && second == null -> null to first
                else -> throw AgentsFailure(SEND_USAGE)
            }
    }
}
