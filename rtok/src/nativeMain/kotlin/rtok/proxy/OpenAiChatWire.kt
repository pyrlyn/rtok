// Port of src/proxy/openai_chat.rs (Chat Completions, T11.2).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object OpenAiChatWire : Wire {
    /** `prompt_tokens` counts cached tokens too, so `input` is the uncached rest; no cache-write signal. */
    private val fields =
        UsageFields(
            input = "prompt_tokens",
            output = "completion_tokens",
            cacheRead = "cached_tokens",
            cacheReadDetails = "prompt_tokens_details",
            inputIncludesCache = true,
        )

    override fun matches(path: String) = path == "/v1/chat/completions"

    override val api get() = Wires.API_OPENAI_CHAT

    override fun usageFromBody(body: JsonElement) = findUsage(body, fields)

    override fun usageFromSse(event: JsonElement) = findUsage(event, fields)

    override fun skillRefs(req: WireRequest) = SkillRefs.collect(req)

    /**
     * A streaming request that has not opted in gets `stream_options.include_usage = true`, the one byte-level
     * change passthrough makes; `[proxy] include_usage = false` turns it off. A malformed or caller-set
     * option is never overwritten.
     */
    override fun prepareRequest(
        body: JsonElement,
        includeUsage: Boolean,
    ): JsonElement? {
        val o = body as? JsonObject
        val opts = o?.get("stream_options")
        val streaming = includeUsage && o?.get("stream") == JsonPrimitive(true)
        return when {
            !streaming || o == null -> null
            opts == null -> JsonObject(o + ("stream_options" to buildJsonObject { put("include_usage", true) }))
            opts !is JsonObject || "include_usage" in opts -> null
            else -> JsonObject(o + ("stream_options" to JsonObject(opts + ("include_usage" to JsonPrimitive(true)))))
        }
    }

    /** Each tool result is its own `role: "tool"` message keyed by `tool_call_id`. */
    override fun toolResults(req: WireRequest): List<ToolResultRef> =
        turnEntries(req, "messages").mapNotNull { e ->
            if (e.obj.str("role") != "tool") return@mapNotNull null
            val id = e.obj.str("tool_call_id") ?: return@mapNotNull null
            if ("content" !in e.obj) return@mapNotNull null
            ToolResultRef(req, listOf("messages", e.index, "content"), e.turn, id)
        }

    /** T51.1 user text: whole strings and `text` parts; `image_url.url` is never yielded (T55.15). */
    override fun liveBlobs(req: WireRequest): List<BlobRef> =
        buildList {
            for (e in turnEntries(req, "messages")) {
                if (e.obj.str("role") != "user") continue
                when (val c = e.obj["content"]) {
                    is JsonPrimitive -> {
                        if (c.isString) add(BlobRef(req, listOf("messages", e.index, "content"), e.turn))
                    }

                    is JsonArray -> {
                        c.forEachIndexed { j, p ->
                            if (p.str("type") == "text" && (p as JsonObject).containsKey("text")) {
                                add(BlobRef(req, listOf("messages", e.index, "content", j, "text"), e.turn))
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
}
