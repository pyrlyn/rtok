// Port of src/hooks/types.rs — host hook I/O (Claude Code contract + adapters for Cursor,
// Copilot, Devin, Gemini, CodeWhale, Cline, Command Code, Grok). Unknown fields round-trip via `extra`.
package rtok.hooks

import kotlinx.serialization.json.*
import rtok.canonicalToolName

data class PreToolUse(val toolName: String, val toolInput: JsonElement)
data class PostToolUse(val toolName: String, val toolInput: JsonElement, val toolResponse: JsonElement)
data class SessionStart(val source: String)
data class PromptSubmit(val prompt: String)
data class SubagentStart(val agentType: String, val taskDescription: String)
data class PreCompact(val trigger: String, val transcriptPath: String)

/** Union of every hook event's input. Event-specific fields are nullable. */
data class HookInput(
    var sessionId: String = "",
    var agentId: String? = null,
    var agentType: String? = null,
    var taskDescription: String? = null,
    var hookEventName: String = "",
    var transcriptPath: String? = null,
    var cwd: String? = null,
    var permissionMode: String? = null,
    var toolName: String? = null,
    var toolInput: JsonElement? = null,
    var toolUseId: String? = null,
    var toolResponse: JsonElement? = null,
    var prompt: String? = null,
    var source: String? = null,
    var trigger: String? = null,
    var customInstructions: String? = null,
    val extra: LinkedHashMap<String, JsonElement> = LinkedHashMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (sessionId.isNotEmpty()) put("session_id", sessionId)
        agentId?.let { put("agent_id", it) }; agentType?.let { put("agent_type", it) }; taskDescription?.let { put("task_description", it) }
        if (hookEventName.isNotEmpty()) put("hook_event_name", hookEventName)
        transcriptPath?.let { put("transcript_path", it) }; cwd?.let { put("cwd", it) }; permissionMode?.let { put("permission_mode", it) }
        toolName?.let { put("tool_name", it) }; toolInput?.let { put("tool_input", it) }; toolUseId?.let { put("tool_use_id", it) }
        toolResponse?.let { put("tool_response", it) }; prompt?.let { put("prompt", it) }; source?.let { put("source", it) }
        trigger?.let { put("trigger", it) }; customInstructions?.let { put("custom_instructions", it) }
        for ((k, v) in extra) put(k, v)
    }

    fun encode(): String = toJson().toString()

    // ---- host adapters ----

    fun adaptCursor(event: String) {
        takeTranscriptPathAlias()
        if (event == "afterMCPExecution" || hookEventName == "afterMCPExecution") {
            hookEventName = "AfterMCPExecution"
            if (toolName == null) extra["tool_name"].str()?.let { toolName = it }
            if (toolResponse == null) extra["result_json"].str()?.let { toolResponse = JsonPrimitive(it) }
        }
        if (sessionId.isEmpty()) extra["conversation_id"].str()?.let { sessionId = it }
        if (toolResponse == null) toolResponse = takeJsonish("tool_output") ?: takeJsonish("result_json")
        if (toolName != null) {
            if (toolInput == null) toolInput = JsonObject(emptyMap())
            hookEventName = cursorEvent(event, hookEventName); return
        }
        val cmd = extra["command"].str()
        if (cmd == null) { hookEventName = cursorEvent(event, hookEventName); return }
        toolName = "Bash"; toolInput = buildJsonObject { put("command", cmd) }
        if (cursorEvent(event, hookEventName) == "PostToolUse") {
            hookEventName = "PostToolUse"
            if (toolResponse == null) toolResponse = extra["output"] ?: extra["stdout"] ?: JsonPrimitive("")
        } else hookEventName = "PreToolUse"
    }

    fun adaptCopilot(event: String) {
        if (sessionId.isEmpty()) lift("sessionId")?.let { sessionId = it }
        if (toolName == null) lift("toolName")?.let { toolName = canonicalToolName(it) }
        if (toolInput == null) extra.remove("toolArgs")?.let { toolInput = decodeToolArgs(it) }
        if (toolResponse == null) extra.remove("toolResult")?.let { toolResponse = it }
        if (agentType == null) lift("agentName")?.let { agentType = it }
        if (taskDescription == null) lift("agentDescription")?.let { taskDescription = it }
        hookEventName = claudeEvent(hookEventName.ifEmpty { event })
    }

    fun adaptDevin(event: String, projectDir: String?) {
        toolName = toolName?.let(::canonicalToolName)
        (toolResponse as? JsonObject)?.let { resp ->
            if (!resp.containsKey("stdout")) resp["output"].str()?.takeIf { it.isNotEmpty() }?.let { toolResponse = JsonObject(resp + ("stdout" to JsonPrimitive(it))) }
        }
        if (cwd == null) cwd = projectDir?.takeIf { it.isNotEmpty() }
        if (hookEventName.isEmpty()) hookEventName = event
        if (hookEventName == "PostCompaction") hookEventName = "PostCompact"
    }

    fun adaptGemini(event: String) {
        hookEventName = geminiEvent(hookEventName.ifEmpty { event })
        toolName = toolName?.let(::canonicalToolName)
        (toolResponse as? JsonObject)?.let { resp ->
            val c = resp["llmContent"]
            if (!resp.containsKey("stdout") && c is JsonPrimitive && c.isString) toolResponse = JsonObject(resp + ("stdout" to c))
        }
    }

    fun adaptCodewhale(event: String) {
        hookEventName = event
        if (prompt == null) prompt = lift("text")
    }

    fun adaptCline(event: String) {
        val hookName = lift("hookName")?.takeIf { it.isNotEmpty() }
        if (sessionId.isEmpty()) { lift("taskId")?.let { sessionId = it } } else extra.remove("taskId")
        if (cwd == null) {
            val roots = extra.remove("workspaceRoots") as? JsonArray
            roots?.firstNotNullOfOrNull { it.str() }?.trim()?.takeIf { it.isNotEmpty() }?.let { cwd = it }
        } else extra.remove("workspaceRoots")
        if (toolName == null) {
            val call = extra["tool_call"] as? JsonObject
            if (call != null) {
                extra.remove("tool_call"); val m = LinkedHashMap(call)
                val name = m.remove("name").str(); var input: JsonElement = m.remove("input") ?: JsonNull; val useId = m.remove("id").str()
                extra.putAll(m)
                if (name != null) { toolName = canonicalToolName(name); if (toolName == "Bash") input = singleCommand(input); toolInput = input }
                if (toolUseId == null) toolUseId = useId
            }
        }
        if (toolResponse == null) {
            val res = extra["tool_result"] as? JsonObject
            if (res != null) {
                extra.remove("tool_result"); val m = LinkedHashMap(res)
                val name = m.remove("name").str(); val input = m.remove("input"); val output = m.remove("output"); val error = m.remove("error"); val useId = m.remove("id").str()
                extra.putAll(m)
                if (toolName == null && name != null) toolName = canonicalToolName(name)
                if (toolInput == null && input != null) toolInput = if (toolName == "Bash") singleCommand(input) else input
                if (toolUseId == null) toolUseId = useId
                val os = output.str(); val es = error.str()
                toolResponse = when {
                    os != null && es != null && es.isNotEmpty() -> buildJsonObject { put("stdout", os); put("stderr", es) }
                    os != null -> JsonPrimitive(os)
                    output != null -> output
                    error != null -> error
                    else -> JsonPrimitive("")
                }
            }
        }
        val name = hookName ?: hookEventName.ifEmpty { event }
        hookEventName = clineEvent(name)
    }

    fun adaptCommandcode(event: String, projectDir: String?) {
        if (sessionId.isEmpty()) lift("session_id")?.let { sessionId = it }
        toolName = toolName?.let(::canonicalToolName)
        if (cwd == null) cwd = projectDir?.takeIf { it.isNotEmpty() }
        if (hookEventName.isEmpty()) hookEventName = event
    }

    fun adaptGrok(event: String) {
        val session = lift("sessionId"); val name = lift("toolName"); val input = extra.remove("toolInput"); val result = extra.remove("toolResult")
        val useId = lift("toolUseId"); val mode = lift("permissionMode"); val root = lift("workspaceRoot")
        if (sessionId.isEmpty()) sessionId = session ?: ""
        toolName = (toolName ?: name)?.let { if (it == "run_terminal_command") "Bash" else it }
        toolInput = toolInput ?: input; toolResponse = toolResponse ?: result; toolUseId = toolUseId ?: useId
        permissionMode = permissionMode ?: mode; cwd = cwd ?: root
        (toolResponse as? JsonObject)?.let { resp ->
            val o = resp["output_for_prompt"]
            if (!resp.containsKey("stdout") && o is JsonPrimitive && o.isString) toolResponse = JsonObject(resp + ("stdout" to o))
        }
        if (hookEventName.isEmpty()) hookEventName = event
    }

    fun takeTranscriptPathAlias() { if (transcriptPath == null) lift("transcriptPath")?.let { transcriptPath = it } }

    // ---- event views ----

    fun preTool(): PreToolUse? { if (hookEventName != "PreToolUse") return null; return PreToolUse(toolName ?: return null, toolInput ?: return null) }
    fun postTool(): PostToolUse? { if (hookEventName != "PostToolUse") return null; return PostToolUse(toolName ?: return null, toolInput ?: return null, toolResponse ?: return null) }
    fun sessionStart(): SessionStart? = if (hookEventName == "SessionStart") SessionStart(source ?: "startup") else null
    fun promptSubmit(): PromptSubmit? { if (hookEventName != "UserPromptSubmit") return null; return PromptSubmit(prompt ?: return null) }
    fun subagentStart(): SubagentStart? = if (hookEventName == "SubagentStart") SubagentStart(agentType ?: "", taskDescription ?: "") else null
    fun preCompact(): PreCompact? = if (hookEventName == "PreCompact") PreCompact(trigger ?: "auto", transcriptPath ?: "") else null
    fun mcpServerName(): String? = extra["mcp_server_name"].str()

    private fun lift(key: String): String? { val v = extra.remove(key); return v.str() }

    /** Cursor `tool_output` / `result_json` is often a JSON string of the result object. */
    private fun takeJsonish(key: String): JsonElement? {
        val v = extra.remove(key) ?: return null
        val s = v.str() ?: return v
        return runCatching { Json.parseToJsonElement(s) }.getOrNull() ?: JsonPrimitive(s)
    }

    companion object {
        private val STRING_KEYS = setOf("session_id", "hook_event_name")
        private val OPT_STRING = setOf("agent_id", "agent_type", "task_description", "transcript_path", "cwd", "permission_mode", "tool_name", "tool_use_id", "prompt", "source", "trigger", "custom_instructions")

        fun parse(text: String): HookInput = fromJson(Json.parseToJsonElement(text))

        /** Mirrors serde: typed fields must have their type (null = absent for Options); others go to `extra`. */
        fun fromJson(v: JsonElement): HookInput {
            val o = v as? JsonObject ?: throw IllegalArgumentException("invalid type: expected a hook input object")
            val h = HookInput()
            for ((k, x) in o) {
                fun s(): String? = when { x is JsonNull && k in OPT_STRING -> null; x is JsonPrimitive && x.isString -> x.content
                    else -> throw IllegalArgumentException("invalid type for `$k`: expected a string") }
                when (k) {
                    "session_id" -> h.sessionId = s() ?: ""; "hook_event_name" -> h.hookEventName = s() ?: ""
                    "agent_id" -> h.agentId = s(); "agent_type" -> h.agentType = s(); "task_description" -> h.taskDescription = s()
                    "transcript_path" -> h.transcriptPath = s(); "cwd" -> h.cwd = s(); "permission_mode" -> h.permissionMode = s()
                    "tool_name" -> h.toolName = s(); "tool_use_id" -> h.toolUseId = s(); "prompt" -> h.prompt = s()
                    "source" -> h.source = s(); "trigger" -> h.trigger = s(); "custom_instructions" -> h.customInstructions = s()
                    "tool_input" -> h.toolInput = x.takeUnless { it is JsonNull }; "tool_response" -> h.toolResponse = x.takeUnless { it is JsonNull }
                    else -> h.extra[k] = x
                }
            }
            return h
        }
    }
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Copilot documents `toolArgs` as a JSON string; only a string that parses to an object is replaced. */
private fun decodeToolArgs(args: JsonElement): JsonElement {
    val s = args.str() ?: return args
    return (runCatching { Json.parseToJsonElement(s) }.getOrNull() as? JsonObject) ?: args
}

