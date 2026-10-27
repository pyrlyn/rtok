// Port of the ToolResults side of src/proxy/openai_chat.rs (Chat Completions, T11.2). Usage parsing and
// request preparation arrive with the proxy server chunk.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object OpenAiChatWire : ToolResults {
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
