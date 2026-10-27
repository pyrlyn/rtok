// Port of src/proxy/anthropic.rs (Anthropic Messages, T11.1): tool results, skills, live blobs, skill refs,
// usage and opt-in context edits.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object AnthropicWire : Wire {
    const val CONTEXT_BETA = "context-management-2025-06-27"

    private val fields =
        UsageFields(
            altParent = "message",
            input = "input_tokens",
            output = "output_tokens",
            cacheCreate = "cache_creation_input_tokens",
            cacheRead = "cache_read_input_tokens",
        )

    override fun matches(path: String) = path == "/v1/messages"

    override val provider get() = Wires.ANTHROPIC_PROVIDER
    override val api get() = Wires.API_ANTHROPIC

    override fun sessionId(body: JsonElement): String? = (body as? JsonObject)?.get("metadata").str("user_id")?.takeIf { it.isNotEmpty() }

    override fun usageFromBody(body: JsonElement) = findUsage(body, fields)

    override fun usageFromSse(event: JsonElement) = findUsage(event, fields)

    override fun skillRefs(req: WireRequest) = SkillRefs.collect(req)

    /**
     * Opt-in server-side context editing (T51.2): the default `clear_tool_uses` edit when [enabled] and the
     * caller set no `context_management`. Never overwrites; the new body, or null when unchanged.
     */
    fun applyContextEdits(
        body: JsonElement,
        enabled: Boolean,
    ): JsonElement? {
        val o = body as? JsonObject
        return if (!enabled || o == null || "context_management" in o) {
            null
        } else {
            val edit = buildJsonObject { putJsonArray("edits") { addJsonObject { put("type", "clear_tool_uses_20250919") } } }
            JsonObject(o + ("context_management" to edit))
        }
    }

    private const val SKILL_BODY = "Base directory for this skill: "

    /** Content blocks of each user message: (entry, block index, block). */
    private inline fun userBlocks(
        req: WireRequest,
        each: (TurnEntry, Int, JsonObject) -> Unit,
    ) {
        for (e in turnEntries(req, "messages")) {
            if (e.obj.str("role") != "user") continue
            val blocks = e.obj["content"] as? JsonArray ?: continue
            blocks.forEachIndexed { j, b -> (b as? JsonObject)?.let { each(e, j, it) } }
        }
    }

    override fun toolResults(req: WireRequest): List<ToolResultRef> =
        buildList {
            userBlocks(req) { e, j, b ->
                if (b.str("type") != "tool_result") return@userBlocks
                val id = b.str("tool_use_id") ?: return@userBlocks
                if ("content" in b) add(ToolResultRef(req, listOf("messages", e.index, "content", j, "content"), e.turn, id))
            }
        }

    /** Skill bodies: a user text block starting with the skill banner; id from the preceding `Skill` tool_use. */
    override fun skills(req: WireRequest): List<SkillRef> =
        buildList {
            var pending: String? = null
            for (e in turnEntries(req, "messages")) {
                if (e.obj.str("role") == "assistant") {
                    (e.obj["content"] as? JsonArray)?.forEach { b ->
                        if (b.str("type") == "tool_use" && b.str("name") == "Skill") b.str("id")?.let { pending = it }
                    }
                    continue
                }
                if (e.obj.str("role") != "user") continue
                (e.obj["content"] as? JsonArray)?.forEachIndexed { j, b ->
                    if (b.str("type") != "text") return@forEachIndexed
                    val body = b.str("text") ?: return@forEachIndexed
                    if (!body.startsWith(SKILL_BODY)) return@forEachIndexed
                    val dir =
                        body
                            .removePrefix(SKILL_BODY)
                            .lineSequence()
                            .firstOrNull()
                            .orEmpty()
                            .removeSuffix("\r")
                            .trimEnd('/', '\\')
                    val name = dir.split('/', '\\').last().ifEmpty { "skill" }
                    val id = pending ?: "skill-${e.turn}"
                    pending = null
                    add(SkillRef(req, listOf("messages", e.index, "content", j, "text"), e.turn, id, name))
                }
            }
        }

    /** T51.1 user text blocks only; binary-bearing fields (`image`/`document` `source.data`) are never yielded (T55.15). */
    override fun liveBlobs(req: WireRequest): List<BlobRef> =
        buildList {
            userBlocks(req) { e, j, b ->
                if (b.str("type") == "text" && "text" in b) add(BlobRef(req, listOf("messages", e.index, "content", j, "text"), e.turn))
            }
        }
}