private fun cursorEvent(cli: String, stdin: String) = when (val n = stdin.ifEmpty { cli }) {
    "sessionStart" -> "SessionStart"; "beforeSubmitPrompt" -> "UserPromptSubmit"; "sessionEnd" -> "SessionEnd"
    "postToolUse", "PostToolUse", "afterShellExecution" -> "PostToolUse"
    "preToolUse", "PreToolUse", "beforeShellExecution" -> "PreToolUse"
    "afterMCPExecution" -> "AfterMCPExecution"; "preCompact" -> "PreCompact"; else -> n
}

private fun claudeEvent(n: String) = when (n) {
    "preToolUse" -> "PreToolUse"; "postToolUse" -> "PostToolUse"; "sessionStart" -> "SessionStart"; "sessionEnd" -> "SessionEnd"
    "userPromptSubmitted" -> "UserPromptSubmit"; "preCompact" -> "PreCompact"; "subagentStart" -> "SubagentStart"; else -> n
}

private fun geminiEvent(n: String) = when (n) {
    "BeforeTool" -> "PreToolUse"; "AfterTool" -> "PostToolUse"; "BeforeAgent" -> "UserPromptSubmit"; "PreCompress" -> "PreCompact"; else -> n
}

private fun clineEvent(n: String) = when (n) {
    "tool_call", "PreToolUse" -> "PreToolUse"; "tool_result", "PostToolUse" -> "PostToolUse"
    "agent_start", "agent_resume", "TaskStart", "TaskResume", "SessionStart" -> "SessionStart"
    "prompt_submit", "UserPromptSubmit" -> "UserPromptSubmit"
    "agent_end", "session_shutdown", "TaskComplete", "SessionShutdown", "SessionEnd" -> "SessionEnd"
    else -> "Noop"
}

