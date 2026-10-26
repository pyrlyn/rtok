// JSON-RPC and argument helpers of `rtok mcp` (mcp.rs: rpc_error, negotiate_protocol_version,
// missing_required, prepare_args, unknown_tool, to_tool, cap_result, mem_hit_line).
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import rtok.plugin.sdk.ToolDef

/** A tool failure whose message is the whole `isError` text. */
class ToolException(
    message: String,
) : RuntimeException(message)

object Protocol {
    /** Dialects `rtok mcp` is tested against, oldest first; the last is the fallback. */
    val SUPPORTED_PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601

    /** Tools that change nothing; announced with `readOnlyHint`. */
    val READ_ONLY_TOOLS =
        setOf(
            "expand",
            "ping",
            "whoami",
            "worktree_list",
            "agent_inbox",
            "agents_list",
            "agent_show",
            "task_list",
            "task_get",
            "task_next",
            "task_ready",
            "read",
            "search",
            "tree",
            "mem_search",
            "mem_pack",
            "mem_get",
            "handoff",
            "symbol",
            "callers",
            "impact",
            "outline",
            "explore",
            "graph_diff",
            "graph_export",
            "docs_resolve",
            "docs_query",
            "docs_get",
        )

    fun rpcError(
        id: JsonElement,
        code: Int,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }

    /** The client's `protocolVersion` when supported, else the newest supported one. */
    fun negotiateProtocolVersion(requested: JsonElement?): String {
        val v = (requested as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (v != null && v in SUPPORTED_PROTOCOL_VERSIONS) v else SUPPORTED_PROTOCOL_VERSIONS.last()
    }

    /** The `initialize` result: name, version, tools capability and the negotiated dialect. */
    fun serverInfo(
        version: String,
        rtokVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "rtok")
                    put("version", rtokVersion)
                },
            )
        }

    /** The first required argument that is missing or mistyped; an `anyOf` needs one satisfied branch. */
    fun missingRequired(
        schema: JsonElement,
        args: JsonObject,
    ): String? {
        val obj = schema as? JsonObject ?: return null
        missingFields(obj["required"], obj["properties"], args)?.let { return it }
        val branches = obj["anyOf"] as? JsonArray ?: return null
        var first: String? = null
        for (branch in branches) {
            val b = branch as? JsonObject ?: continue
            val msg = missingFields(b["required"], b["properties"] ?: obj["properties"], args) ?: return null
            if (first == null) first = msg
        }
        return first
    }

    private fun missingFields(
        required: JsonElement?,
        props: JsonElement?,
        args: JsonObject,
    ): String? {
        val fields = (required as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
        val missing = fields.firstOrNull { !present(args[it], kindOf(props, it)) } ?: return null
        val got = args.keys.joinToString(", ").ifEmpty { "no arguments" }
        return "missing `$missing` (got: $got)"
    }

    private fun kindOf(
        props: JsonElement?,
        field: String,
    ): String? = (((props as? JsonObject)?.get(field) as? JsonObject)?.get("type") as? JsonPrimitive)?.content

    private fun present(
        v: JsonElement?,
        kind: String?,
    ): Boolean {
        if (v == null || v is JsonNull) return false
        val p = v as? JsonPrimitive
        return when (kind) {
            "string" -> p != null && p.isString && p.content.isNotEmpty()
            "integer" -> p != null && !p.isString && p.longOrNull != null
            "boolean" -> p != null && !p.isString && p.booleanOrNull != null
            else -> true
        }
    }

    /** `null` becomes `{}`; `search` takes `query` as `pattern`, `docs_query` takes `question`/`userQuery` as `query`. */
    fun prepareArgs(
        name: String,
        args: JsonElement?,
    ): JsonObject {
        val m = (args as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        fun absent(k: String) = m[k] == null || m[k] is JsonNull
        if (name == "search" && absent("pattern")) m.remove("query")?.let { m["pattern"] = it }
        if (name == "docs_query" && absent("query")) (m.remove("question") ?: m.remove("userQuery"))?.let { m["query"] = it }
        return JsonObject(m)
    }

    fun unknownTool(name: String) = ToolException("unknown tool: $name")

    /** A `tools/list` entry. */
    fun toTool(def: ToolDef): JsonObject =
        buildJsonObject {
            put("name", def.name)
            put("description", def.description)
            put("inputSchema", def.inputSchema as? JsonObject ?: JsonObject(emptyMap()))
            if (def.name in READ_ONLY_TOOLS) put("annotations", buildJsonObject { put("readOnlyHint", true) })
        }

    /** A `tools/call` result. */
    fun callResult(
        text: String,
        ok: Boolean,
    ): JsonObject =
        buildJsonObject {
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    ),
                ),
            )
            put("isError", !ok)
        }

    /** One `mem_search` hit: id, then title and snippet without repeating either. */
    fun memHitLine(
        id: Int,
        title: String,
        snippet: String,
    ): String {
        val s = snippet.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val t = title.trim()
        return when {
            s.isEmpty() || s == t -> "$id $t"
            t.isEmpty() || s.contains(t) -> "$id $s"
            else -> "$id $t: $s"
        }
    }

    // ---- argument readers
    fun str(
        args: JsonObject,
        k: String,
    ): String? = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun long(
        args: JsonObject,
        k: String,
    ): Long? = (args[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun bool(
        args: JsonObject,
        k: String,
    ): Boolean? = (args[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun int(
        args: JsonObject,
        k: String,
    ): Int? = long(args, k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** A non-negative integer argument, saturated at `u32::MAX`. */
    fun saturatingU32(
        args: JsonObject,
        k: String,
    ): UInt? = long(args, k)?.takeIf { it >= 0 }?.let { if (it > UInt.MAX_VALUE.toLong()) UInt.MAX_VALUE else it.toUInt() }
}
