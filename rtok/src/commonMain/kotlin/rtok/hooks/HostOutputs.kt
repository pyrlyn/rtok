// Port of the per-host stdout shapers in src/hooks/mod.rs (codex, codebuddy, gemini, codewhale,
// copilot, cline, cursor). Each takes rtok's Claude-shaped HookOutput and writes what that host reads.
// Rust builds these through serde_json::Map (a BTreeMap without `preserve_order`), so object keys
// come out sorted; sortedJson keeps the bytes identical.
package rtok.hooks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

private val EMPTY = "{}".encodeToByteArray()

/** [v] with every object's keys in byte order, recursively (serde_json `Value` ordering). */
fun sortedJson(v: JsonElement): JsonElement = when (v) {
    is JsonObject -> JsonObject(v.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sortedJson(it.value) })
    is JsonArray -> JsonArray(v.map(::sortedJson))
    else -> v
}

private fun obj(vararg kv: Pair<String, JsonElement?>): ByteArray =
    sortedJson(JsonObject(kv.filter { it.second != null }.associate { it.first to it.second!! })).toString().encodeToByteArray()

private fun str(s: String?) = s?.let(::JsonPrimitive)

/** Codex: a rewrite needs `permissionDecision: allow` beside it; MCP replacements are Cursor-only. */
fun codexOutput(out: HookOutput): ByteArray {
    val h = out.hookSpecificOutput?.let { h ->
        h.copy(updatedMcpToolOutput = null, permissionDecision = h.permissionDecision ?: if (h.updatedInput != null) "allow" else null)
    }
    return out.copy(updatedMcpToolOutput = null, hookSpecificOutput = h).encode().encodeToByteArray()
}

/** CodeBuddy reads `modifiedInput` where Claude reads `updatedInput`. */
fun codebuddyOutput(out: HookOutput): ByteArray {
    val v = sortedJson(out.toJson()).jsonObject
    val h = v["hookSpecificOutput"] as? JsonObject ?: return v.toString().encodeToByteArray()
    val input = h["updatedInput"] ?: return v.toString().encodeToByteArray()
    val renamed = sortedJson(JsonObject(h - "updatedInput" + ("modifiedInput" to input)))
    return JsonObject(v + ("hookSpecificOutput" to renamed)).let(::sortedJson).toString().encodeToByteArray()
}

/** Gemini: `decision: deny` / `hookSpecificOutput.tool_input` before a tool, `additionalContext` otherwise. */
fun geminiOutput(out: HookOutput, event: String): ByteArray {
    val h = out.hookSpecificOutput ?: return EMPTY
    if (event == "PreToolUse") {
        if (h.permissionDecision == "deny") return obj("decision" to JsonPrimitive("deny"), "reason" to str(h.permissionDecisionReason))
        h.updatedInput?.let { return obj("hookSpecificOutput" to JsonObject(mapOf("tool_input" to it))) }
        return EMPTY
    }
    h.additionalContext?.let { return obj("hookSpecificOutput" to JsonObject(mapOf("additionalContext" to JsonPrimitive(it)))) }
    return EMPTY
}

/** CodeWhale replaces the prompt text, so context is folded into a full replacement. */
fun codewhaleOutput(out: HookOutput, prompt: String?): ByteArray {
    val ctx = out.hookSpecificOutput?.additionalContext ?: return EMPTY
    val text = if (!prompt.isNullOrEmpty()) "$prompt\n\n[hook context] $ctx" else ctx
    return obj("text" to JsonPrimitive(text))
}

/** Copilot: flat `permissionDecision`, `modifiedArgs`, `additionalContext`; a Claude `block` becomes `deny`. */
fun copilotOutput(out: HookOutput): ByteArray {
    val o = LinkedHashMap<String, JsonElement>()
    out.hookSpecificOutput?.let { h ->
        h.permissionDecision?.let { o["permissionDecision"] = JsonPrimitive(it) }
        h.permissionDecisionReason?.let { o["permissionDecisionReason"] = JsonPrimitive(it) }
        h.updatedInput?.let { o["modifiedArgs"] = it }
        h.additionalContext?.let { o["additionalContext"] = JsonPrimitive(it) }
    }
    if (out.decision == "block" && "permissionDecision" !in o) {
        o["permissionDecision"] = JsonPrimitive("deny")
        out.reason?.let { o["permissionDecisionReason"] = JsonPrimitive(it) }
    }
    return sortedJson(JsonObject(o)).toString().encodeToByteArray()
}

/** Cline: `overrideInput.commands`, `context`, and `cancel` + `errorMessage` for a deny. */
fun clineOutput(out: HookOutput): ByteArray {
    val o = LinkedHashMap<String, JsonElement>()
    val h = out.hookSpecificOutput
    val deny = out.decision == "block" || h?.permissionDecision == "deny"
    if (h != null) {
        ((h.updatedInput as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.let { cmd ->
            o["overrideInput"] = JsonObject(mapOf("commands" to buildJsonArray { add(cmd) }))
        }
        h.additionalContext?.let { o["context"] = JsonPrimitive(it) }
        if (deny && h.permissionDecisionReason != null) o["errorMessage"] = JsonPrimitive(h.permissionDecisionReason)
    }
    if (deny) {
        o["cancel"] = JsonPrimitive(true)
        if ("errorMessage" !in o) out.reason?.let { o["errorMessage"] = JsonPrimitive(it) }
    }
    return sortedJson(JsonObject(o)).toString().encodeToByteArray()
}

/** Cursor: flat snake_case `updated_mcp_tool_output` / `additional_context`; anything else stays nested. */
fun cursorOutput(out: HookOutput): ByteArray {
    val nested = { out.encode().encodeToByteArray() }
    val h = out.hookSpecificOutput ?: return nested()
    val o = LinkedHashMap<String, JsonElement>()
    h.updatedMcpToolOutput?.let { o["updated_mcp_tool_output"] = it }
    h.additionalContext?.let { o["additional_context"] = JsonPrimitive(it) }
    if (o.isEmpty()) return nested()
    return sortedJson(JsonObject(o)).toString().encodeToByteArray()
}