/** A one-entry Cline `commands: [cmd]` becomes `command: cmd`; multi-entry calls pass through. */
private fun singleCommand(input: JsonElement): JsonElement {
    val m = input as? JsonObject ?: return input
    val cmds = m["commands"] as? JsonArray
    return if (!m.containsKey("command") && cmds != null && cmds.size == 1) JsonObject(m + ("command" to cmds[0])) else input
}

/** Hook stdout. The default encodes to `{}` (no opinion, fail open). */
data class HookSpecificOutput(
    val hookEventName: String = "",
    val permissionDecision: String? = null,
    val permissionDecisionReason: String? = null,
    val updatedInput: JsonElement? = null,
    val additionalContext: String? = null,
    val updatedMcpToolOutput: JsonElement? = null,
) {
    fun toJson() = buildJsonObject {
        put("hookEventName", hookEventName); permissionDecision?.let { put("permissionDecision", it) }
        permissionDecisionReason?.let { put("permissionDecisionReason", it) }; updatedInput?.let { put("updatedInput", it) }
        additionalContext?.let { put("additionalContext", it) }; updatedMcpToolOutput?.let { put("updatedMcpToolOutput", it) }
    }
    companion object {
        fun fromJson(o: JsonObject) = HookSpecificOutput(o["hookEventName"].str() ?: throw IllegalArgumentException("missing field `hookEventName`"),
            o["permissionDecision"].str(), o["permissionDecisionReason"].str(), o["updatedInput"]?.takeUnless { it is JsonNull },
            o["additionalContext"].str(), o["updatedMcpToolOutput"]?.takeUnless { it is JsonNull })
    }
}

