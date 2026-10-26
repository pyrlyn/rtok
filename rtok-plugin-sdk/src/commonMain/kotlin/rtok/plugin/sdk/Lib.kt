// Port of crates/rtok-plugin-sdk/src/lib.rs — the plugin contract.
// Rules: fail open; lossless by default (put the handle in Measurement.refId);
// a saving that is not a Measurement row does not exist.
package rtok.plugin.sdk

import kotlinx.serialization.json.JsonElement

/** Where a plugin is reachable from (D2). */
enum class Surface(val str: String) { Hook("hook"), Mcp("mcp"), Proxy("proxy"), Cli("cli");
    fun asStr() = str }

/** Id, surfaces, and whether it is on unless configuration says otherwise. */
data class Manifest(val id: String, val surfaces: List<Surface>, val defaultOn: Boolean)

/** A before/after pair produced by one plugin action (D3). */
data class Measurement(
    val plugin: String, val kind: String,
    val beforeBytes: ULong, val afterBytes: ULong,
    val estBefore: UInt, val estAfter: UInt,
    val refId: String? = null, val callId: Int? = null,
)

/** What a plugin may do to a PreToolUse event. First Deny wins; Rewrite is last-writer. */
sealed class PreToolDecision {
    data class Deny(val reason: String) : PreToolDecision()
    data class Rewrite(val input: JsonElement, val reason: String) : PreToolDecision()
}

/** Text offered for injection; emitted in priority order (higher first) until the budget is spent (D5). */
data class Injection(val plugin: String, val text: String, val priority: UByte)

/** The page a plugin contributes to operator surfaces (D23). */
data class DashboardPage(val title: String, val summary: String, val savesTokens: Boolean, val fields: List<Pair<String, String>> = emptyList())

/** An MCP tool exposed by `rtok mcp`; one short sentence of description. */
data class ToolDef(val name: String, val description: String, val inputSchema: JsonElement)

data class PreToolUse(val toolName: String, val toolInput: JsonElement)
data class PostToolUse(val toolName: String, val toolInput: JsonElement, val toolResponse: JsonElement)
data class SessionStart(val source: String)
data class PromptSubmit(val prompt: String)
data class PreCompact(val trigger: String, val transcriptPath: String)
data class SubagentStart(val agentType: String, val taskDescription: String)

/** One token-reduction method. Implement the surfaces the manifest declares; never throw on the hot path. */
interface Plugin {
    fun manifest(): Manifest
    fun dashboardPage(): DashboardPage
    fun preTool(ev: PreToolUse, cx: Ctx): PreToolDecision? = null
    fun postTool(ev: PostToolUse, cx: Ctx): String? = null
    fun sessionStart(ev: SessionStart, cx: Ctx): Injection? = null
    fun promptSubmit(ev: PromptSubmit, cx: Ctx): Injection? = null
    fun preCompact(ev: PreCompact, cx: Ctx) {}
    fun subagentStart(ev: SubagentStart, cx: Ctx): Injection? = null
    fun mcpTools(): List<ToolDef> = emptyList()
    fun proxyFilter(req: WireRequest, cx: Ctx): List<Measurement> = emptyList()
}

const val TASK_PREFIX_MAX = 8

/** 1..8 ASCII letters, or an error naming the prefix. */
fun checkTaskPrefix(prefix: String) {
    if (prefix.isEmpty() || prefix.length > TASK_PREFIX_MAX || !prefix.all { it in 'a'..'z' || it in 'A'..'Z' })
        throw IllegalArgumentException("task prefix ${debugStr(prefix)} must be 1 to $TASK_PREFIX_MAX ASCII letters")
}

/** Rust `{:?}` of a str. */
fun debugStr(s: String) = buildString {
    append('"'); for (c in s) when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) }; append('"')
}
