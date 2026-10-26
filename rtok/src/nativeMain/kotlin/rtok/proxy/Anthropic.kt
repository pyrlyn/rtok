// Port of the ToolResults side of src/proxy/anthropic.rs (Anthropic Messages, T11.1). Usage parsing,
// context edits and skill_refs arrive with the proxy (Phase 7).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object AnthropicWire : ToolResults {
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