data class HookOutput(
    val `continue`: Boolean? = null,
    val stopReason: String? = null,
    val suppressOutput: Boolean? = null,
    val systemMessage: String? = null,
    val decision: String? = null,
    val reason: String? = null,
    val hookSpecificOutput: HookSpecificOutput? = null,
    val updatedMcpToolOutput: JsonElement? = null,
) {
    fun toJson() = buildJsonObject {
        `continue`?.let { put("continue", it) }; stopReason?.let { put("stopReason", it) }; suppressOutput?.let { put("suppressOutput", it) }
        systemMessage?.let { put("systemMessage", it) }; decision?.let { put("decision", it) }; reason?.let { put("reason", it) }
        hookSpecificOutput?.let { put("hookSpecificOutput", it.toJson()) }; updatedMcpToolOutput?.let { put("updatedMcpToolOutput", it) }
    }
    fun encode() = toJson().toString()
    companion object {
        fun fromJson(o: JsonObject) = HookOutput((o["continue"] as? JsonPrimitive)?.booleanOrNull, o["stopReason"].str(), (o["suppressOutput"] as? JsonPrimitive)?.booleanOrNull,
            o["systemMessage"].str(), o["decision"].str(), o["reason"].str(), (o["hookSpecificOutput"] as? JsonObject)?.let(HookSpecificOutput::fromJson),
            o["updatedMcpToolOutput"]?.takeUnless { it is JsonNull })
        fun parse(text: String) = fromJson(Json.parseToJsonElement(text).jsonObject)
    }
}

/** Shared test helpers: one PreToolUse-shaped output, one PostToolUse-shaped output. */
fun preOut(decision: String?, reason: String?, input: JsonElement?) =
    HookOutput(hookSpecificOutput = HookSpecificOutput("PreToolUse", decision, reason, input))
fun postOut(ctx: String) = HookOutput(hookSpecificOutput = HookSpecificOutput("PostToolUse", additionalContext = ctx))
